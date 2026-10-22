package com.bua.coursehelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从教务系统导出的课表 PDF 里还原课表。
 *
 * 这类 PDF 的形状（企业微信里「导出课表 / 打印」得到的）：
 *   第一行是标题（如「张三课表」）和 2026-2027学年第1学期、学号；
 *   然后是一张表格：表头「时间段 节次 | 星期一 … 星期日」，一列一天；
 *   每个格子里是多行文字：第一行课程名，后面几行是
 *   `(1-2节)3周,7-17周(单)/场地:教A-301/教师:邢玉红/教学班组成:…/学分:4.0`（会被折成好几行）。
 *
 * 解析思路：按「星期一…星期日」的横坐标把文字分列 → 列内按 y 间距分块 → 块内第一段是课名，
 * 从第一个 ASCII 左括号开始是详情 → 从详情里取节次/周次/场地/教师。
 */
public final class TimetablePdfParser {

    private static final String[] WEEKDAYS = {
            "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
    };

    /** 同一门课的多行文字，行间距约 12pt；超过这个值认为换了一门课 */
    private static final float BLOCK_GAP = 14f;

    private static final Pattern NODE_RANGE = Pattern.compile(
            "\\((\\d{1,2})\\s*[-—~至]\\s*(\\d{1,2})\\s*节\\)");
    private static final Pattern NODE_SINGLE = Pattern.compile("\\((\\d{1,2})\\s*节\\)");
    private static final Pattern PLACE = Pattern.compile("场地\\s*[:：]\\s*([^/]*)");
    private static final Pattern TEACHER = Pattern.compile("教师\\s*[:：]\\s*([^/]*)");
    private static final Pattern TERM = Pattern.compile("(\\d{4})\\s*-\\s*(\\d{4})\\s*学年\\s*第\\s*(\\d)\\s*学期");
    private static final Pattern STUDENT = Pattern.compile("^(.{1,12}?)课表$");

    private TimetablePdfParser() {
    }

    public static Timetable parse(byte[] pdfBytes, String fallbackXnm, String fallbackXqm)
            throws Exception {
        List<PdfTextExtractor.Page> pages = PdfTextExtractor.extract(pdfBytes);
        if (pages.isEmpty()) {
            throw new Exception("这个 PDF 里没有可提取的文字（可能是扫描/图片版），暂时解析不了");
        }
        PdfTextExtractor.Page grid = null;
        for (PdfTextExtractor.Page page : pages) {
            if (countWeekdayHeaders(page) >= 5) {
                grid = page;
                break;
            }
        }
        if (grid == null) {
            throw new Exception("没找到「星期一…星期日」的表格，请确认是教务系统导出的课表 PDF");
        }
        return fromGrid(grid, fallbackXnm, fallbackXqm);
    }

    // ---------------- 表格 → 课表 ----------------

    private static Timetable fromGrid(PdfTextExtractor.Page page, String fallbackXnm, String fallbackXqm)
            throws Exception {
        List<Float> columns = new ArrayList<>();
        float headerY = Float.MIN_VALUE;
        for (String day : WEEKDAYS) {
            PdfTextExtractor.TextRun run = findRun(page.runs, day);
            if (run != null) {
                columns.add(run.x);
                headerY = Math.max(headerY, run.y);
            }
        }
        if (columns.size() < 5) {
            throw new Exception("表格里没找到星期列（星期一…星期日）");
        }
        float columnWidth = columns.size() > 1
                ? (columns.get(columns.size() - 1) - columns.get(0)) / (columns.size() - 1)
                : 100f;

        // 按列分文字（表头及以上不要；左侧"节次/时间段"列不要）
        List<List<PdfTextExtractor.TextRun>> byColumn = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            byColumn.add(new ArrayList<>());
        }
        for (PdfTextExtractor.TextRun run : page.runs) {
            if (run.y >= headerY - 3f) {
                continue;                       // 标题 / 表头 / 学号
            }
            int column = nearest(columns, run.x);
            if (column < 0) {
                continue;
            }
            float delta = run.x - columns.get(column);
            if (delta < -columnWidth * 0.35f || delta > columnWidth * 0.75f) {
                continue;                       // 属于左侧的节次编号列
            }
            byColumn.get(column).add(run);
        }

        Timetable timetable = new Timetable();
        timetable.xnm = fallbackXnm == null ? "" : fallbackXnm;
        timetable.xqm = fallbackXqm == null ? "3" : fallbackXqm;
        readHeaderInfo(page, timetable);

        int skipped = 0;
        for (int column = 0; column < byColumn.size(); column++) {
            List<PdfTextExtractor.TextRun> runs = byColumn.get(column);
            if (runs.isEmpty()) {
                continue;
            }
            Collections.sort(runs, (a, b) -> Float.compare(b.y, a.y));

            List<List<PdfTextExtractor.TextRun>> blocks = new ArrayList<>();
            List<PdfTextExtractor.TextRun> current = new ArrayList<>();
            float previousY = Float.NaN;
            for (PdfTextExtractor.TextRun run : runs) {
                if (!Float.isNaN(previousY) && previousY - run.y > BLOCK_GAP) {
                    blocks.add(current);
                    current = new ArrayList<>();
                }
                current.add(run);
                previousY = run.y;
            }
            if (!current.isEmpty()) {
                blocks.add(current);
            }

            for (List<PdfTextExtractor.TextRun> block : blocks) {
                Course course = toCourse(block, column + 1);
                if (course == null) {
                    skipped++;
                    continue;
                }
                if (!containsSame(timetable.courses, course)) {
                    timetable.courses.add(course);
                }
            }
        }

        if (timetable.courses.isEmpty()) {
            throw new Exception("PDF 表格里没有解析出课程"
                    + (skipped > 0 ? "（有 " + skipped + " 段文字没认出节次）" : ""));
        }
        timetable.fetchedAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.CHINA).format(new java.util.Date());
        return timetable;
    }

    /** 一段块内文字 → 一门课 */
    private static Course toCourse(List<PdfTextExtractor.TextRun> block, int weekday) {
        StringBuilder name = new StringBuilder();
        StringBuilder detail = new StringBuilder();
        boolean detailStarted = false;
        for (PdfTextExtractor.TextRun run : block) {
            String text = run.text;
            if (!detailStarted && text.startsWith("(")) {
                detailStarted = true;
            }
            if (detailStarted) {
                detail.append(text);
            } else {
                name.append(text);
            }
        }
        // 万一整段都没有 "("，就把第一行当课名
        if (name.length() == 0 && block.size() > 1) {
            name.append(block.get(0).text);
            detail.setLength(0);
            for (int i = 1; i < block.size(); i++) {
                detail.append(block.get(i).text);
            }
        }

        String detailText = detail.toString();
        int start = 1;
        int end = 2;
        Matcher range = NODE_RANGE.matcher(detailText);
        if (range.find()) {
            start = parseInt(range.group(1), 1);
            end = parseInt(range.group(2), start);
        } else {
            Matcher single = NODE_SINGLE.matcher(detailText);
            if (single.find()) {
                start = parseInt(single.group(1), 1);
                end = start;
            } else {
                return null;      // 认不出节次，跳过
            }
        }
        if (end < start) {
            end = start;
        }

        Course course = new Course();
        course.name = cleanName(name.toString());
        if (course.name.isEmpty()) {
            return null;
        }
        course.weekday = weekday;
        course.startNode = start;
        course.endNode = end;
        course.nodeText = (start == end) ? String.valueOf(start) : (start + "-" + end);

        // 周次：节次括号之后、"/场地" 之前
        String weeksText = detailText;
        int bracket = weeksText.indexOf(')');
        if (bracket >= 0) {
            weeksText = weeksText.substring(bracket + 1);
        }
        int cut = weeksText.length();
        for (String marker : new String[]{"/场地", "/教师", "/教学班", "/选课备注", "/学分", "/"}) {
            int at = weeksText.indexOf(marker);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        weeksText = weeksText.substring(0, cut).trim();
        course.weekText = weeksText;
        course.weeks = Timetable.parseWeeks(weeksText);

        Matcher place = PLACE.matcher(detailText);
        if (place.find()) {
            course.place = place.group(1).trim();
        }
        Matcher teacher = TEACHER.matcher(detailText);
        if (teacher.find()) {
            course.teacher = teacher.group(1).trim();
        }
        return course;
    }

    /** 课名里常带类型标记（★ 理论线下、○ 实验…）和折行残留，清一下 */
    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw;
        name = name.replaceAll("[\\s\\u00a0]+", "");
        name = name.replaceAll("[★☆○●◆◇■□▲△]+$", "");
        return name.trim();
    }

    private static boolean containsSame(List<Course> courses, Course candidate) {
        for (Course c : courses) {
            if (c.name.equals(candidate.name) && c.weekday == candidate.weekday
                    && c.nodeText.equals(candidate.nodeText) && c.weekText.equals(candidate.weekText)) {
                return true;
            }
        }
        return false;
    }

    /** 标题里的学年学期、学生姓名 */
    private static void readHeaderInfo(PdfTextExtractor.Page page, Timetable timetable) {
        for (PdfTextExtractor.TextRun run : page.runs) {
            Matcher term = TERM.matcher(run.text);
            if (term.find()) {
                timetable.xnm = term.group(1);
                String index = term.group(3);
                if ("1".equals(index)) {
                    timetable.xqm = "3";
                } else if ("2".equals(index)) {
                    timetable.xqm = "12";
                } else if ("3".equals(index)) {
                    timetable.xqm = "16";
                }
            }
            Matcher student = STUDENT.matcher(run.text.trim());
            if (student.find()) {
                String name = student.group(1).trim();
                if (!name.isEmpty() && name.length() <= 12) {
                    timetable.student = name;
                }
            }
        }
    }

    private static int countWeekdayHeaders(PdfTextExtractor.Page page) {
        int count = 0;
        for (String day : WEEKDAYS) {
            if (findRun(page.runs, day) != null) {
                count++;
            }
        }
        return count;
    }

    private static PdfTextExtractor.TextRun findRun(List<PdfTextExtractor.TextRun> runs, String text) {
        for (PdfTextExtractor.TextRun run : runs) {
            if (run.text.contains(text)) {
                return run;
            }
        }
        return null;
    }

    private static int nearest(List<Float> values, float x) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < values.size(); i++) {
            float distance = Math.abs(values.get(i) - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return fallback;
        }
    }
}
