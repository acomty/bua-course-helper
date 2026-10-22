import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * 课表解析逻辑的自测（不需要安卓设备，桌面 JVM 即可跑）。
 *
 * 用法：
 *   javac -cp json.jar -d out app/src/main/java/com/bua/coursehelper/Course.java \
 *         app/src/main/java/com/bua/coursehelper/Timetable.java tools/ParseTest.java
 *   java -cp "out;json.jar" ParseTest
 */
public class ParseTest {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // 用反射调用，避免编译期依赖（Course / Timetable 在默认包里编译进来）
        Class<?> timetableClass = Class.forName("com.bua.coursehelper.Timetable");
        Class<?> courseClass = Class.forName("com.bua.coursehelper.Course");

        String json = sampleJson();
        Method parse = timetableClass.getMethod("parse", String.class, String.class, String.class);
        Object tt = parse.invoke(null, json, "2025", "3");

        java.util.List<?> courses = (java.util.List<?>) timetableClass.getField("courses").get(tt);

        check("课程条数 = 5", courses.size() == 5, "实际 " + courses.size());

        Object prob = courses.get(0);
        Object physics = courses.get(1);
        Object pe = courses.get(2);
        Object english = courses.get(3);
        Object elective = courses.get(4);

        check("概率论 周次 1-16", weeksOf(timetableClass, prob).equals("1-16"),
                weeksOf(timetableClass, prob));
        check("大学物理 单周 1,3,…,15", weeksOf(timetableClass, physics).equals("1,3,5,7,9,11,13,15"),
                weeksOf(timetableClass, physics));
        check("大学物理 好看写法 1-15周(单)", weeksLabelOf(timetableClass, physics).equals("1-15周(单)"),
                weeksLabelOf(timetableClass, physics));
        check("体育 1-5,7-9", weeksOf(timetableClass, pe).equals("1-5,7-9"),
                weeksOf(timetableClass, pe));
        check("英语 双周 2-14", weeksOf(timetableClass, english).equals("2,4,6,8,10,12,14"),
                weeksOf(timetableClass, english));
        check("英语 好看写法 2-14周(双)", weeksLabelOf(timetableClass, english).equals("2-14周(双)"),
                weeksLabelOf(timetableClass, english));
        check("选修 3,5,7", weeksOf(timetableClass, elective).equals("3,5,7"),
                weeksOf(timetableClass, elective));

        Method maxNode = timetableClass.getMethod("maxNode");
        check("最大节次 = 10", ((Integer) maxNode.invoke(tt)) == 10, String.valueOf(maxNode.invoke(tt)));

        Method weekdayName = courseClass.getMethod("weekdayName");
        check("xqj=3 → 周三", "周三".equals(weekdayName.invoke(prob)), String.valueOf(weekdayName.invoke(prob)));
        check("nodeText = 3-4", "3-4".equals(courseClass.getField("nodeText").get(prob)),
                String.valueOf(courseClass.getField("nodeText").get(prob)));

        Method coursesAt = timetableClass.getMethod("coursesAt", int.class, int.class, int.class);
        check("第2周周二第3节 没有物理（单周）",
                ((java.util.List<?>) coursesAt.invoke(tt, 2, 3, 2)).isEmpty(), "");
        check("第3周周二第3节 有物理（单周）",
                ((java.util.List<?>) coursesAt.invoke(tt, 2, 3, 3)).size() == 1, "");

        Method allWeeks = timetableClass.getMethod("allWeeks");
        int[] weeks = (int[]) allWeeks.invoke(tt);
        check("教学周 1-16（共 16 周）", weeks.length == 16 && weeks[0] == 1 && weeks[15] == 16,
                weeks.length + " 周");

        Method termText = timetableClass.getMethod("termText");
        check("学期文本 = 2025-2026 学年 第一学期",
                "2025-2026 学年 第一学期".equals(termText.invoke(tt)), String.valueOf(termText.invoke(tt)));

        Method timeOfNode = timetableClass.getMethod("timeOfNode", int.class);
        check("两节一段的时间表：第3节 = 10:00-11:40",
                "10:00-11:40".equals(timeOfNode.invoke(tt, 3)), String.valueOf(timeOfNode.invoke(tt, 3)));

        // 另一所学校可能给的是「一节一段」的时间表，也要能对上
        Object perNode = parse.invoke(null, sampleJson(true), "2025", "3");
        check("一节一段的时间表：第3节 = 10:00-10:45",
                "10:00-10:45".equals(timeOfNode.invoke(perNode, 3)),
                String.valueOf(timeOfNode.invoke(perNode, 3)));
        check("一节一段的时间表：第7节 = 16:00-16:45",
                "16:00-16:45".equals(timeOfNode.invoke(perNode, 7)),
                String.valueOf(timeOfNode.invoke(perNode, 7)));
        check("一节一段的时间表：第8节 = 16:55-17:40",
                "16:55-17:40".equals(timeOfNode.invoke(perNode, 8)),
                String.valueOf(timeOfNode.invoke(perNode, 8)));

        // 缓存往返
        Method toJson = timetableClass.getMethod("toJson");
        Method fromJson = timetableClass.getMethod("fromJson", String.class);
        Object restored = fromJson.invoke(null, toJson.invoke(tt));
        java.util.List<?> restoredCourses = (java.util.List<?>) timetableClass.getField("courses").get(restored);
        check("缓存往返后课程数一致", restoredCourses.size() == 5, String.valueOf(restoredCourses.size()));
        check("缓存往返后周次一致",
                weeksOf(timetableClass, restoredCourses.get(1)).equals("1,3,5,7,9,11,13,15"),
                weeksOf(timetableClass, restoredCourses.get(1)));

        Method weeksToText = timetableClass.getMethod("weeksToText", int[].class);
        check("weeksToText({1,2,3,5}) = 1-3,5",
                "1-3,5".equals(weeksToText.invoke(null, new int[]{1, 2, 3, 5})),
                String.valueOf(weeksToText.invoke(null, new int[]{1, 2, 3, 5})));

        // 单双周混合写法：整串同时含"单""双"，不能互相抵消（PDF/在线导入都会遇到）
        Method parseWeeks = timetableClass.getMethod("parseWeeks", String.class);
        int[] mixed = (int[]) parseWeeks.invoke(null, "3-5周(单),6-8周(双),9-18周");
        check("单双周混合 → 14 周", mixed.length == 14, mixed.length + " 周");
        check("单双周混合 → 3,5-6,8-18",
                "3,5-6,8-18".equals(weeksToText.invoke(null, mixed)),
                String.valueOf(weeksToText.invoke(null, mixed)));
        int[] oddOnly = (int[]) parseWeeks.invoke(null, "1-16周(单)");
        check("1-16周(单) → 8 周", oddOnly.length == 8, oddOnly.length + " 周");

        System.out.println();
        if (failed == 0) {
            System.out.println("全部通过 ✅");
        } else {
            System.out.println(failed + " 项失败 ❌");
            System.exit(1);
        }
    }

    private static String weeksOf(Class<?> timetableClass, Object course) throws Exception {
        int[] weeks = (int[]) course.getClass().getField("weeks").get(course);
        Method m = timetableClass.getMethod("weeksToText", int[].class);
        return (String) m.invoke(null, weeks);
    }

    private static String weeksLabelOf(Class<?> timetableClass, Object course) throws Exception {
        int[] weeks = (int[]) course.getClass().getField("weeks").get(course);
        Method m = timetableClass.getMethod("weeksLabel", int[].class);
        return (String) m.invoke(null, weeks);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            System.out.println("  ❌ " + name + "   实际: " + detail);
        }
    }

    /** 模拟正方 /kbcx/xskbcx_cxXsKb.html 的返回结构 */
    private static String sampleJson() throws Exception {
        return sampleJson(false);
    }

    private static String sampleJson(boolean perNodeTimes) throws Exception {
        JSONObject root = new JSONObject();
        root.put("xsxx", new JSONObject().put("XM", "张同学").put("XH_ID", "2025000000"));

        org.json.JSONArray sjk = new org.json.JSONArray();
        if (perNodeTimes) {
            String[][] nodes = {
                    {"第1节", "08:00-08:45"}, {"第2节", "08:55-09:40"},
                    {"第3节", "10:00-10:45"}, {"第4节", "10:55-11:40"},
                    {"第5节", "14:00-14:45"}, {"第6节", "14:55-15:40"},
                    {"第7节", "16:00-16:45"}, {"第8节", "16:55-17:40"},
                    {"第9节", "19:00-19:45"}, {"第10节", "19:55-20:40"},
            };
            for (String[] t : nodes) {
                sjk.put(new JSONObject().put("jcmc", t[0]).put("jcsj", t[1]));
            }
        } else {
            String[][] blocks = {
                    {"第1,2节", "08:00-09:40"}, {"第3,4节", "10:00-11:40"},
                    {"第5,6节", "14:00-15:40"}, {"第7,8节", "16:00-17:40"},
                    {"第9,10节", "19:00-20:40"},
            };
            for (String[] t : blocks) {
                sjk.put(new JSONObject().put("jcmc", t[0]).put("jcsj", t[1]));
            }
        }
        root.put("sjkList", sjk);

        org.json.JSONArray kb = new org.json.JSONArray();
        kb.put(course("概率论与数理统计", "张老师", "教A-207", "3", "3-4", "1-16周", "MATH1001"));
        kb.put(course("大学物理", "惠雁冰", "A-207", "2", "3-4", "1-16周(单)", "PHYS1001"));
        kb.put(course("体育", "李老师", "体育馆", "5", "5-6", "1-5,7-9周", "PE1001"));
        kb.put(course("大学英语", "王老师", "外语楼101", "1", "1-2", "2-14周(双)", "ENG1001"));
        kb.put(course("通识选修", "", "", "6", "9-10", "3,5,7周", "ELEC1001"));
        root.put("kbList", kb);
        return root.toString();
    }

    private static JSONObject course(String kcmc, String xm, String cdmc,
                                     String xqj, String jcs, String zcd, String kch) throws Exception {
        return new JSONObject()
                .put("kcmc", kcmc).put("xm", xm).put("cdmc", cdmc)
                .put("xqj", xqj).put("jcs", jcs).put("zcd", zcd).put("kch", kch);
    }
}
