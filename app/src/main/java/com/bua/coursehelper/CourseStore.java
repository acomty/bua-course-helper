package com.bua.coursehelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 本地数据的统一入口：课表缓存、当前周次、自建课程、桌面小组件刷新。
 * 主界面和两个小组件都走这里，避免各写一套。
 */
public final class CourseStore {

    public static final String PREF = "course_helper";

    private CourseStore() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------- 课表缓存 ----------------

    public static Timetable load(Context context) {
        return Timetable.fromJson(prefs(context).getString("cache", ""));
    }

    public static void save(Context context, Timetable timetable) {
        if (timetable == null) {
            return;
        }
        prefs(context).edit().putString("cache", timetable.toJson()).apply();
    }

    // ---------------- 当前周次 ----------------

    /**
     * 今天是第几周：
     *   ① 用户填过的「当前第几周」锚点 → 按天数往后推算（最优先）；
     *   ② 否则用课表里的开学日期。
     * 判断不出来返回 0。
     */
    public static int currentWeek(Context context, Timetable timetable) {
        if (timetable == null) {
            return 0;
        }
        SharedPreferences prefs = prefs(context);
        int anchorWeek = prefs.getInt("week_anchor_week", 0);
        String anchorDate = prefs.getString("week_anchor_date", "");
        if (anchorWeek > 0 && anchorDate != null && !anchorDate.isEmpty()) {
            int drift = Timetable.weeksBetween(anchorDate, new Date());
            if (drift != Integer.MIN_VALUE) {
                return clamp(timetable, anchorWeek + drift);
            }
        }
        String start = timetable.termStart;
        if (start == null || start.isEmpty()) {
            start = prefs.getString("term_start", "");
        }
        int week = Timetable.weekOfDate(start, new Date());
        return week > 0 ? clamp(timetable, week) : 0;
    }

    /** 学期结束后停在最后一周 */
    public static int clamp(Timetable timetable, int week) {
        if (timetable != null) {
            int maxWeek = timetable.maxWeek();
            if (maxWeek > 0 && week > maxWeek) {
                week = maxWeek;
            }
        }
        return Math.max(1, week);
    }

    // ---------------- 自建课程 ----------------

    public static void addCustomCourse(Context context, Timetable timetable, Course course) {
        if (timetable == null || course == null) {
            return;
        }
        course.custom = true;
        timetable.courses.add(course);
        save(context, timetable);
        updateWidgets(context);
    }

    public static void replaceCustomCourse(Context context, Timetable timetable,
                                           Course oldCourse, Course newCourse) {
        if (timetable == null || newCourse == null) {
            return;
        }
        newCourse.custom = true;
        if (oldCourse != null) {
            timetable.courses.remove(oldCourse);
        }
        timetable.courses.add(newCourse);
        save(context, timetable);
        updateWidgets(context);
    }

    public static void removeCustomCourse(Context context, Timetable timetable, Course course) {
        if (timetable == null || course == null) {
            return;
        }
        timetable.courses.remove(course);
        save(context, timetable);
        updateWidgets(context);
    }

    // ---------------- 查某天的课（小组件用） ----------------

    /**
     * 查某一天有哪些课（给桌面小组件用）。
     *
     * 注意：**判断不出当前周次时返回空列表**，绝不返回"当天所有课" ——
     * 否则小组件会把这学期其它周的课也列出来，看起来就像多出了不该有的课。
     */
    public static List<Course> coursesOn(Timetable timetable, int weekday, int week) {
        List<Course> out = new ArrayList<>();
        if (timetable == null || week <= 0) {
            return out;
        }
        for (Course c : timetable.courses) {
            if (c.weekday == weekday && c.matchesWeek(week)) {
                out.add(c);
            }
        }
        return out;
    }

    public static String weekdayName(int weekday) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return (weekday >= 1 && weekday <= 7) ? names[weekday - 1] : "";
    }

    /** 小组件里一行课的文字 */
    public static String courseLine(Context context, Timetable timetable, Course course) {
        StringBuilder sb = new StringBuilder();
        sb.append("第").append(course.nodeText).append("节  ").append(course.name);
        if (course.place != null && !course.place.isEmpty()) {
            sb.append("  @").append(course.place);
        }
        return sb.toString();
    }

    // ---------------- 小组件 ----------------

    public static void updateWidgets(Context context) {
        TodayWidgetProvider.updateAll(context);
        TwoDaysWidgetProvider.updateAll(context);
    }
}
