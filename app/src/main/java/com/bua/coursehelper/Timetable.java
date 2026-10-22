package com.bua.coursehelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次导入的完整课表：负责解析正方教务系统的课表 JSON、周次字段，
 * 以及本地缓存用的序列化。
 */
public class Timetable {

    /**
     * 默认作息时间（第 1~12 节，每节 45 分钟、课间 10 分钟）。
     *
     * 已按北京农学院实际作息校准：**下午第一节 13:30、晚上第一节 18:30**。
     * 如果教务系统返回了节次时间，会优先用接口给的时间（这里只作兜底）。
     */
    public static final String[] DEFAULT_NODE_TIMES = {
            "08:00-08:45", "08:55-09:40", "10:00-10:45", "10:55-11:40",
            "13:30-14:15", "14:25-15:10", "15:20-16:05", "16:15-17:00",
            "18:30-19:15", "19:25-20:10", "20:20-21:05", "21:15-22:00"
    };

    private static final Pattern RANGE = Pattern.compile("(\\d+)\\s*[-~—]\\s*(\\d+)");
    /** 周次上限（一学期不会超过 30 周） */
    private static final int MAX_WEEK = 30;    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}[:：]\\d{2})\\s*[-~—]\\s*(\\d{1,2}[:：]\\d{2})");

    public List<Course> courses = new ArrayList<>();
    public String xnm = "";         // 学年起始年，如 2025
    public String xqm = "3";        // 正方学期代码：3/12/16
    public String student = "";     // 学生姓名（接口里有就用）
    public String fetchedAt = "";   // 抓取时间
    public String termStart = "";   // 第 1 周周一，格式 yyyy-MM-dd（用于自动定位本周）
    public boolean termStartGuessed = false;   // 开学日是不是从接口里的日期推断出来的
    public String[] nodeTimes = DEFAULT_NODE_TIMES.clone();
    /** 节次时间是不是教务系统给的（否则用内置作息表，升级 App 后会自动跟着变） */
    public boolean nodeTimesFromServer = false;

    // ---------------- 解析 ----------------

    /** 解析 /kbcx/xskbcx_cxXsKb.html 返回的 JSON */
    public static Timetable parse(String json, String xnm, String xqm) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray kbList = root.optJSONArray("kbList");
        if (kbList == null) {
            kbList = root.optJSONArray("kbListByWeek");
        }
        if (kbList == null) {
            throw new JSONException("接口没有返回 kbList 字段，可能是登录失效或系统版本不同");
        }

        Timetable t = new Timetable();
        t.xnm = xnm == null ? "" : xnm;
        t.xqm = xqm == null ? "3" : xqm;

        for (int i = 0; i < kbList.length(); i++) {
            JSONObject item = kbList.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Course c = new Course();
            c.name = item.optString("kcmc").trim();
            c.teacher = firstNonEmpty(item.optString("xm"), item.optString("jsxm")).trim();
            c.place = firstNonEmpty(item.optString("cdmc"), item.optString("jsmc")).trim();
            c.nodeText = item.optString("jcs").trim();
            c.weekText = item.optString("zcd").trim();
            c.kch = item.optString("kch").trim();
            c.weekday = parseWeekday(item.optString("xqj"));
            int[] nodes = parseNodes(c.nodeText);
            c.startNode = nodes[0];
            c.endNode = nodes[1];
            c.weeks = parseWeeks(c.weekText);
            if (!c.name.isEmpty() && c.weekday >= 1 && c.startNode > 0) {
                t.courses.add(c);
            }
        }

        String[] times = parseNodeTimes(root.optJSONArray("sjkList"));
        if (times != null && times.length > 0) {
            t.nodeTimes = times;
            t.nodeTimesFromServer = true;
        }

        JSONObject xsxx = root.optJSONObject("xsxx");
        if (xsxx != null) {
            t.student = firstNonEmpty(xsxx.optString("XM"), xsxx.optString("xm")).trim();
        }
        t.fetchedAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date());

        // 接口里常常带着学期起止日期，翻一翻当作「开学第一周周一」的兜底，
        // 这样即使读不到周次下拉框，也能自动定位到本周。
        String guessed = guessTermStart(root, t.maxWeek(), new java.util.Date());
        if (!guessed.isEmpty()) {
            t.termStart = guessed;
            t.termStartGuessed = true;
        }
        return t;
    }

    /**
     * 在接口返回的 JSON 里找"最可能的开学第一周周一"：
     * 收集所有 yyyy-MM-dd，只留下「是周一、且不晚于今天、且距今不超过课表周数」的日期，
     * 取离今天最近的那个。找不到返回空串。
     */
    private static String guessTermStart(Object node, int maxWeek, java.util.Date today) {
        java.util.List<String> dates = new java.util.ArrayList<>();
        collectDates(node, dates, 0);

        String best = "";
        int bestDrift = Integer.MAX_VALUE;
        int limit = Math.max(maxWeek, 20);
        for (String date : dates) {
            if (!isMonday(date)) {
                continue;
            }
            int drift = weeksBetween(date, today);
            if (drift < 0 || drift > limit) {
                continue;
            }
            if (drift < bestDrift) {
                bestDrift = drift;
                best = date;
            }
        }
        return best;
    }

    private static void collectDates(Object node, java.util.List<String> out, int depth) {
        if (node == null || depth > 4) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object value = object.opt(keys.next());
                if (value instanceof String) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("(\\d{4}-\\d{2}-\\d{2})").matcher((String) value);
                    while (m.find()) {
                        out.add(m.group(1));
                    }
                } else {
                    collectDates(value, out, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("(\\d{4}-\\d{2}-\\d{2})").matcher((String) value);
                    while (m.find()) {
                        out.add(m.group(1));
                    }
                } else {
                    collectDates(value, out, depth + 1);
                }
            }
        }
    }

    private static boolean isMonday(String date) {
        if (date == null || date.trim().isEmpty()) {
            return false;
        }
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
            format.setLenient(false);
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.setTime(format.parse(date.trim()));
            return calendar.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.MONDAY;
        } catch (Exception e) {
            return false;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty() && !"null".equals(a.trim())) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static int parseWeekday(String xqj) {
        if (xqj == null) {
            return 0;
        }
        Matcher m = DIGITS.matcher(xqj);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /** "3-4" → {3,4}；"5" → {5,5} */
    public static int[] parseNodes(String jcs) {
        if (jcs == null) {
            return new int[]{0, 0};
        }
        Matcher m = DIGITS.matcher(jcs);
        int min = Integer.MAX_VALUE, max = 0;
        boolean any = false;
        while (m.find()) {
            int v = Integer.parseInt(m.group());
            min = Math.min(min, v);
            max = Math.max(max, v);
            any = true;
        }
        return any ? new int[]{min, max} : new int[]{0, 0};
    }

    /**
     * 解析周次字段，支持 "1-16周" / "1-5,7-9" / "1-16周(单)" / "3,5,7" 等写法。
     *
     * 注意：单双周是**按段**判断的，不能整串判断 —— 像
     * "3-5周(单),6-8周(双),9-18周" 这种混合写法，整串同时含"单""双"，
     * 早期版本会互相抵消导致一周都解析不出来。
     */
    public static int[] parseWeeks(String zcd) {
        if (zcd == null || zcd.trim().isEmpty()) {
            return new int[0];
        }
        String text = zcd.trim();

        Set<Integer> set = new LinkedHashSet<>();
        for (String part : text.split("[,，、;；]")) {
            if (part.trim().isEmpty()) {
                continue;
            }
            // 单双周按段判断；同一段里同时出现"单""双"（如"单双周"）表示每周交替，不做奇偶过滤
            boolean onlyOdd = part.contains("单") && !part.contains("双");
            boolean onlyEven = part.contains("双") && !part.contains("单");

            Set<Integer> partWeeks = new LinkedHashSet<>();
            Matcher m = RANGE.matcher(part);
            if (m.find()) {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                if (a > b) {
                    int tmp = a;
                    a = b;
                    b = tmp;
                }
                for (int w = a; w <= b; w++) {
                    partWeeks.add(w);
                }
            } else {
                Matcher d = DIGITS.matcher(part);
                boolean anyDigit = false;
                while (d.find()) {
                    partWeeks.add(Integer.parseInt(d.group()));
                    anyDigit = true;
                }
                if (!anyDigit) {
                    // "单周" / "双周" / "每周" 这种没写周次的：先铺满整个学期，再由下面按奇偶过滤
                    for (int w = 1; w <= MAX_WEEK; w++) {
                        partWeeks.add(w);
                    }
                }
            }

            for (int w : partWeeks) {
                if (w <= 0 || w > MAX_WEEK) {
                    continue;
                }
                if (onlyOdd && w % 2 == 0) {
                    continue;
                }
                if (onlyEven && w % 2 == 1) {
                    continue;
                }
                set.add(w);
            }
        }

        int[] out = new int[set.size()];
        int index = 0;
        for (int w : set) {
            out[index++] = w;
        }
        Arrays.sort(out);
        return out;
    }

    /** {1,2,3,5} → "1-3,5" */
    public static String weeksToText(int[] weeks) {
        if (weeks == null || weeks.length == 0) {
            return "";
        }
        int[] sorted = weeks.clone();
        Arrays.sort(sorted);
        StringBuilder sb = new StringBuilder();
        int start = sorted[0], prev = sorted[0];
        for (int i = 1; i < sorted.length; i++) {
            int w = sorted[i];
            if (w == prev + 1) {
                prev = w;
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(start == prev ? String.valueOf(start) : start + "-" + prev);
            start = prev = w;
        }
        if (sb.length() > 0) {
            sb.append(",");
        }
        sb.append(start == prev ? String.valueOf(start) : start + "-" + prev);
        return sb.toString();
    }

    /** 给人看的周次文本：单双周会写成 "1-15周(单)" */
    public static String weeksLabel(int[] weeks) {
        if (weeks == null || weeks.length == 0) {
            return "";
        }
        int[] sorted = weeks.clone();
        Arrays.sort(sorted);
        boolean allOdd = true;
        boolean allEven = true;
        for (int w : sorted) {
            if (w % 2 == 0) {
                allOdd = false;
            } else {
                allEven = false;
            }
        }
        boolean step2 = sorted.length >= 2;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] - sorted[i - 1] != 2) {
                step2 = false;
                break;
            }
        }
        if (step2 && (allOdd || allEven)) {
            return sorted[0] + "-" + sorted[sorted.length - 1] + "周(" + (allOdd ? "单" : "双") + ")";
        }
        return weeksToText(sorted) + "周";
    }

    /**
     * 节次时间表。正方不同版本给的粒度不同：
     *   {"jcmc":"第1,2节","jcsj":"08:00-09:40"}   两节一段
     *   {"jcmc":"0102","jcsj":"08:00-09:40"}     两节一段（四位数字）
     *   {"jcmc":"第3节","jcsj":"10:00-10:45"}     一节一段
     * 这里统一展开成「每个节次对应一条时间」，取不到节次号时按顺序排。
     */
    private static String[] parseNodeTimes(JSONArray sjkList) {
        if (sjkList == null || sjkList.length() == 0) {
            return null;
        }
        List<int[]> ranges = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        int cursor = 1;

        for (int i = 0; i < sjkList.length(); i++) {
            JSONObject item = sjkList.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String raw = firstNonEmpty(item.optString("jcsj"), item.optString("jcmc"));
            Matcher m = CLOCK.matcher(raw);
            if (!m.find()) {
                continue;
            }
            String timeText = m.group(1).replace('：', ':') + "-" + m.group(2).replace('：', ':');

            String label = firstNonEmpty(item.optString("jcmc"), item.optString("jc"));
            int[] nodes = nodeRange(label);
            if (nodes[0] <= 0) {
                nodes = new int[]{cursor, cursor};
            }
            if (nodes[1] < nodes[0]) {
                nodes[1] = nodes[0];
            }
            ranges.add(nodes);
            texts.add(timeText);
            cursor = nodes[1] + 1;
        }

        if (texts.isEmpty()) {
            return null;
        }
        int max = 0;
        for (int[] r : ranges) {
            max = Math.max(max, r[1]);
        }
        String[] out = new String[max];
        for (int i = 0; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            for (int node = r[0]; node <= r[1] && node <= max; node++) {
                if (node >= 1) {
                    out[node - 1] = texts.get(i);
                }
            }
        }
        for (int i = 0; i < max; i++) {
            if (out[i] == null) {
                out[i] = "";
            }
        }
        return out;
    }

    /** 从 "第1,2节" / "0102" / "1-2" 这类标签里解析节次范围 */
    static int[] nodeRange(String label) {
        if (label == null) {
            return new int[]{0, 0};
        }
        String text = label.trim();
        if (text.matches("\\d{4}(\\d{4})*")) {   // 0102 → 第1,2节
            List<Integer> nums = new ArrayList<>();
            for (int i = 0; i + 2 <= text.length(); i += 2) {
                nums.add(Integer.parseInt(text.substring(i, i + 2)));
            }
            if (!nums.isEmpty()) {
                return new int[]{java.util.Collections.min(nums), java.util.Collections.max(nums)};
            }
        }
        return parseNodes(text);
    }

    // ---------------- 查询 ----------------

    public int maxNode() {
        int max = 0;
        for (Course c : courses) {
            max = Math.max(max, c.endNode);
        }
        return max;
    }

    public int[] allWeeks() {
        Set<Integer> set = new LinkedHashSet<>();
        for (Course c : courses) {
            for (int w : c.weeks) {
                set.add(w);
            }
        }
        List<Integer> list = new ArrayList<>(set);
        java.util.Collections.sort(list);
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    public List<Course> coursesAt(int weekday, int node, int week) {
        List<Course> out = new ArrayList<>();
        for (Course c : courses) {
            if (c.weekday == weekday && node >= c.startNode && node <= c.endNode && c.matchesWeek(week)) {
                out.add(c);
            }
        }
        return out;
    }

    public String timeOfNode(int node) {
        if (node >= 1 && node <= nodeTimes.length) {
            return nodeTimes[node - 1];
        }
        return "";
    }

    /**
     * 这门课的真实上课时间，例如 "13:30–15:10"。
     * 取「第一节的开始时间」到「最后一节的结束时间」；取不到返回空串。
     */
    public String courseTimeText(Course course) {
        if (course == null) {
            return "";
        }
        String from = startOfRange(timeOfNode(course.startNode));
        String to = endOfRange(timeOfNode(course.endNode));
        if (from.isEmpty()) {
            return to;
        }
        if (to.isEmpty()) {
            return from;
        }
        return from + "–" + to;
    }

    private static String startOfRange(String range) {
        if (range == null) {
            return "";
        }
        int dash = range.indexOf('-');
        String from = dash > 0 ? range.substring(0, dash) : range;
        return from.trim();
    }

    private static String endOfRange(String range) {
        if (range == null) {
            return "";
        }
        int dash = range.indexOf('-');
        if (dash <= 0 || dash + 1 >= range.length()) {
            return "";
        }
        return range.substring(dash + 1).trim();
    }

    public int courseCount() {
        Set<String> names = new LinkedHashSet<>();
        for (Course c : courses) {
            names.add(c.name);
        }
        return names.size();
    }

    /** 课表里出现的最大周次 */
    public int maxWeek() {
        int max = 0;
        for (int w : allWeeks()) {
            max = Math.max(max, w);
        }
        return max;
    }

    /** 只保留用户自建的课程（旁听课等） */
    public List<Course> customCourses() {
        List<Course> out = new ArrayList<>();
        for (Course c : courses) {
            if (c.custom) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 导入新数据后，把原来的自建课程补回来（按 名称+星期+节次+周次 去重），
     * 这样旁听课不会被一次导入冲掉。
     */
    public void keepCustomFrom(Timetable old) {
        if (old == null) {
            return;
        }
        for (Course c : old.customCourses()) {
            boolean exists = false;
            for (Course mine : courses) {
                if (mine.name.equals(c.name) && mine.weekday == c.weekday
                        && mine.nodeText.equals(c.nodeText) && mine.weekText.equals(c.weekText)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                courses.add(c);
            }
        }
    }

    /** 今天属于第几周；无法判断返回 0 */
    public int weekOfToday() {
        return weekOfDate(termStart, new java.util.Date());
    }

    /**
     * 根据「第 1 周周一」的日期算某天是第几周。
     * 开学前算第 1 周；日期格式不对返回 0。
     */
    public static int weekOfDate(String termStart, java.util.Date date) {
        if (termStart == null || termStart.trim().isEmpty() || date == null) {
            return 0;
        }
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
            format.setLenient(false);
            java.util.Date start = format.parse(termStart.trim().replace('/', '-').replace('.', '-'));

            java.util.Calendar a = java.util.Calendar.getInstance();
            a.setTime(start);
            zeroTime(a);
            moveToWeekStart(a);
            java.util.Calendar b = java.util.Calendar.getInstance();
            b.setTime(date);
            zeroTime(b);
            moveToWeekStart(b);

            long days = Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
            if (days < 0) {
                return 1;   // 还没开学，按第 1 周显示
            }
            return (int) Math.round(days / 7.0) + 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 把日历挪到所在那一周的周一（周一为一周第一天） */
    private static void moveToWeekStart(java.util.Calendar calendar) {
        int dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK);   // 周日=1，周一=2 …
        int sinceMonday = (dayOfWeek + 5) % 7;                          // 周一=0 … 周日=6
        calendar.add(java.util.Calendar.DAY_OF_MONTH, -sinceMonday);
    }

    /**
     * 从 from（yyyy-MM-dd）到 to 相差几个整周（可以是负数）。
     *
     * **按自然周计算（周一是每周第一天）**：两个日期分别对齐到所在那一周的周一再相减，
     * 所以"周日 → 第二天周一"就算跨了一周（返回 1），而"周一 → 周日"仍是同一周（返回 0）。
     * （早期版本用原始天数差 ÷ 7，导致周日设的锚点要等到下周日才进位，
     * 于是周一打开还停在上周 —— 这个 bug 已修复。）
     *
     * 用途：用户告诉 App「今天是第 N 周」之后，以后按日期自动往后推，不必知道开学日期。
     * 日期无效返回 {@link Integer#MIN_VALUE}。
     */
    public static int weeksBetween(String from, java.util.Date to) {
        if (from == null || from.trim().isEmpty() || to == null) {
            return Integer.MIN_VALUE;
        }
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
            format.setLenient(false);
            java.util.Date start = format.parse(from.trim().replace('/', '-').replace('.', '-'));

            java.util.Calendar a = java.util.Calendar.getInstance();
            a.setTime(start);
            zeroTime(a);
            moveToWeekStart(a);
            java.util.Calendar b = java.util.Calendar.getInstance();
            b.setTime(to);
            zeroTime(b);
            moveToWeekStart(b);

            long days = Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
            return (int) Math.round(days / 7.0);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    private static void zeroTime(java.util.Calendar calendar) {
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
    }

    /** 第 week 周的日期范围文本，如 "09-01 ~ 09-07"；termStart 为空时返回空串 */
    public String weekDateRange(int week) {
        if (termStart == null || termStart.trim().isEmpty() || week < 1) {
            return "";
        }
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
            java.util.Date start = format.parse(termStart.trim().replace('/', '-').replace('.', '-'));
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.setTime(start);
            calendar.add(java.util.Calendar.DAY_OF_YEAR, (week - 1) * 7);
            java.text.SimpleDateFormat show = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA);
            String from = show.format(calendar.getTime());
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 6);
            return from + " ~ " + show.format(calendar.getTime());
        } catch (Exception e) {
            return "";
        }
    }

    public String termText() {
        String termName;
        if ("3".equals(xqm)) {
            termName = "第一学期";
        } else if ("12".equals(xqm)) {
            termName = "第二学期";
        } else if ("16".equals(xqm)) {
            termName = "第三学期";
        } else {
            termName = "第 " + xqm + " 学期";
        }
        try {
            int year = Integer.parseInt(xnm);
            return year + "-" + (year + 1) + " 学年 " + termName;
        } catch (Exception e) {
            return termName;
        }
    }

    // ---------------- 缓存 ----------------

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("xnm", xnm);
            root.put("xqm", xqm);
            root.put("student", student);
            root.put("fetchedAt", fetchedAt);
            root.put("termStart", termStart);
            root.put("termStartGuessed", termStartGuessed);
            // 标记节次时间是不是服务器给的：不是的话，升级 App 后要跟着新的默认作息走
            root.put("nodeTimesFromServer", nodeTimesFromServer);
            JSONArray times = new JSONArray();
            for (String s : nodeTimes) {
                times.put(s);
            }
            root.put("nodeTimes", times);
            JSONArray arr = new JSONArray();
            for (Course c : courses) {
                arr.put(c.toJson());
            }
            root.put("courses", arr);
            return root.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    public static Timetable fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            Timetable t = new Timetable();
            t.xnm = root.optString("xnm");
            t.xqm = root.optString("xqm", "3");
            t.student = root.optString("student");
            t.fetchedAt = root.optString("fetchedAt");
            t.termStart = root.optString("termStart");
            t.termStartGuessed = root.optBoolean("termStartGuessed", false);
            // 只有"服务器给的时间"才从缓存里读回来；
            // 否则（老缓存 / PDF 导入）一律用当前版本的内置作息表，
            // 这样改了默认作息后，老用户升级就能自动纠正，不用重新导入。
            t.nodeTimesFromServer = root.optBoolean("nodeTimesFromServer", false);
            JSONArray times = root.optJSONArray("nodeTimes");
            if (t.nodeTimesFromServer && times != null && times.length() > 0) {
                String[] arr = new String[times.length()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = times.optString(i);
                }
                t.nodeTimes = arr;
            }
            JSONArray arr = root.optJSONArray("courses");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        t.courses.add(Course.fromJson(o));
                    }
                }
            }
            return t;
        } catch (JSONException e) {
            return null;
        }
    }
}
