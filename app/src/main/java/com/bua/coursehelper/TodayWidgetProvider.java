package com.bua.coursehelper;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 桌面小组件①：今天课表。
 * 样式：顶部「今天 · 周一」+ 右上角日期，下面每门课一个圆角彩色块（课程名 + 节次·地点）。
 */
public class TodayWidgetProvider extends AppWidgetProvider {

    private static final int MAX_ROWS = 4;

    private static final int[] BLOCK_IDS = {
            R.id.widget_block1, R.id.widget_block2, R.id.widget_block3, R.id.widget_block4,
    };
    private static final int[] NAME_IDS = {
            R.id.widget_name1, R.id.widget_name2, R.id.widget_name3, R.id.widget_name4,
    };
    private static final int[] DETAIL_IDS = {
            R.id.widget_detail1, R.id.widget_detail2, R.id.widget_detail3, R.id.widget_detail4,
    };
    private static final int[] TIME_IDS = {
            R.id.widget_time1, R.id.widget_time2, R.id.widget_time3, R.id.widget_time4,
    };

    private static final int[] BLOCK_BACKGROUNDS = {
            R.drawable.widget_block_1, R.drawable.widget_block_2, R.drawable.widget_block_3,
            R.drawable.widget_block_4, R.drawable.widget_block_5, R.drawable.widget_block_6,
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        RemoteViews views = build(context);
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, views);
        }
    }

    /** 数据变化后主动刷新（导入课表、加/删自建课程、切主题色时调用） */
    public static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, TodayWidgetProvider.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            RemoteViews views = build(context);
            for (int id : ids) {
                manager.updateAppWidget(id, views);
            }
        } catch (Exception ignored) {
        }
    }

    static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_today);
        int theme = AppTheme.color(context);
        Timetable timetable = CourseStore.load(context);
        int week = CourseStore.currentWeek(context, timetable);
        int today = todayWeekday();

        String title = "今天 · " + CourseStore.weekdayName(today);
        if (week > 0) {
            title += " · 第 " + week + " 周";
        }
        views.setTextViewText(R.id.widget_title, title);
        views.setTextColor(R.id.widget_title, theme);
        views.setInt(R.id.widget_accent, "setBackgroundColor", theme);
        views.setTextViewText(R.id.widget_date,
                new SimpleDateFormat("M/d", Locale.CHINA).format(new Date()));

        List<Course> courses = new ArrayList<>();
        if (timetable != null) {
            courses = CourseStore.coursesOn(timetable, today, week);
            sortByNode(courses);
        }
        if (courses.size() > MAX_ROWS) {
            title += "（共" + courses.size() + "门）";
            views.setTextViewText(R.id.widget_title, title);
        }

        for (int i = 0; i < MAX_ROWS; i++) {
            if (i < courses.size()) {
                Course course = courses.get(i);
                views.setViewVisibility(BLOCK_IDS[i], View.VISIBLE);
                views.setInt(BLOCK_IDS[i], "setBackgroundResource", blockBackground(course));
                views.setTextViewText(NAME_IDS[i], course.name);
                views.setTextViewText(DETAIL_IDS[i], detailLine(course));
                // 上课时间（如 13:30–15:10），取不到就隐藏这一行
                String time = timetable == null ? "" : timetable.courseTimeText(course);
                views.setTextViewText(TIME_IDS[i], time);
                views.setViewVisibility(TIME_IDS[i], time.isEmpty() ? View.GONE : View.VISIBLE);
            } else {
                views.setViewVisibility(BLOCK_IDS[i], View.GONE);
            }
        }
        views.setViewVisibility(R.id.widget_empty, courses.isEmpty() ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.widget_empty,
                week > 0 ? "今天没课 🎉" : "点开 App 填一下当前周次");

        views.setOnClickPendingIntent(R.id.widget_root, openApp(context));
        return views;
    }

    /** 课程块颜色：按课程名固定分配，同一门课每次都是同一个颜色 */
    static int blockBackground(Course course) {
        int index = Math.abs((course.name == null ? "" : course.name).hashCode())
                % BLOCK_BACKGROUNDS.length;
        return BLOCK_BACKGROUNDS[index];
    }

    static String detailLine(Course course) {
        String place = (course.place == null || course.place.isEmpty()) ? "地点未定" : course.place;
        return course.nodeText + "节 · " + place;
    }

    static void sortByNode(List<Course> courses) {
        Collections.sort(courses, (a, b) -> {
            if (a.startNode != b.startNode) {
                return a.startNode - b.startNode;
            }
            return a.name.compareTo(b.name);
        });
    }

    static int todayWeekday() {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return day == Calendar.SUNDAY ? 7 : day - 1;
    }

    static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
