package com.bua.coursehelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 不需要手机/模拟器的冒烟测试：在电脑上真实地把界面创建、测量、绘制一遍，
 * 检查布局能不能解析、控件在不在、课表能不能画出来、能不能按今天定位本周。
 * 跑法： gradlew testDebugUnitTest
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTest {

    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

    /** 让"今天"正好是第 3 周：开学日期 = 今天 - 14 天 */
    private static String termStartForWeek3() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -14);
        return DAY.format(calendar.getTime());
    }

    /** 一份模拟的教务系统缓存数据（和真实 kbList 结构一致） */
    private static String cacheJson(String termStart) {
        return "{"
                + "\"xnm\":\"2026\",\"xqm\":\"3\",\"student\":\"张同学\",\"fetchedAt\":\"2026-10-16 15:00\","
                + "\"termStart\":\"" + termStart + "\","
                + "\"nodeTimes\":[\"08:00-08:45\",\"08:55-09:40\",\"10:00-10:45\",\"10:55-11:40\","
                + "\"13:30-14:15\",\"14:25-15:10\",\"15:20-16:05\",\"16:15-17:00\"],"
                + "\"courses\":["
                + "{\"name\":\"概率论与数理统计\",\"teacher\":\"张老师\",\"place\":\"教A-207\",\"weekday\":3,"
                + "\"startNode\":3,\"endNode\":4,\"nodeText\":\"3-4\",\"weekText\":\"1-16周\",\"kch\":\"MATH1001\","
                + "\"weeks\":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16]},"
                + "{\"name\":\"大学物理\",\"teacher\":\"惠雁冰\",\"place\":\"A-207\",\"weekday\":2,"
                + "\"startNode\":3,\"endNode\":4,\"nodeText\":\"3-4\",\"weekText\":\"1-16周(单)\",\"kch\":\"PHYS1001\","
                + "\"weeks\":[1,3,5,7,9,11,13,15]},"
                + "{\"name\":\"体育\",\"teacher\":\"李老师\",\"place\":\"体育馆\",\"weekday\":5,"
                + "\"startNode\":5,\"endNode\":6,\"nodeText\":\"5-6\",\"weekText\":\"1-5,7-9周\",\"kch\":\"PE1001\","
                + "\"weeks\":[1,2,3,4,5,7,8,9]}"
                + "]}";
    }

    private static void putCache(String json) {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("course_helper", Context.MODE_PRIVATE)
                .edit().putString("cache", json).commit();
    }

    @Test
    public void 空缓存也能正常打开界面() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNotNull("Activity 应该创建成功", activity);

        // 关键控件都在
        assertNotNull(activity.findViewById(R.id.gridView));
        assertNotNull(activity.findViewById(R.id.weekContainer));
        assertNotNull(activity.findViewById(R.id.weekScroll));
        assertNotNull(activity.findViewById(R.id.btnImport));
        assertNotNull(activity.findViewById(R.id.btnImportFile));
        assertNotNull(activity.findViewById(R.id.btnAddCourse));
        assertNotNull(activity.findViewById(R.id.themeColors));
        assertNotNull(activity.findViewById(R.id.tvThemeName));
        assertNotNull(activity.findViewById(R.id.etCurrentWeek));
        assertNotNull(activity.findViewById(R.id.btnSetCurrentWeek));
        assertNotNull(activity.findViewById(R.id.btnCourseList));
        assertNotNull(activity.findViewById(R.id.btnToggleConfig));
        assertNotNull(activity.findViewById(R.id.etUsername));
        assertNotNull(activity.findViewById(R.id.etCurrentWeek));

        // 没有缓存时设置卡片应该是展开的
        assertEquals(View.VISIBLE, activity.findViewById(R.id.configCard).getVisibility());

        TextView status = activity.findViewById(R.id.tvStatus);
        assertTrue("没缓存时应提示去导入", status.getText().toString().contains("导入"));
    }

    @Test
    public void 有缓存时一屏显示完整课表并自动定位本周() {
        putCache(cacheJson(termStartForWeek3()));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        // 导入/加载缓存后设置卡片自动收起，课表占满
        assertEquals("设置卡片应自动收起", View.GONE,
                activity.findViewById(R.id.configCard).getVisibility());

        // 周次按钮：全部 + 1..16 周
        LinearLayout weeks = activity.findViewById(R.id.weekContainer);
        assertEquals("周次按钮数", 1 + 16, weeks.getChildCount());

        // 概要里应该已经是第 3 周（今天 = 开学 + 14 天）
        TextView summary = activity.findViewById(R.id.tvSummary);
        String text = summary.getText().toString();
        assertTrue("概要应自动定位到第 3 周: " + text, text.contains("第 3 周"));
        assertTrue("第3周三门课都上: " + text, text.contains("3 条"));
        assertTrue("概要应带学生名: " + text, text.contains("张同学"));
    }

    @Test
    public void 课表能铺满屏幕并且不崩溃地画出来() {
        putCache(cacheJson(termStartForWeek3()));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        TimetableGridView grid = activity.findViewById(R.id.gridView);
        assertNotNull(grid);

        int width = 1080;
        int height = 1500;
        grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, width, height);

        // 宽度铺满、不给横向滚动留余地
        assertEquals("课表宽度应等于可用宽度", width, grid.getMeasuredWidth());
        assertTrue("课表高度不应超过可用高度", grid.getMeasuredHeight() <= height);

        // 真的画一遍，确认绘制代码不抛异常
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        grid.draw(new Canvas(bitmap));
        assertNotNull(bitmap);
    }

    @Test
    public void 按日期算周次() throws Exception {
        String start = "2026-09-07";      // 周一
        assertEquals(1, Timetable.weekOfDate(start, DAY.parse("2026-09-07")));
        assertEquals(1, Timetable.weekOfDate(start, DAY.parse("2026-09-13")));      // 周日仍第 1 周
        assertEquals(2, Timetable.weekOfDate(start, DAY.parse("2026-09-14")));
        assertEquals(3, Timetable.weekOfDate(start, DAY.parse("2026-09-21")));
        assertEquals(4, Timetable.weekOfDate(start, DAY.parse("2026-09-28")));
        assertEquals(1, Timetable.weekOfDate(start, DAY.parse("2026-08-20")));      // 开学前按第 1 周
        assertEquals(0, Timetable.weekOfDate("", DAY.parse("2026-09-28")));         // 没日期无法判断
        assertEquals(0, Timetable.weekOfDate("乱写的", DAY.parse("2026-09-28")));
    }

    @Test
    public void 接口里带学期日期时自动推断开学日并定位本周() throws Exception {
        // 最近的周一再往前两周 → 今天是第 3 周
        Calendar monday = Calendar.getInstance();
        while (monday.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            monday.add(Calendar.DAY_OF_YEAR, -1);
        }
        monday.add(Calendar.DAY_OF_YEAR, -14);
        String start = DAY.format(monday.getTime());

        String json = "{\"xsxx\":{\"XM\":\"张同学\",\"XQ_KSRQ\":\"" + start + "\",\"XQ_JSRQ\":\"2027-01-15\"},"
                + "\"kbList\":[{\"kcmc\":\"概率论\",\"xm\":\"张老师\",\"cdmc\":\"A-207\","
                + "\"xqj\":\"3\",\"jcs\":\"3-4\",\"zcd\":\"1-16周\"}]}";

        Timetable timetable = Timetable.parse(json, "2026", "3");
        assertEquals("应从接口日期里找出开学第一周周一", start, timetable.termStart);
        assertTrue("应标记为推断值", timetable.termStartGuessed);
        assertEquals("今天应该是第 3 周", 3, timetable.weekOfToday());
    }

    @Test
    public void 周次锚点按天数自动推进() throws Exception {
        Date today = DAY.parse("2026-10-16");                            // 周五，属于 10-12 那一周
        assertEquals(0, Timetable.weeksBetween("2026-10-16", today));
        assertEquals(1, Timetable.weeksBetween("2026-10-09", today));
        assertEquals(2, Timetable.weeksBetween("2026-10-02", today));
        assertEquals(-1, Timetable.weeksBetween("2026-10-23", today));   // 锚点在未来
        assertEquals(1, Timetable.weeksBetween("2026-10-08", today));    // 八天算一周
        assertEquals(0, Timetable.weeksBetween("2026-10-12", today));    // 同一周的周一到周五
        assertEquals(1, Timetable.weeksBetween("2026-10-10", today));    // 上周六 → 已跨过周一
        assertEquals(Integer.MIN_VALUE, Timetable.weeksBetween("", today));
    }

    /**
     * v3.3 修复的 bug：锚点设在周日、第二天是周一，必须进到下一周。
     * （早期版本按"满 7 天"进位，周一打开还停在上周，看到的是上周一的课。）
     */
    @Test
    public void 周日锚点第二天周一要进下一周() throws Exception {
        Date monday = DAY.parse("2026-10-12");
        Date sundayBefore = DAY.parse("2026-10-11");     // 10-12 前一天（属于上一周）
        Date sundayAfter = DAY.parse("2026-10-18");      // 10-12 那一周的周日

        assertEquals("周日→周一要算跨周", 1, Timetable.weeksBetween("2026-10-11", monday));
        assertEquals("周一到同一周的周日仍是同一周", 0, Timetable.weeksBetween("2026-10-12", sundayAfter));
        assertEquals("同周内怎么比都是 0", 0, Timetable.weeksBetween("2026-10-14", sundayAfter));
        assertEquals("往回调一周是 -1", -1, Timetable.weeksBetween("2026-10-12", sundayBefore));
        assertEquals("第 6 周周一 → 第 7 周周一 是 1 周",
                1, Timetable.weeksBetween("2026-10-12", DAY.parse("2026-10-19")));

        // 开学日路径也要一致：第 1 周周一 09-07，09-13 周日仍是第 1 周，09-14 周一进第 2 周
        assertEquals(1, Timetable.weekOfDate("2026-09-07", DAY.parse("2026-09-13")));
        assertEquals(2, Timetable.weekOfDate("2026-09-07", DAY.parse("2026-09-14")));
    }

    /** 老版本导入的缓存里没有开学日期，靠"今天是第 N 周"这个锚点也要能定位 */
    @Test
    public void 没有开学日期时按锚点定位本周() {
        putCache(cacheJson(""));
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("course_helper", Context.MODE_PRIVATE)
                .edit()
                .putInt("week_anchor_week", 5)
                .putString("week_anchor_date", DAY.format(new Date()))
                .commit();

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView summary = activity.findViewById(R.id.tvSummary);
        assertTrue("应按锚点定位到第 5 周: " + summary.getText(),
                summary.getText().toString().contains("第 5 周"));
    }

    @Test
    public void 锚点会随着日期自动往后推() {
        putCache(cacheJson(""));
        Calendar weekAgo = Calendar.getInstance();
        weekAgo.add(Calendar.DAY_OF_YEAR, -7);
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("course_helper", Context.MODE_PRIVATE)
                .edit()
                .putInt("week_anchor_week", 5)
                .putString("week_anchor_date", DAY.format(weekAgo.getTime()))
                .commit();

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView summary = activity.findViewById(R.id.tvSummary);
        assertTrue("一周前校准第 5 周，今天应是第 6 周: " + summary.getText(),
                summary.getText().toString().contains("第 6 周"));
    }

    /** v1.6：直接在界面上填「当前是第几周」，点「设为本周」即可定位 */
    @Test
    public void 填入当前周次后按周次定位本周() {
        putCache(cacheJson(""));      // 缓存里没有开学日期
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        TextView summary = activity.findViewById(R.id.tvSummary);
        assertTrue("还没填周次时应显示全部周: " + summary.getText(),
                summary.getText().toString().contains("全部周"));

        EditText input = activity.findViewById(R.id.etCurrentWeek);
        input.setText("4");
        activity.findViewById(R.id.btnSetCurrentWeek).performClick();

        summary = activity.findViewById(R.id.tvSummary);
        assertTrue("填了第 4 周后应显示第 4 周: " + summary.getText(),
                summary.getText().toString().contains("第 4 周"));
        assertEquals("输入框应与显示一致", "4", input.getText().toString());
        assertEquals("定位成功后设置应自动收起", View.GONE,
                activity.findViewById(R.id.configCard).getVisibility());
    }

    /** v1.7：自建课程（旁听课）能加上，而且不会被一次导入冲掉 */
    @Test
    public void 自建课程能添加且导入不覆盖() throws Exception {
        putCache(cacheJson("2026-09-07"));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        Course custom = new Course();
        custom.name = "旁听 · 数据结构";
        custom.teacher = "王老师";
        custom.place = "信息楼 302";
        custom.weekday = 4;
        custom.startNode = 7;
        custom.endNode = 8;
        custom.nodeText = "7-8";
        custom.weekText = "1-16";
        custom.weeks = Timetable.parseWeeks("1-16");

        Timetable current = CourseStore.load(activity);
        CourseStore.addCustomCourse(activity, current, custom);

        Timetable saved = CourseStore.load(activity);
        assertEquals("应该多出一条课", 4, saved.courses.size());
        assertEquals("应标记为自建", 1, saved.customCourses().size());
        assertTrue("自建课应能被查到", saved.coursesAt(4, 7, 3).size() == 1);

        // 模拟一次"登录并导入课表"：新数据 + 保留自建课程
        Timetable imported = Timetable.fromJson(cacheJson("2026-09-07"));
        assertEquals(3, imported.courses.size());
        imported.keepCustomFrom(saved);
        assertEquals("导入后自建课程不能被冲掉", 4, imported.courses.size());
        assertEquals(1, imported.customCourses().size());
    }

    /** v1.7：主题色可切换，重建界面后生效 */
    @Test
    public void 主题色可切换并生效() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals("默认是经典红", AppTheme.COLORS[0], AppTheme.color(activity));

        AppTheme.setIndex(activity, 2);
        assertEquals("切换后应记住森林绿", AppTheme.COLORS[2], AppTheme.color(activity));

        MainActivity again = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals("状态栏颜色应跟随主题色",
                AppTheme.COLORS[2], again.getWindow().getStatusBarColor());
    }

    /** v1.7：两个桌面小组件的布局与数据都能正常渲染 */
    @Test
    public void 两个桌面小组件能正常渲染() {
        putCache(cacheJson(termStartForWeek3()));
        Context context = RuntimeEnvironment.getApplication();

        RemoteViews today = TodayWidgetProvider.build(context);
        assertNotNull("今日课表小组件应能构建", today);
        assertNotNull("今日课表小组件应能真正渲染出来",
                today.apply(context, new FrameLayout(context)));

        RemoteViews twoDays = TwoDaysWidgetProvider.build(context);
        assertNotNull("今日+明日小组件应能构建", twoDays);
        assertNotNull("今日+明日小组件应能真正渲染出来",
                twoDays.apply(context, new FrameLayout(context)));
    }

    /** v1.8：教务系统导出的课表 PDF 能解析成课表 */
    @Test
    public void 课表PDF能解析成课表() throws Exception {
        Timetable timetable = TimetablePdfParser.parse(buildPdf(), "2026", "3");

        assertEquals("张三", timetable.student);
        assertEquals("2026", timetable.xnm);
        assertEquals("3", timetable.xqm);
        assertEquals("两门课", 2, timetable.courses.size());

        Course english = timetable.courses.get(0);
        assertEquals("大学英语I", english.name);
        assertEquals(1, english.weekday);              // 星期一
        assertEquals("1-2", english.nodeText);
        assertEquals("邢玉红", english.teacher);
        assertEquals("教A-301", english.place);
        assertEquals("3周,7-17周(单)", english.weekText);
        assertEquals("单周过滤后 7 周", 7, english.weeks.length);

        Course pickle = timetable.courses.get(1);
        assertEquals("匹克球", pickle.name);
        assertEquals(3, pickle.weekday);               // 星期三
        assertEquals("3-4", pickle.nodeText);
        assertEquals("3-5周(单),6-8周(双),9-18周", pickle.weekText);
        assertEquals("单双周混合应解析出 14 周", 14, pickle.weeks.length);
    }

    /** 单双周混合写法不能互相抵消（这个 bug 也影响在线导入） */
    @Test
    public void 单双周混合写法能正确解析() {
        int[] weeks = Timetable.parseWeeks("3-5周(单),6-8周(双),9-18周");
        assertEquals(14, weeks.length);
        assertEquals("3,5-6,8-18", Timetable.weeksToText(weeks));
        assertEquals("1-16周(单) 仍是单周", 8, Timetable.parseWeeks("1-16周(单)").length);
    }

    /** 自检发现①：只写"单周/双周"没写周次数字时，以前会解析成空 → 这门课变成每周都上 */
    @Test
    public void 单周双周没写数字也能解析() {
        int[] odd = Timetable.parseWeeks("单周");
        assertTrue("单周应解析出奇数周，实际 " + odd.length, odd.length >= 15);
        for (int w : odd) {
            assertEquals("应全是奇数周", 1, w % 2);
        }
        int[] even = Timetable.parseWeeks("双周");
        assertTrue("双周应解析出偶数周", even.length >= 15);
        for (int w : even) {
            assertEquals("应全是偶数周", 0, w % 2);
        }
        // 同一段里同时出现"单""双"（单双周=每周交替）→ 不做奇偶过滤
        assertEquals("1-16周(单双周) 应是 16 周", 16, Timetable.parseWeeks("1-16周(单双周)").length);
        // 完全没写周次（例如自建课程）= 每周都上
        assertEquals(0, Timetable.parseWeeks("").length);
    }

    /** 自检发现②：判断不出当前周次时，小组件以前会把这天"所有周"的课都列出来 */
    @Test
    public void 判断不出周次时小组件不列课() {
        putCache(cacheJson(""));                 // 缓存里没有开学日期 → 周次未知
        Context context = RuntimeEnvironment.getApplication();
        Timetable timetable = CourseStore.load(context);
        assertTrue("课表本身有课", timetable.courses.size() > 0);

        assertEquals("周次未知时不应返回任何课（否则会显示别的周的课）", 0,
                CourseStore.coursesOn(timetable, 3, 0).size());
        assertEquals("知道周次时才按周次过滤", 1,
                CourseStore.coursesOn(timetable, 3, 2).size());
        assertEquals("单周课在第 2 周不该出现", 0,
                CourseStore.coursesOn(timetable, 2, 2).size());
        assertEquals("单周课在第 3 周应该出现", 1,
                CourseStore.coursesOn(timetable, 2, 3).size());
    }

    /** 用代码合成一份和教务系统同结构的课表 PDF（FlateDecode + UniGB-UCS2-H 文字 + 表格矩形） */
    private static byte[] buildPdf() throws Exception {
        StringBuilder content = new StringBuilder();
        appendText(content, 361, 545, 24, "张三课表");
        appendText(content, 21, 553, 8, "2026-2027学年第1学期");
        appendText(content, 696, 553, 8, " 学号：202500000000");

        float[] headerX = {133f, 236.85f, 340.69f, 444.5f, 548.4f, 652.2f, 756.1f};
        String[] headerText = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        for (int i = 0; i < headerX.length; i++) {
            appendText(content, headerX[i], 521, 12, headerText[i]);
        }

        String[] english = {
                "大学英语I★", "(1-2节)3周,7-17周(单)/场地:教A-",
                "301/教师:邢玉红/教学班组", "成:2026/选课备注:/学分:4.0",
        };
        float y = 505.5f;
        for (String line : english) {
            appendText(content, 104.1f, y, 10, line);
            y -= 12f;
        }

        String[] pickle = {
                "匹克球★", "(3-4节)3-5周(单),6-8周(双",
                "),9-18周/场地:操场/教师:王", "卓识/教学班组成:2026/选课", "备注:/学分:1.0",
        };
        y = 367f;
        for (String line : pickle) {
            appendText(content, 311.8f, y, 10, line);
            y -= 12f;
        }

        content.append("99.08 504 103.85 15 re S\n");
        content.append("306.77 365.5 103.85 60.5 re S\n");
        return wrapPdf(content.toString());
    }

    private static void appendText(StringBuilder out, float x, float y, float size, String text) {
        out.append("BT\n1 0 0 1 ").append(x).append(' ').append(y).append(" Tm\n/F1 ")
                .append(size).append(" Tf\n(").append(escapePdf(text)).append(")Tj\nET\n");
    }

    /** 按 UniGB-UCS2-H 的写法：文字用 UTF-16BE 字节，PDF 字符串里转义 \ ( ) */
    private static String escapePdf(String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
        StringBuilder out = new StringBuilder();
        for (byte raw : bytes) {
            int value = raw & 0xFF;
            if (value == '(' || value == ')' || value == '\\') {
                out.append('\\');
            }
            out.append((char) value);
        }
        return out.toString();
    }

    private static byte[] wrapPdf(String content) throws Exception {
        byte[] raw = content.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(raw);
        deflater.finish();
        java.io.ByteArrayOutputStream deflated = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        while (!deflater.finished()) {
            int n = deflater.deflate(chunk);
            deflated.write(chunk, 0, n);
        }
        deflater.end();

        byte[] compressed = deflated.toByteArray();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write("%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.write(("1 0 obj\n<</Length " + compressed.length + "/Filter/FlateDecode>>\nstream\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        out.write(compressed);
        out.write("\nendstream\nendobj\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    /** v2.0：课表上左右滑动切换上一周 / 下一周 */
    @Test
    public void 课表左右滑动可切换周次() {
        putCache(cacheJson(termStartForWeek3()));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        TimetableGridView grid = activity.findViewById(R.id.gridView);
        grid.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, 1080, 1400);

        TextView summary = activity.findViewById(R.id.tvSummary);
        assertTrue("起始应是第 3 周: " + summary.getText(), summary.getText().toString().contains("第 3 周"));

        swipe(grid, 900, 700, 150, 705);      // 向左滑 → 下一周
        assertTrue("左滑后应是第 4 周: " + summary.getText(),
                summary.getText().toString().contains("第 4 周"));

        swipe(grid, 150, 700, 900, 705);      // 向右滑 → 上一周
        assertTrue("右滑后应回到第 3 周: " + summary.getText(),
                summary.getText().toString().contains("第 3 周"));
    }

    /** v2.1：切周过渡动画——切换立即生效，动画中反复绘制也不崩 */
    @Test
    public void 切周过渡动画不崩且落点正确() {
        putCache(cacheJson(termStartForWeek3()));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TimetableGridView grid = activity.findViewById(R.id.gridView);
        grid.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, 1080, 1400);

        Bitmap bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888);

        grid.setWeekAnimated(4, 1);                 // 下一周
        assertEquals("切周应立即可见", 4, grid.getWeek());
        grid.draw(new Canvas(bitmap));              // 动画中绘制

        grid.setWeekAnimated(5, 1);                 // 连续切也不该崩
        grid.draw(new Canvas(bitmap));
        assertEquals(5, grid.getWeek());

        grid.setWeekAnimated(4, -1);                // 回上一周
        grid.draw(new Canvas(bitmap));
        assertEquals(4, grid.getWeek());

        // 通过界面滑一次，走的是"Activity 当前周 + 带过渡动画"的完整路径
        // （前面直接调 grid.setWeekAnimated 不会改 Activity 的当前周，所以这里从第 3 周滑到第 4 周）
        swipe(grid, 900, 700, 150, 705);
        grid.draw(new Canvas(bitmap));
        TextView summary = activity.findViewById(R.id.tvSummary);
        assertTrue("滑动后应到第 4 周: " + summary.getText(),
                summary.getText().toString().contains("第 4 周"));
    }

    /** 边界：第 1 周向右滑不应越界 */    @Test
    public void 第一周右滑不越界() {
        putCache(cacheJson(termStartForWeek3()));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TimetableGridView grid = activity.findViewById(R.id.gridView);
        grid.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, 1080, 1400);

        LinearLayout weeks = activity.findViewById(R.id.weekContainer);
        for (int i = 0; i < weeks.getChildCount(); i++) {
            TextView chip = (TextView) weeks.getChildAt(i);
            if ("第1周".equals(chip.getText().toString())) {
                chip.performClick();
                break;
            }
        }
        TextView summary = activity.findViewById(R.id.tvSummary);
        assertTrue("先切到第 1 周: " + summary.getText(), summary.getText().toString().contains("第 1 周"));

        swipe(grid, 150, 700, 980, 705);      // 向右滑 → 已经是第一周，应停住
        assertTrue("不该越界到第 0 周: " + summary.getText(),
                summary.getText().toString().contains("第 1 周"));
    }

    /** v2.0：设置展开时，从屏幕任意位置向上划一下即可收起 */
    @Test
    public void 上划屏幕可收起设置() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals("空缓存时设置是展开的", View.VISIBLE,
                activity.findViewById(R.id.configCard).getVisibility());

        dispatchSwipe(activity, 500, 1400, 505, 200);     // 向上划
        assertEquals("上划后设置应收起", View.GONE,
                activity.findViewById(R.id.configCard).getVisibility());

        // 竖直方向小幅移动不应被当成收起手势
        MainActivity another = Robolectric.buildActivity(MainActivity.class).setup().get();
        dispatchSwipe(another, 500, 800, 505, 780);
        assertEquals("小幅移动不该收起设置", View.VISIBLE,
                another.findViewById(R.id.configCard).getVisibility());
    }

    /** v2.2：设置收起时，在课表页向下划即可展开设置（与上划收起对称） */
    @Test
    public void 下滑屏幕可展开设置() {
        putCache(cacheJson(termStartForWeek3()));      // 有缓存 → 进入时设置是收起的
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals("加载缓存后设置应收起", View.GONE,
                activity.findViewById(R.id.configCard).getVisibility());

        dispatchSwipe(activity, 500, 300, 505, 1400);  // 向下划
        assertEquals("下滑后设置应展开", View.VISIBLE,
                activity.findViewById(R.id.configCard).getVisibility());

        dispatchSwipe(activity, 500, 1400, 505, 300);  // 再向上划
        assertEquals("上划后又应收起", View.GONE,
                activity.findViewById(R.id.configCard).getVisibility());

        // 横向滑动不应该被当成展开手势
        MainActivity another = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(View.GONE, another.findViewById(R.id.configCard).getVisibility());
        dispatchSwipe(another, 150, 900, 900, 905);
        assertEquals("横向滑动不该展开设置", View.GONE,
                another.findViewById(R.id.configCard).getVisibility());
    }

    private static void swipe(View view, float fromX, float fromY, float toX, float toY) {
        view.dispatchTouchEvent(motion(fromX, fromY, MotionEvent.ACTION_DOWN, 0));
        view.dispatchTouchEvent(motion((fromX + toX) / 2, (fromY + toY) / 2, MotionEvent.ACTION_MOVE, 20));
        view.dispatchTouchEvent(motion(toX, toY, MotionEvent.ACTION_UP, 40));
    }

    private static void dispatchSwipe(MainActivity activity,
                                      float fromX, float fromY, float toX, float toY) {
        activity.dispatchTouchEvent(motion(fromX, fromY, MotionEvent.ACTION_DOWN, 0));
        activity.dispatchTouchEvent(motion((fromX + toX) / 2, (fromY + toY) / 2, MotionEvent.ACTION_MOVE, 20));
        activity.dispatchTouchEvent(motion(toX, toY, MotionEvent.ACTION_UP, 40));
    }

    private static MotionEvent motion(float x, float y, int action, long offset) {
        long now = android.os.SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now + offset, action, x, y, 0);
    }

    /** v3.0 校外导入：学校 SSL VPN 的地址改写规则（点换横线 + -s.vpn.bua.edu.cn:8118） */
    @Test
    public void 校外网关地址改写正确() {
        assertEquals("http://jwglxt-bua-edu-cn-s.vpn.bua.edu.cn:8118/jwglxt",
                VpnGateway.toGatewayUrl("https://jwglxt.bua.edu.cn/jwglxt"));
        assertEquals("http://jwglxt-bua-edu-cn-s.vpn.bua.edu.cn:8118/jwglxt/xtgl/login_slogin.html",
                VpnGateway.toGatewayUrl("https://jwglxt.bua.edu.cn/jwglxt/xtgl/login_slogin.html"));
        assertEquals("http://jwc-bua-edu-cn-s.vpn.bua.edu.cn:8118/",
                VpnGateway.toGatewayUrl("http://jwc.bua.edu.cn/"));

        // 已经是网关地址就不重复改写
        String gateway = "http://jwglxt-bua-edu-cn-s.vpn.bua.edu.cn:8118/jwglxt";
        assertEquals(gateway, VpnGateway.toGatewayUrl(gateway));

        // 不是本校域名、或地址不合法 → 换不出来
        assertEquals("", VpnGateway.toGatewayUrl("https://example.com/jwglxt"));
        assertEquals("", VpnGateway.toGatewayUrl(""));

        // 判断"是否已经穿过网关"：VPN 门户本身不算，落到网关域名上才算
        assertTrue(VpnGateway.isThroughGateway(gateway));
        assertTrue(VpnGateway.isThroughGateway(
                "http://jwc-bua-edu-cn-s.vpn.bua.edu.cn:8118/index"));
        assertFalse("VPN 门户登录页不算已登录",
                VpnGateway.isThroughGateway("https://vpn.bua.edu.cn/?redirect_uri=http%3A%2F%2Fjwglxt-bua-edu-cn-s.vpn.bua.edu.cn%3A8118%2Fjwglxt"));
        assertFalse(VpnGateway.isThroughGateway("https://jwglxt.bua.edu.cn/jwglxt"));
        assertFalse(VpnGateway.isThroughGateway(null));

        // 门户登录地址带上了目标地址
        assertTrue(VpnGateway.portalLoginUrl("https://jwglxt.bua.edu.cn/jwglxt")
                .startsWith("https://vpn.bua.edu.cn/?redirect_uri="));
    }

    /** v3.1 校外导入：页面内取课表的脚本要带上接口、学年学期、同源凭据，并能识别 403 */
    @Test
    public void 页面内取课表的脚本正确() {
        String script = VpnImportActivity.buildFetchScript("2026", "3");
        assertTrue("要请求课表接口: " + script,
                script.contains("/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"));
        assertTrue("接口地址要从当前页面推导（WebVPN 与网关两种入口通用）",
                script.contains("location.pathname") && script.contains("indexOf('/jwglxt')"));
        assertTrue("要带上学年", script.contains("append('xnm','2026')"));
        assertTrue("要带上学期", script.contains("append('xqm','3')"));
        assertTrue("要带同源凭据（会话靠浏览器自己带）", script.contains("credentials:'include'"));
        assertTrue("要回调 Java", script.contains("Android.onResult"));
        assertTrue("403 要单独识别出来", script.contains("Forbidden"));
        assertTrue("取不到数据要报错而不是静默", script.contains("Android.onError"));
    }

    /** v3.1：只有进到教务系统页面（而不是 VPN 门户/登录页）才自动取课表；两种入口都认 */
    @Test
    public void 校外页面判定正确() {
        // SSL VPN 网关入口
        assertTrue(VpnGateway.isTimetablePage(
                "http://jwglxt-bua-edu-cn-s.vpn.bua.edu.cn:8118/jwglxt/xtgl/index_initMenu.html"));
        // 网页版 WebVPN 入口（路径里带 /https/xxx 前缀）
        assertTrue(VpnGateway.isTimetablePage(
                "https://webvpn.bua.edu.cn/https/77726476706e6973746865626573742/jwglxt/xtgl/index_initMenu.html"));

        assertFalse("WebVPN 门户本身不算", VpnGateway.isTimetablePage("https://webvpn.bua.edu.cn/"));
        assertFalse("WebVPN 登录页不算",
                VpnGateway.isTimetablePage("https://webvpn.bua.edu.cn/users/sign_in"));
        assertFalse("VPN 门户不算", VpnGateway.isTimetablePage("https://vpn.bua.edu.cn/"));
        assertFalse("网关登录页不算", VpnGateway.isTimetablePage(
                "http://jwglxt-bua-edu-cn-s.vpn.bua.edu.cn:8118/users/sign_in"));
        assertFalse("校内地址（没走网关）不算",
                VpnGateway.isTimetablePage("https://jwglxt.bua.edu.cn/jwglxt/index.html"));
    }

    /** v3.0/v3.1 校外导入：点按钮会打开校外导入页，并把地址、学年学期带过去 */
    @Test
    public void 点校外导入会打开校外导入页() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        activity.findViewById(R.id.btnVpnImport).performClick();

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("应该启动了校外导入页", started);
        assertEquals(VpnImportActivity.class.getName(), started.getComponent().getClassName());
        assertNotNull("要把教务系统地址带过去",
                started.getStringExtra(VpnImportActivity.EXTRA_BASE_URL));
        assertNotNull("要把学年带过去", started.getStringExtra(VpnImportActivity.EXTRA_XNM));
        assertNotNull("要把学期带过去", started.getStringExtra(VpnImportActivity.EXTRA_XQM));
    }

    /** v3.1 校外导入：用户取消时要给提示，不能卡在"正在导入" */
    @Test
    public void 取消校外导入会提示() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        activity.findViewById(R.id.btnVpnImport).performClick();

        Intent request = new Intent(activity, VpnImportActivity.class);
        request.putExtra(VpnImportActivity.EXTRA_BASE_URL, JwglxtClient.DEFAULT_BASE_URL);
        shadowOf(activity).receiveResult(request, android.app.Activity.RESULT_CANCELED, null);

        TextView status = activity.findViewById(R.id.tvStatus);
        assertEquals("已取消校外导入", status.getText().toString());
    }

    /** v3.4/v3.5：上课时间 = 第一节开始 → 最后一节结束（按学校作息：下午 13:30、晚上 18:30 起） */
    @Test
    public void 小组件能算出上课时间() {
        Timetable timetable = new Timetable();
        Course course = new Course();
        course.name = "大学生心理健康教育";
        course.weekday = 2;

        course.startNode = 5;
        course.endNode = 6;
        assertEquals("下午第一节 13:30", "13:30–15:10", timetable.courseTimeText(course));

        course.startNode = 7;
        course.endNode = 8;
        assertEquals("下午第二大节", "15:20–17:00", timetable.courseTimeText(course));

        course.startNode = 1;
        course.endNode = 2;
        assertEquals("08:00–09:40", timetable.courseTimeText(course));

        course.startNode = 9;
        course.endNode = 10;
        assertEquals("晚上第一节 18:30", "18:30–20:10", timetable.courseTimeText(course));

        course.startNode = 3;
        course.endNode = 3;
        assertEquals("10:00–10:45", timetable.courseTimeText(course));

        // 没有节次时间数据时不能崩，只是不显示
        timetable.nodeTimes = new String[0];
        assertEquals("", timetable.courseTimeText(course));
    }

    /** v3.4：小组件里真的把上课时间渲染出来了 */
    @Test
    public void 小组件显示上课时间() {
        Context context = RuntimeEnvironment.getApplication();
        Timetable timetable = new Timetable();
        timetable.termStart = termStartForWeek3();
        timetable.xnm = "2026";
        timetable.xqm = "3";

        Course course = new Course();
        course.name = "大学生心理健康教育";
        course.place = "教A-103";
        course.weekday = TodayWidgetProvider.todayWeekday();
        course.startNode = 5;
        course.endNode = 6;
        course.nodeText = "5-6";
        course.weekText = "1-18周";
        course.weeks = Timetable.parseWeeks(course.weekText);
        timetable.courses.add(course);
        CourseStore.save(context, timetable);

        View today = TodayWidgetProvider.build(context).apply(context, new FrameLayout(context));
        TextView time = today.findViewById(R.id.widget_time1);
        assertNotNull("今天小组件要有上课时间这一行", time);
        assertEquals("13:30–15:10", time.getText().toString());
        assertTrue("节次/地点那一行仍保留",
                ((TextView) today.findViewById(R.id.widget_detail1)).getText().toString()
                        .contains("教A-103"));

        View twoDays = TwoDaysWidgetProvider.build(context).apply(context, new FrameLayout(context));
        TextView todayTime = twoDays.findViewById(R.id.widget_today_time1);
        assertNotNull("今日+明日小组件也要有上课时间", todayTime);
        assertEquals("13:30–15:10", todayTime.getText().toString());
    }

    /** v3.6：老缓存里存着旧默认作息（14:00 那套），升级后要自动换成新作息 */
    @Test
    public void 旧缓存的作息时间会自动迁移() throws Exception {
        String json = "{\"xnm\":\"2026\",\"xqm\":\"3\",\"student\":\"张同学\","
                + "\"termStart\":\"2026-09-07\","
                + "\"nodeTimes\":[\"08:00-08:45\",\"08:55-09:40\",\"10:00-10:45\",\"10:55-11:40\","
                + "\"14:00-14:45\",\"14:55-15:40\",\"16:00-16:45\",\"16:55-17:40\"],"
                + "\"courses\":[]}";

        Timetable old = Timetable.fromJson(json);
        assertNotNull(old);
        assertEquals("下午第一节要换成 13:30", "13:30-14:15", old.nodeTimes[4]);
        assertEquals("晚上第一节要换成 18:30", "18:30-19:15", old.nodeTimes[8]);

        // 服务器给的节次时间不能被覆盖
        String serverJson = json.replace("\"nodeTimes\"", "\"nodeTimesFromServer\":true,\"nodeTimes\"");
        Timetable server = Timetable.fromJson(serverJson);
        assertEquals("服务器给的时间要保留", "14:00-14:45", server.nodeTimes[4]);

        // 存下来再读，也要记得"这份时间来自服务器"
        String saved = server.toJson();
        assertTrue("要记住来源标记", saved.contains("\"nodeTimesFromServer\":true"));
        assertEquals("往返后仍是服务器给的时间", "14:00-14:45", Timetable.fromJson(saved).nodeTimes[4]);
    }

    /** v3.6：升级 App 后（不需要重新导入）小组件立刻显示新作息 */
    @Test
    public void 升级后小组件时间自动纠正() {
        Context context = RuntimeEnvironment.getApplication();
        Timetable timetable = new Timetable();
        timetable.termStart = termStartForWeek3();
        timetable.xnm = "2026";
        timetable.xqm = "3";
        timetable.nodeTimes = new String[]{
                "08:00-08:45", "08:55-09:40", "10:00-10:45", "10:55-11:40",
                "14:00-14:45", "14:55-15:40", "16:00-16:45", "16:55-17:40"};
        timetable.nodeTimesFromServer = false;

        Course course = new Course();
        course.name = "大学生心理健康教育";
        course.place = "教A-103";
        course.weekday = TodayWidgetProvider.todayWeekday();
        course.startNode = 5;
        course.endNode = 6;
        course.nodeText = "5-6";
        course.weekText = "1-18周";
        course.weeks = Timetable.parseWeeks(course.weekText);
        timetable.courses.add(course);
        CourseStore.save(context, timetable);

        Timetable loaded = CourseStore.load(context);
        assertNotNull(loaded);
        assertEquals("读回来就该是新作息", "13:30-14:15", loaded.nodeTimes[4]);

        View today = TodayWidgetProvider.build(context).apply(context, new FrameLayout(context));
        assertEquals("13:30–15:10",
                ((TextView) today.findViewById(R.id.widget_time1)).getText().toString());
    }

    @Test
    public void 有缓存的课表能往返读写() {
        Timetable timetable = Timetable.fromJson(cacheJson("2026-09-07"));
        assertNotNull(timetable);
        assertEquals("2026-09-07", timetable.termStart);
        assertEquals(3, timetable.courses.size());
        assertEquals(16, timetable.maxWeek());
        assertEquals(6, timetable.maxNode());
        // 第2周第3节：周二(单周物理)没有课，周三(概率论)有课
        assertEquals(0, timetable.coursesAt(2, 3, 2).size());
        assertEquals(1, timetable.coursesAt(3, 3, 2).size());
        // 第 17 周不在任何课程的周次里
        assertEquals(0, timetable.coursesAt(3, 3, 17).size());
    }
}
