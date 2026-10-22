import com.bua.coursehelper.JwglxtClient;
import com.bua.coursehelper.Course;
import com.bua.coursehelper.Timetable;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 命令行一键验证：用真实学号密码登录教务系统并打印课表。
 *
 * 用法（项目根目录下的 run-fetch-test.bat 会自动带上参数和环境）：
 *   run-fetch-test.bat 学号 密码 [学年] [学期代码] [教务系统地址]
 *   例如： run-fetch-test.bat 20251234 mypassword 2025 3
 *
 * 学期代码：3=第一学期 12=第二学期 16=第三学期
 */
public class LoginFetchTest {

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length < 2) {
            out.println("用法: run-fetch-test.bat 学号 密码 [学年] [学期代码] [教务系统地址]");
            out.println("  学期代码：3=第一学期  12=第二学期  16=第三学期");
            System.exit(2);
        }

        String username = args[0];
        String password = args[1];
        String xnm = args.length > 2 ? args[2] : String.valueOf(currentAcademicYear());
        String xqm = args.length > 3 ? args[3] : "3";
        String baseUrl = args.length > 4 ? args[4] : JwglxtClient.DEFAULT_BASE_URL;

        out.println("教务系统: " + baseUrl);
        out.println("学号: " + username);
        out.println("学年: " + xnm + "   学期代码: " + xqm);
        out.println("--- 1/2 登录 ---");

        JwglxtClient client = new JwglxtClient(baseUrl, username, password);
        JwglxtClient.Result result = client.login("");
        if (!result.ok) {
            out.println("登录失败: " + result.message);
            if (result.message != null && result.message.contains("验证码")) {
                out.println("（提示：这个账号近期输错次数较多，需要先在手机上用企业微信登录一次教务系统，");
                out.println("  或者在浏览器里正常登录一次，之后再跑本工具。）");
            }
            System.exit(3);
        }
        out.println("登录成功");

        out.println("--- 2/2 抓取课表 ---");
        String json = client.fetchTimetableJson(xnm, xqm);
        Timetable timetable = Timetable.parse(json, xnm, xqm);

        out.println();
        out.println("=".repeat(72));
        out.println(timetable.termText() + "   共 " + timetable.courseCount() + " 门课 / "
                + timetable.courses.size() + " 条安排");
        out.println("=".repeat(72));

        String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int rows = Math.max(timetable.maxNode(), 1);
        for (int node = 1; node <= rows; node++) {
            StringBuilder line = new StringBuilder();
            line.append(String.format("%2d节 ", node));
            for (int day = 1; day <= 7; day++) {
                java.util.List<Course> here = timetable.coursesAt(day, node, 0);
                String cell = "";
                if (!here.isEmpty() && here.get(0).startNode == node) {
                    cell = here.get(0).name + "@" + here.get(0).place;
                }
                line.append("| ").append(pad(cell, 16));
            }
            out.println(line);
        }

        out.println();
        out.println("--- 全部课程 ---");
        for (Course c : timetable.courses) {
            out.println(String.format("  %s 第%s节  %s  %s  %s  周次:%s",
                    c.weekdayName(), c.nodeText, c.name, c.teacher, c.place,
                    Timetable.weeksLabel(c.weeks)));
        }

        String outFile = "timetable.json";
        java.nio.file.Files.write(java.nio.file.Paths.get(outFile),
                timetable.toJson().getBytes(StandardCharsets.UTF_8));
        out.println();
        out.println("课表 JSON 已保存到: " + new java.io.File(outFile).getAbsolutePath());
        out.println("如果上面能看到你的课程，说明协议完全打通，安卓 App 用同样的代码一定能导入。");
    }

    private static int currentAcademicYear() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int year = now.get(java.util.Calendar.YEAR);
        return (now.get(java.util.Calendar.MONTH) + 1) >= 9 ? year : year - 1;
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
