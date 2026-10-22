package com.bua.coursehelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一条课程安排：某门课在「某个星期 + 某个节次 + 某段周次」上的一次出现。
 * 对应正方教务系统 kbList 里的一条记录。
 */
public class Course {

    public String name = "";      // 课程名称
    public String teacher = "";   // 教师姓名
    public String place = "";     // 上课地点
    public int weekday = 0;       // 1=周一 … 7=周日
    public int startNode = 0;     // 起始节次
    public int endNode = 0;       // 结束节次
    public String nodeText = "";  // 节次原文，如 "3-4"
    public String weekText = "";  // 周次原文，如 "1-16周"
    public int[] weeks = new int[0];
    public String kch = "";       // 课程号
    public boolean custom = false; // true = 用户自己加的（旁听课等），导入课表时不会被覆盖

    public boolean matchesWeek(int week) {
        if (week <= 0 || weeks.length == 0) {
            return true;
        }
        for (int w : weeks) {
            if (w == week) {
                return true;
            }
        }
        return false;
    }

    public String weekdayName() {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return (weekday >= 1 && weekday <= 7) ? names[weekday - 1] : "";
    }

    /**
     * 周次的显示文本。
     * 如果原文有周次但没解析出来，会明确说明"按每周显示"——
     * 免得用户看到某门课出现在不该出现的周却不知道原因。
     */
    public String weeksDisplay() {
        if (weeks != null && weeks.length > 0) {
            return Timetable.weeksLabel(weeks);
        }
        if (weekText == null || weekText.trim().isEmpty()) {
            return "每周";
        }
        return weekText + "（未能识别，按每周显示）";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("teacher", teacher);
        o.put("place", place);
        o.put("weekday", weekday);
        o.put("startNode", startNode);
        o.put("endNode", endNode);
        o.put("nodeText", nodeText);
        o.put("weekText", weekText);
        o.put("kch", kch);
        o.put("custom", custom);
        JSONArray arr = new JSONArray();
        for (int w : weeks) {
            arr.put(w);
        }
        o.put("weeks", arr);
        return o;
    }

    public static Course fromJson(JSONObject o) {
        Course c = new Course();
        c.name = o.optString("name");
        c.teacher = o.optString("teacher");
        c.place = o.optString("place");
        c.weekday = o.optInt("weekday");
        c.startNode = o.optInt("startNode");
        c.endNode = o.optInt("endNode");
        c.nodeText = o.optString("nodeText");
        c.weekText = o.optString("weekText");
        c.kch = o.optString("kch");
        c.custom = o.optBoolean("custom", false);
        JSONArray arr = o.optJSONArray("weeks");
        if (arr != null) {
            c.weeks = new int[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                c.weeks[i] = arr.optInt(i);
            }
        }
        return c;
    }
}
