package com.bua.coursehelper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Inflater;

/**
 * 极简 PDF 文字提取器 —— 只针对教务系统导出的这种 PDF：
 *   * 内容流是 FlateDecode 压缩的（用 java.util.zip.Inflater 解压，安卓自带）；
 *   * 文字用 `1 0 0 1 x y Tm (字符串) Tj` 这种最简单的写法；
 *   * 字体是标准 CJK 字体（如 STSong-Light + UniGB-UCS2-H），字符串按 UTF-16BE 直接解码。
 *
 * 提取结果带上坐标，后面 {@link TimetablePdfParser} 就靠坐标把表格还原成课表。
 * 故意不引入任何第三方 PDF 库，保持整个 App 零依赖。
 */
public final class PdfTextExtractor {

    /** 一段文字：坐标是 PDF 内容流里的原始坐标（左上角原点在左下） */
    public static class TextRun {
        public final float x;
        public final float y;
        public final float size;
        public final String text;

        TextRun(float x, float y, float size, String text) {
            this.x = x;
            this.y = y;
            this.size = size;
            this.text = text;
        }
    }

    /** 表格线/单元格矩形（re 算子） */
    public static class Rect {
        public final float x;
        public final float y;
        public final float w;
        public final float h;

        Rect(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public static class Page {
        public final List<TextRun> runs = new ArrayList<>();
        public final List<Rect> rects = new ArrayList<>();
    }

    private PdfTextExtractor() {
    }

    public static List<Page> extract(byte[] data) {
        List<Page> pages = new ArrayList<>();
        for (byte[] stream : inflatedStreams(data)) {
            String probe = new String(stream, StandardCharsets.ISO_8859_1);
            if (!probe.contains("Tj") && !probe.contains("TJ")) {
                continue;      // 不是内容流（可能是字体/图片）
            }
            try {
                pages.add(parseContent(stream));
            } catch (Exception ignored) {
            }
        }
        return pages;
    }

    // ---------------- 找到并解压内容流 ----------------

    private static List<byte[]> inflatedStreams(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        byte[] marker = "stream".getBytes(StandardCharsets.ISO_8859_1);
        byte[] endMarker = "endstream".getBytes(StandardCharsets.ISO_8859_1);
        int index = 0;
        while (true) {
            int at = indexOf(data, marker, index);
            if (at < 0) {
                break;
            }
            int dictStart = Math.max(0, at - 800);
            String dict = new String(data, dictStart, at - dictStart, StandardCharsets.ISO_8859_1);
            int start = at + marker.length;
            if (start < data.length && data[start] == '\r') {
                start++;
            }
            if (start < data.length && data[start] == '\n') {
                start++;
            }
            int end = indexOf(data, endMarker, start);
            if (end < 0) {
                break;
            }
            index = end + endMarker.length;
            if (!dict.contains("/FlateDecode") || !dict.contains("obj")) {
                continue;
            }
            byte[] inflated = inflate(Arrays.copyOfRange(data, start, end));
            if (inflated != null && inflated.length > 0) {
                out.add(inflated);
            }
        }
        return out;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = Math.max(0, from); i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] inflate(byte[] raw) {
        Inflater inflater = new Inflater();
        inflater.setInput(raw);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, raw.length * 5));
        byte[] chunk = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                buffer.write(chunk, 0, n);
            }
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
        return buffer.toByteArray();
    }

    // ---------------- 解析内容流里的算子和文字 ----------------

    private static Page parseContent(byte[] stream) {
        Page page = new Page();
        Tokenizer tokenizer = new Tokenizer(stream);
        List<Object> operands = new ArrayList<>();

        float[] tm = {1, 0, 0, 1, 0, 0};
        float[] tlm = {1, 0, 0, 1, 0, 0};
        float fontSize = 10f;
        float leading = 0f;

        while (tokenizer.hasNext()) {
            Object token = tokenizer.next();
            if (!(token instanceof String)) {
                operands.add(token);
                continue;
            }
            String op = (String) token;
            try {
                switch (op) {
                    case "BT":
                        tm = new float[]{1, 0, 0, 1, 0, 0};
                        tlm = new float[]{1, 0, 0, 1, 0, 0};
                        break;
                    case "Tf":
                        if (operands.size() >= 2) {
                            fontSize = toFloat(operands.get(operands.size() - 1), fontSize);
                        }
                        break;
                    case "TL":
                        if (!operands.isEmpty()) {
                            leading = toFloat(operands.get(operands.size() - 1), leading);
                        }
                        break;
                    case "Tm":
                        if (operands.size() >= 6) {
                            int n = operands.size();
                            tm = new float[]{
                                    toFloat(operands.get(n - 6), 1), toFloat(operands.get(n - 5), 0),
                                    toFloat(operands.get(n - 4), 0), toFloat(operands.get(n - 3), 1),
                                    toFloat(operands.get(n - 2), 0), toFloat(operands.get(n - 1), 0)};
                            tlm = tm.clone();
                        }
                        break;
                    case "Td":
                    case "TD":
                        if (operands.size() >= 2) {
                            int n = operands.size();
                            float tx = toFloat(operands.get(n - 2), 0);
                            float ty = toFloat(operands.get(n - 1), 0);
                            tlm = translate(tlm, tx, ty);
                            tm = tlm.clone();
                            if ("TD".equals(op)) {
                                leading = -ty;
                            }
                        }
                        break;
                    case "T*":
                        tlm = translate(tlm, 0, -leading);
                        tm = tlm.clone();
                        break;
                    case "Tj":
                    case "'":
                    case "\"":
                        for (int i = operands.size() - 1; i >= 0; i--) {
                            if (operands.get(i) instanceof byte[]) {
                                addRun(page, tm, fontSize, (byte[]) operands.get(i));
                                break;
                            }
                        }
                        break;
                    case "TJ":
                        if (!operands.isEmpty()) {
                            Object last = operands.get(operands.size() - 1);
                            if (last instanceof List) {
                                for (Object item : (List<?>) last) {
                                    if (item instanceof byte[]) {
                                        addRun(page, tm, fontSize, (byte[]) item);
                                    }
                                }
                            }
                        }
                        break;
                    case "re":
                        if (operands.size() >= 4) {
                            int n = operands.size();
                            page.rects.add(new Rect(
                                    toFloat(operands.get(n - 4), 0), toFloat(operands.get(n - 3), 0),
                                    toFloat(operands.get(n - 2), 0), toFloat(operands.get(n - 1), 0)));
                        }
                        break;
                    default:
                        break;
                }
            } catch (Exception ignored) {
            }
            operands.clear();
        }
        return page;
    }

    private static float[] translate(float[] matrix, float tx, float ty) {
        return new float[]{matrix[0], matrix[1], matrix[2], matrix[3],
                matrix[4] + tx * matrix[0] + ty * matrix[2],
                matrix[5] + tx * matrix[1] + ty * matrix[3]};
    }

    private static void addRun(Page page, float[] tm, float fontSize, byte[] bytes) {
        String text = decode(bytes);
        if (text == null) {
            return;
        }
        text = text.trim();
        if (text.isEmpty()) {
            return;
        }
        page.runs.add(new TextRun(tm[4], tm[5], fontSize, text));
    }

    /**
     * 标准 CJK 字体（UniGB-UCS2-H 之类）的字符串就是 UTF-16BE；
     * 万一是单字节编码的导出，再用 GBK 兜一下。
     */
    private static String decode(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        if (bytes.length % 2 == 0) {
            try {
                String utf16 = new String(bytes, StandardCharsets.UTF_16BE);
                if (looksSane(utf16)) {
                    return utf16;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            String gbk = new String(bytes, Charset.forName("GBK"));
            if (looksSane(gbk)) {
                return gbk;
            }
        } catch (Exception ignored) {
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static boolean looksSane(String text) {
        if (text.isEmpty()) {
            return false;
        }
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
                bad++;
            }
        }
        return bad * 5 < text.length();
    }

    private static float toFloat(Object value, float fallback) {
        if (value instanceof Double) {
            return ((Double) value).floatValue();
        }
        if (value instanceof String) {
            try {
                return Float.parseFloat((String) value);
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    // ---------------- 极简 PDF 内容流分词器 ----------------

    private static class Tokenizer {
        private final byte[] data;
        private int pos;

        Tokenizer(byte[] data) {
            this.data = data;
        }

        boolean hasNext() {
            skipSpace();
            return pos < data.length;
        }

        Object next() {
            skipSpace();
            if (pos >= data.length) {
                return "";
            }
            byte c = data[pos];
            if (c == '(') {
                return readLiteralString();
            }
            if (c == '<') {
                if (pos + 1 < data.length && data[pos + 1] == '<') {
                    pos += 2;
                    return "<<";
                }
                return readHexString();
            }
            if (c == '>') {
                if (pos + 1 < data.length && data[pos + 1] == '>') {
                    pos += 2;
                    return ">>";
                }
                pos++;
                return ">";
            }
            if (c == '[') {
                pos++;
                return readArray();
            }
            if (c == ']') {
                pos++;
                return "]";
            }
            if (c == '/' || c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                if (c == '/') {
                    return readName();
                }
                return readNumber();
            }
            return readKeyword();
        }

        private void skipSpace() {
            while (pos < data.length) {
                byte c = data[pos];
                if (c == '%') {
                    while (pos < data.length && data[pos] != '\n' && data[pos] != '\r') {
                        pos++;
                    }
                    continue;
                }
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == 0) {
                    pos++;
                    continue;
                }
                break;
            }
        }

        private String readKeyword() {
            int start = pos;
            while (pos < data.length) {
                byte c = data[pos];
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == 0
                        || c == '/' || c == '(' || c == '[' || c == '<' || c == '%') {
                    break;
                }
                pos++;
            }
            if (pos == start) {
                pos++;
            }
            return new String(data, start, pos - start, StandardCharsets.ISO_8859_1);
        }

        private String readName() {
            pos++;   // 跳过 '/'
            int start = pos;
            while (pos < data.length) {
                byte c = data[pos];
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == 0
                        || c == '/' || c == '(' || c == '[' || c == '<' || c == '>' || c == '%') {
                    break;
                }
                pos++;
            }
            return "/" + new String(data, start, pos - start, StandardCharsets.ISO_8859_1);
        }

        private Double readNumber() {
            int start = pos;
            while (pos < data.length) {
                byte c = data[pos];
                if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            try {
                return Double.parseDouble(new String(data, start, pos - start,
                        StandardCharsets.ISO_8859_1));
            } catch (Exception e) {
                return 0d;
            }
        }

        private List<Object> readArray() {
            List<Object> items = new ArrayList<>();
            while (true) {
                skipSpace();
                if (pos >= data.length) {
                    break;
                }
                if (data[pos] == ']') {
                    pos++;
                    break;
                }
                Object item = next();
                if ("]".equals(item)) {
                    break;
                }
                items.add(item);
            }
            return items;
        }

        private byte[] readLiteralString() {
            pos++;   // 跳过 '('
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int depth = 1;
            while (pos < data.length) {
                byte c = data[pos++];
                if (c == '\\') {
                    if (pos >= data.length) {
                        break;
                    }
                    byte esc = data[pos++];
                    switch (esc) {
                        case 'n': buffer.write('\n'); break;
                        case 'r': buffer.write('\r'); break;
                        case 't': buffer.write('\t'); break;
                        case 'b': buffer.write('\b'); break;
                        case 'f': buffer.write('\f'); break;
                        case '(': buffer.write('('); break;
                        case ')': buffer.write(')'); break;
                        case '\\': buffer.write('\\'); break;
                        case '\r':
                            if (pos < data.length && data[pos] == '\n') {
                                pos++;
                            }
                            break;
                        case '\n': break;
                        default:
                            if (esc >= '0' && esc <= '7') {
                                int value = esc - '0';
                                for (int i = 0; i < 2 && pos < data.length; i++) {
                                    byte d = data[pos];
                                    if (d < '0' || d > '7') {
                                        break;
                                    }
                                    value = value * 8 + (d - '0');
                                    pos++;
                                }
                                buffer.write(value);
                            } else {
                                buffer.write(esc);
                            }
                            break;
                    }
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                buffer.write(c);
            }
            return buffer.toByteArray();
        }

        private byte[] readHexString() {
            pos++;   // 跳过 '<'
            ByteArrayOutputStream digits = new ByteArrayOutputStream();
            while (pos < data.length && data[pos] != '>') {
                byte c = data[pos++];
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                    digits.write(c);
                }
            }
            if (pos < data.length) {
                pos++;   // 跳过 '>'
            }
            byte[] hex = digits.toByteArray();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            for (int i = 0; i + 1 < hex.length; i += 2) {
                buffer.write((hexValue(hex[i]) << 4) | hexValue(hex[i + 1]));
            }
            return buffer.toByteArray();
        }

        private int hexValue(byte c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            return c - 'A' + 10;
        }
    }
}
