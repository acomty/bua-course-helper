import com.bua.coursehelper.Course;
import com.bua.coursehelper.Timetable;
import com.bua.coursehelper.TimetablePdfParser;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 桌面自测：直接用 App 里的 PDF 解析代码去读一份真实的课表 PDF。
 * 用法： java -cp "out;json.jar" PdfParseTest "D:\path\课表.pdf"
 */
public class PdfParseTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: PdfParseTest <课表.pdf>");
            System.exit(2);
        }
        byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
        System.out.println("PDF 大小: " + bytes.length + " 字节");

        if (args.length > 1 && "--debug".equals(args[1])) {
            com.bua.coursehelper.PdfTextExtractor.Page page =
                    com.bua.coursehelper.PdfTextExtractor.extract(bytes).get(0);
            System.out.println("文字段数: " + page.runs.size() + "  矩形数: " + page.rects.size());
            page.runs.sort((a, b) -> a.x != b.x ? Float.compare(a.x, b.x) : Float.compare(b.y, a.y));
            for (com.bua.coursehelper.PdfTextExtractor.TextRun run : page.runs) {
                System.out.println(String.format("  x=%7.1f y=%7.1f size=%4.1f  %s",
                        run.x, run.y, run.size, run.text));
            }
            return;
        }

        Timetable timetable = TimetablePdfParser.parse(bytes, "2026", "3");

        if (args.length > 1 && args[1].equals("--verify-weeks")) {
            crossCheckWeeks(timetable);
            return;
        }

        if (args.length > 1 && args[1].equals("--who")) {
            int day = Integer.parseInt(args[2]);
            int week = Integer.parseInt(args[3]);
            for (int node = 1; node <= 12; node++) {
                for (Course c : timetable.coursesAt(day, node, week)) {
                    System.out.println("node=" + node + " " + c.name + "  nodeText=" + c.nodeText
                            + "  weekText=" + c.weekText
                            + "  weeks=" + Timetable.weeksToText(c.weeks)
                            + "  该课第" + week + "周? " + (contains(c.weeks, week)));
                }
            }
            return;
        }

        if (args.length > 1 && args[1].startsWith("--weeks")) {
            int maxWeek = args.length > 2 ? Integer.parseInt(args[2]) : 6;
            for (int week = 1; week <= maxWeek; week++) {
                StringBuilder line = new StringBuilder("第" + week + "周: ");
                for (int day = 1; day <= 7; day++) {
                    StringBuilder cell = new StringBuilder();
                    for (Course c : timetable.coursesAt(day, 1, week)) {
                        if (cell.length() > 0) {
                            cell.append('/');
                        }
                        cell.append(c.name);
                    }
                    for (Course c : timetable.coursesAt(day, 5, week)) {
                        if (cell.length() > 0) {
                            cell.append('/');
                        }
                        cell.append(c.name);
                    }
                    for (Course c : timetable.coursesAt(day, 9, week)) {
                        if (cell.length() > 0) {
                            cell.append('/');
                        }
                        cell.append(c.name);
                    }
                    if (cell.length() > 0) {
                        line.append("[").append(courseDayName(day)).append(" ").append(cell).append("] ");
                    }
                }
                System.out.println(line);
            }
            return;
        }

        System.out.println("学生: " + timetable.student);
        System.out.println("学期: " + timetable.xnm + " / xqm=" + timetable.xqm + "  → " + timetable.termText());
        System.out.println("课程安排: " + timetable.courses.size() + " 条，"
                + timetable.courseCount() + " 门课，教学周 "
                + Timetable.weeksToText(timetable.allWeeks()));
        System.out.println();

        String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int rows = Math.max(timetable.maxNode(), 1);
        for (int node = 1; node <= rows; node++) {
            StringBuilder line = new StringBuilder(String.format("%2d节 ", node));
            for (int day = 1; day <= 7; day++) {
                java.util.List<Course> here = timetable.coursesAt(day, node, 0);
                String cell = "";
                if (!here.isEmpty() && here.get(0).startNode == node) {
                    cell = here.get(0).name + "@" + here.get(0).place;
                }
                line.append("| ").append(pad(cell, 18));
            }
            System.out.println(line);
        }

        System.out.println();
        System.out.println("--- 明细 ---");
        for (Course c : timetable.courses) {
            System.out.println(String.format("  %s 第%-4s %-16s %-8s %-22s [weekText=%s] weeks=%d %s",
                    c.weekdayName(), c.nodeText, c.name, c.teacher, c.place,
                    c.weekText, c.weeks.length, Timetable.weeksLabel(c.weeks)));
        }
    }

    /**
     * 自检：把每门课"存起来的周次"和"从周次原文重新解析的周次"逐周对照，
     * 再和 coursesAt() 的实际结果对照 —— 只要出现"某周多出/少了这门课"就会报出来。
     */
    private static void crossCheckWeeks(Timetable timetable) {
        int problems = 0;
        for (Course c : timetable.courses) {
            int[] recomputed = Timetable.parseWeeks(c.weekText);
            if (!Timetable.weeksToText(c.weeks).equals(Timetable.weeksToText(recomputed))) {
                System.out.println("! 周次不一致: " + c.name + " text=" + c.weekText
                        + " stored=" + Timetable.weeksToText(c.weeks)
                        + " recomputed=" + Timetable.weeksToText(recomputed));
                problems++;
            }
            for (int week = 1; week <= 20; week++) {
                boolean byStored = c.matchesWeek(week);
                boolean byText = c.weekText.isEmpty() || contains(recomputed, week);
                if (byStored != byText) {
                    System.out.println("! 第" + week + "周判断不一致: " + c.weekdayName() + " " + c.name
                            + " stored=" + byStored + " byText=" + byText);
                    problems++;
                }
            }
        }
        // 再按"周 × 星期 × 节次"扫一遍，确认 coursesAt 返回的课程都真的在该周上课
        for (int week = 1; week <= 20; week++) {
            for (int day = 1; day <= 7; day++) {
                for (int node = 1; node <= 12; node++) {
                    for (Course c : timetable.coursesAt(day, node, week)) {
                        boolean shouldShow = (c.weekText.isEmpty() || contains(c.weeks, week))
                                && node >= c.startNode && node <= c.endNode;
                        if (!shouldShow) {
                            System.out.println("! 多出的课: 第" + week + "周 " + c.weekdayName()
                                    + " 第" + node + "节 -> " + c.name + " 周次=" + c.weeksDisplay());
                            problems++;
                        }
                    }
                }
            }
        }
        System.out.println(problems == 0
                ? "周次自检：全部一致 ✅（共 " + timetable.courses.size() + " 条课程 × 20 周）"
                : "周次自检：发现 " + problems + " 处问题 ❌");
    }

    private static boolean contains(int[] weeks, int week) {
        for (int w : weeks) {
            if (w == week) {
                return true;
            }
        }
        return false;
    }

    private static String courseDayName(int day) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[day - 1];
    }

    private static String pad(String text, int width) {
        String value = text == null ? "" : text;
        if (value.length() > width) {
            value = value.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
