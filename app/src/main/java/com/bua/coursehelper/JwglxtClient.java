package com.bua.coursehelper;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 正方教务管理系统（ZFSoft jwglxt）客户端。
 *
 * 企业微信工作台里的「教学管理信息服务平台」就是这套系统，
 * 例如北京农学院：https://jwglxt.bua.edu.cn/jwglxt
 *
 * 登录流程（与网页 login.js 一致）：
 *   1. GET  /xtgl/login_slogin.html         取 csrftoken 与 JSESSIONID
 *   2. GET  /xtgl/login_getPublicKey.html   取 RSA 公钥 modulus / exponent
 *   3. RSA(PKCS#1 v1.5) 加密密码后 base64
 *   4. POST /xtgl/login_slogin.html?time=…  提交 yhm / mm / csrftoken
 *      （连续输错若干次后系统会要求验证码，此时带 yzm 字段重试）
 *
 * 课表接口：
 *   POST /kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151
 *   xnm=学年(2025)  xqm=学期(3=第一学期, 12=第二学期, 16=第三学期)
 */
public class JwglxtClient {

    public static final String DEFAULT_BASE_URL = "https://jwglxt.bua.edu.cn/jwglxt";

    private static final Pattern CSRF = Pattern.compile("id=\"csrftoken\"[^>]*value=\"([^\"]*)\"");
    private static final Pattern TIPS = Pattern.compile("id=\"tips\"[^>]*>(.*?)</p>", Pattern.DOTALL);
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final int timeoutMs;

    public String csrfToken = "";
    public String studentName = "";
    private String timetableEndpoint = null;

    public JwglxtClient(String baseUrl, String username, String password) {
        String url = (baseUrl == null || baseUrl.trim().isEmpty()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.timeoutMs = 20000;

        // 让 HttpURLConnection 自动管理 JSESSIONID 等 Cookie
        CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(manager);
    }

    // ---------------- 对外接口 ----------------

    /** 登录结果 */
    public static class Result {
        public final boolean ok;
        public final String message;

        public Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    /**
     * 登录。
     *
     * @param captcha 验证码，可为空；服务端要求验证码时返回值里会带「验证码」字样
     */
    public Result login(String captcha) {
        if (username.isEmpty() || password.isEmpty()) {
            return new Result(false, "请先填写学号与密码");
        }
        try {
            String loginHtml = httpGet("/xtgl/login_slogin.html", null);
            csrfToken = extract(CSRF, loginHtml);

            String[] key = fetchPublicKey();
            String mm = rsaEncrypt(password, key[0], key[1]);

            Map<String, String> form = new LinkedHashMap<>();
            form.put("csrftoken", csrfToken);
            form.put("language", "zh_CN");
            form.put("ydType", "");
            form.put("yhm", username);
            form.put("mm", mm);
            if (captcha != null && !captcha.trim().isEmpty()) {
                form.put("yzm", captcha.trim());
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", baseUrl + "/xtgl/login_slogin.html");
            headers.put("Content-Type", "application/x-www-form-urlencoded");

            String resp = httpPost("/xtgl/login_slogin.html?time=" + System.currentTimeMillis(),
                    form, headers, false);

            if (isLoggedIn()) {
                return new Result(true, "登录成功");
            }

            String tips = extract(TIPS, resp);
            tips = tips == null ? "" : tips.replaceAll("<[^>]+>", "").trim();
            if (tips.isEmpty()) {
                boolean looksLikeLoginPage = resp != null
                        && resp.contains("login_slogin") && resp.contains("id=\"yhm\"");
                tips = looksLikeLoginPage
                        ? "登录失败：用户名或密码不正确"
                        : "登录失败：服务器返回的页面不像登录结果。"
                        + "请确认手机连着校园网 WiFi（教务系统是校内地址）。";
            }
            return new Result(false, tips);
        } catch (Exception e) {
            return new Result(false, "登录异常：" + e.getMessage());
        }
    }

    /** 判断当前是否已登录 */
    public boolean isLoggedIn() {
        try {
            String html = httpGet("/xtgl/index_initMenu.html", null);
            return html != null && !html.contains("login_slogin") && html.length() > 500;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取 RSA 公钥，返回 {modulus, exponent}。
     * 如果拿回来的不是 JSON（最常见：手机没连校园网，被门户/运营商页面拦截），
     * 就把服务器真正返回的东西带进错误信息里，方便定位。
     */
    public String[] fetchPublicKey() throws Exception {
        Response resp = requestFull("GET",
                "/xtgl/login_getPublicKey.html?time=" + System.currentTimeMillis(), null, null, true);
        String text = resp.text().trim();
        if (!text.startsWith("{")) {
            throw new Exception(describeBadResponse(resp, "获取登录公钥"));
        }
        JSONObject key = new JSONObject(text);
        return new String[]{key.getString("modulus"), key.getString("exponent")};
    }

    /** 把「返回的不是预期数据」描述成人能看懂的话 */
    private String describeBadResponse(Response resp, String what) {
        String body = resp.text().trim();
        String snippet = body.length() > 180 ? body.substring(0, 180) + "…" : body;
        snippet = snippet.replaceAll("\\s+", " ");

        StringBuilder sb = new StringBuilder();
        sb.append(what).append("失败");
        if (resp.status > 0) {
            sb.append("（HTTP ").append(resp.status).append("）");
        }
        sb.append("：");

        Matcher title = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(body);
        if (title.find()) {
            String pageTitle = title.group(1).replaceAll("\\s+", " ").trim();
            if (!pageTitle.isEmpty()) {
                sb.append("服务器返回的是网页「").append(pageTitle).append("」，不是数据。");
            }
        } else if (body.toLowerCase().startsWith("<!doctype") || body.toLowerCase().startsWith("<html")) {
            sb.append("服务器返回的是网页，不是数据。");
        }

        boolean lookedLikeHtml = body.startsWith("<");
        if (lookedLikeHtml) {
            sb.append("\n最常见的两个原因：1) 手机没连校园网 WiFi（教务系统是校内地址，"
                    + "用流量或校外网络访问不到）；2) 中间有上网认证/门户页面拦截。");
            sb.append("\n服务器实际返回的开头：").append(snippet);
        } else {
            sb.append(snippet);
        }
        return sb.toString();
    }

    /** 抓取验证码图片（连续输错若干次后系统会要求） */
    public byte[] getCaptchaImage() {
        try {
            return requestRaw("GET", "/kaptcha?time=" + System.currentTimeMillis(),
                    null, null, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** 抓取指定学年学期的课表原始 JSON */
    public String fetchTimetableJson(String xnm, String xqm) throws Exception {
        String[] candidates = new String[]{timetableEndpoint(), "/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"};
        Exception last = null;
        for (String path : candidates) {
            try {
                String resp = postTimetable(path, xnm, xqm);
                if (resp != null && resp.trim().startsWith("{")) {
                    return resp;
                }
                last = new Exception("课表接口 " + path + " 没有返回 JSON；返回内容开头："
                        + (resp == null ? "(空)" : resp.substring(0, Math.min(200, resp.trim().length()))));
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("课表接口调用失败") : last;
    }

    private String postTimetable(String path, String xnm, String xqm) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("xnm", xnm);
        form.put("xqm", xqm);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Referer", baseUrl + "/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151");
        headers.put("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");

        Response resp = requestFull("POST", path, encodeForm(form), headers, true);
        String text = resp.text().trim();
        if (text.startsWith("{")) {
            return text;
        }
        if (text.startsWith("<")) {
            // 有的版本会把 JSON 包在页面里
            Matcher m = Pattern.compile("(\\{.*\\})", Pattern.DOTALL).matcher(text);
            if (m.find()) {
                return m.group(1);
            }
            throw new Exception(describeBadResponse(resp, "获取课表数据"));
        }
        return text;
    }

    /**
     * 不同版本的正方，课表数据接口路径不完全一样。
     * 先打开「学生课表查询」页面，从页面脚本里找出它真正请求的接口；
     * 找不到就用网上最常见的那个路径。
     */
    private String timetableEndpoint() {
        if (timetableEndpoint != null) {
            return timetableEndpoint;
        }
        String fallback = "/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151";
        try {
            String page = httpGet("/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151", null);
            if (page != null && !page.contains("login_slogin")) {
                Matcher m = Pattern.compile("[\"']([^\"']*xskbcx[^\"']*\\.html[^\"']*)[\"']").matcher(page);
                while (m.find()) {
                    String candidate = m.group(1).replace("&amp;", "&");
                    if (candidate.contains("cx")) {
                        timetableEndpoint = candidate.startsWith("/") ? candidate : "/" + candidate;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // 页面打不开就用默认路径
        }
        if (timetableEndpoint == null) {
            timetableEndpoint = fallback;
        }
        return timetableEndpoint;
    }

    /** 带年份的日期区间：2026-09-07至2026-09-13 / 2026/9/7-2026/9/13 / 2026年9月7日至9月13日 */
    private static final Pattern FULL_RANGE = Pattern.compile(
            "(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?\\s*(?:至|到|~|—|－|-{1,2})\\s*"
                    + "(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})");

    /** 不带年份的日期区间：09-07至09-13 / 9月7日至9月13日 */
    private static final Pattern SHORT_RANGE = Pattern.compile(
            "(?<!\\d)(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?\\s*(?:至|到|~|—|－)\\s*"
                    + "(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?");

    /**
     * 读「周次」下拉框里每周的起始日期（index 0 = 第 1 周），用来推算开学第一周周一。
     * 取不到返回空数组。
     *
     * @param xnm 学年起始年（如 "2026"），用于解释不带年份的日期
     */
    public String[] fetchWeekRanges(String xnm) {
        try {
            Response resp = requestFull("GET", "/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151",
                    null, null, true);
            String html = resp.text();

            // 优先只在 <select id="zc"> 里找，避免抓到页面其它地方的日期
            String scope = html;
            Matcher select = Pattern.compile("<select[^>]*id=\"zc\"[^>]*>(.*?)</select>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
            if (select.find()) {
                scope = select.group(1);
            }

            List<String> starts = collectRangeStarts(scope, xnm);
            if (starts.isEmpty() && scope != html) {
                starts = collectRangeStarts(html, xnm);
            }
            return starts.toArray(new String[0]);
        } catch (Exception e) {
            return new String[0];
        }
    }

    /** 第 1 周周一是哪天（yyyy-MM-dd）；取不到返回空串 */
    public String fetchTermStart(String xnm) {
        String[] starts = fetchWeekRanges(xnm);
        return starts.length > 0 ? starts[0] : "";
    }

    /** 从任意文本里取第一个日期区间的起始日（给内置网页导入用） */
    public static String firstRangeStart(String text, String xnm) {
        List<String> starts = collectRangeStarts(text, xnm);
        return starts.isEmpty() ? "" : starts.get(0);
    }

    private static List<String> collectRangeStarts(String text, String xnm) {
        List<String> starts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return starts;
        }
        Matcher full = FULL_RANGE.matcher(text);
        while (full.find()) {
            try {
                starts.add(String.format(Locale.US, "%04d-%02d-%02d",
                        Integer.parseInt(full.group(1)),
                        Integer.parseInt(full.group(2)),
                        Integer.parseInt(full.group(3))));
            } catch (NumberFormatException ignored) {
            }
        }
        if (!starts.isEmpty()) {
            return starts;
        }

        int baseYear = 0;
        try {
            baseYear = Integer.parseInt(xnm);
        } catch (Exception ignored) {
        }
        if (baseYear <= 0) {
            baseYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        }
        Matcher shortRange = SHORT_RANGE.matcher(text);
        while (shortRange.find()) {
            try {
                int month = Integer.parseInt(shortRange.group(1));
                int day = Integer.parseInt(shortRange.group(2));
                // 学年从 9 月开始：9~12 月属于起始年，1~8 月属于下一年
                int year = month >= 8 ? baseYear : baseYear + 1;
                starts.add(String.format(Locale.US, "%04d-%02d-%02d", year, month, day));
            } catch (NumberFormatException ignored) {
            }
        }
        return starts;
    }

    /**
     * 从课表页 / 首页里找「当前周次」，找不到返回 0。
     * 有的正方案会把「现在是第几周」直接写进页面。
     */
    public int fetchCurrentWeekHint() {
        String[] pages = {"/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151", "/xtgl/index_initMenu.html"};
        for (String path : pages) {
            try {
                Response resp = requestFull("GET", path, null, null, true);
                int week = findCurrentWeek(resp.text());
                if (week > 0) {
                    return week;
                }
            } catch (Exception ignored) {
                // 换下一个页面
            }
        }
        return 0;
    }

    private static int findCurrentWeek(String html) {
        if (html == null || html.isEmpty()) {
            return 0;
        }
        String[] patterns = {
                "id=\"dqzc\"[^>]*>\\s*(\\d+)",
                "id=\"dqzc\"[^>]*value=\"(\\d+)\"",
                "当前周[次]?\\s*[：:]?\\s*第?\\s*(\\d+)\\s*周",
                "(\\d+)\\s*周\\s*[（(]\\s*当前\\s*[)）]",
                "dqz\\s*[=:]\\s*['\"]?(\\d+)",
                "dqzc\\s*[=:]\\s*['\"]?(\\d+)",
        };
        for (String pattern : patterns) {
            Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) {
                try {
                    int week = Integer.parseInt(m.group(1));
                    if (week >= 1 && week <= 30) {
                        return week;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    // ---------------- RSA ----------------

    /** 正方网页同款：RSA / PKCS#1 v1.5 加密后 Base64 */
    public static String rsaEncrypt(String text, String modulusB64, String exponentB64) throws Exception {
        BigInteger n = new BigInteger(1, Base64.decode(modulusB64, Base64.DEFAULT));
        BigInteger e = new BigInteger(1, Base64.decode(exponentB64, Base64.DEFAULT));
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] out = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    // ---------------- HTTP ----------------

    private String httpGet(String path, Map<String, String> headers) throws Exception {
        return request("GET", path, null, headers, true);
    }

    private String httpPost(String path, Map<String, String> form,
                            Map<String, String> headers, boolean jsonExpected) throws Exception {
        return request("POST", path, encodeForm(form), headers, jsonExpected);
    }

    /** 表单编码（和浏览器提交的格式一致） */
    private static String encodeForm(Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
        }
        return body.toString();
    }

    /** 一次 HTTP 请求的完整结果（带状态码和地址，出错时方便定位） */
    public static class Response {
        public final int status;
        public final String url;
        public final String contentType;
        public final byte[] body;

        Response(int status, String url, String contentType, byte[] body) {
            this.status = status;
            this.url = url == null ? "" : url;
            this.contentType = contentType == null ? "" : contentType;
            this.body = body == null ? new byte[0] : body;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private String request(String method, String path, String body,
                           Map<String, String> headers, boolean jsonExpected) throws Exception {
        return requestFull(method, path, body, headers, jsonExpected).text();
    }

    private byte[] requestRaw(String method, String path, String body,
                              Map<String, String> headers, boolean jsonExpected) throws Exception {
        return requestFull(method, path, body, headers, jsonExpected).body;
    }

    /** 自己处理 302 跳转，保证 POST 后的 Cookie 不丢 */
    private Response requestFull(String method, String path, String body,
                                 Map<String, String> headers, boolean jsonExpected) throws Exception {
        String url = path.startsWith("http") ? path : baseUrl + path;
        String currentMethod = method;
        String currentBody = body;

        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            if (conn instanceof HttpsURLConnection) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(trustAllContext().getSocketFactory());
                ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
            }
            conn.setRequestMethod(currentMethod);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("Accept", jsonExpected
                    ? "application/json, text/javascript, */*; q=0.01"
                    : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if ("POST".equals(currentMethod)) {
                conn.setDoOutput(true);
                byte[] data = (currentBody == null ? "" : currentBody).getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                }
            }

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new Exception("服务器返回 " + code + " 但没有 Location");
                }
                if (location.startsWith("/")) {
                    String origin = baseUrl;
                    int idx = origin.indexOf("/", origin.indexOf("//") + 2);
                    if (idx > 0) {
                        origin = origin.substring(0, idx);
                    }
                    location = origin + location;
                }
                url = location;
                // 302 → GET（浏览器的默认行为）
                currentMethod = "GET";
                currentBody = null;
                continue;
            }

            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            byte[] data = is == null ? new byte[0] : readAllBytes(is);
            String contentType = conn.getHeaderField("Content-Type");
            conn.disconnect();
            return new Response(code, url, contentType, data);
        }
        throw new Exception("重定向次数过多");
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) {
            bos.write(buf, 0, len);
        }
        is.close();
        return bos.toByteArray();
    }

    private static String extract(Pattern pattern, String text) {
        if (text == null) {
            return "";
        }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 学校内网常见自签名证书，这里统一放行（只用于访问本学校的教务系统） */
    private static SSLContext trustAllContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        ctx.init(null, managers, new java.security.SecureRandom());
        return ctx;
    }
}
