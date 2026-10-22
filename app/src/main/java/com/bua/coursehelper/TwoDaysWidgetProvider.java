package com.bua.coursehelper;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 桌面小组件②：今天 + 明天课表。
 * 样式：左边「今天 09-07」、右边「明天 09-08」，中间一条分隔线，各边最多 3 门课。
 */
public class TwoDaysWidgetProvider extends AppWidgetProvider {

    private static final int ROWS_PER_DAY = 3;

    private static final int[] TODAY_BLOCK_IDS = {
            R.id.widget_today_block1, R.id.widget_today_block2, R.id.widget_today_block3,
    };
    private static final int[] TODAY_NAME_IDS = {
            R.id.widget_today_name1, R.id.widget_today_name2, R.id.widget_today_name3,
    };
    private static final int[] TODAY_DETAIL_IDS = {
            R.id.widget_today_detail1, R.id.widget_today_detail2, R.id.widget_today_detail3,
    };
    private static final int[] TODAY_TIME_IDS = {
            R.id.widget_today_time1, R.id.widget_today_time2, R.id.widget_today_time3,
    };

    private static final int[] TOMORROW_BLOCK_IDS = {
            R.id.widget_tomorrow_block1, R.id.widget_tomorrow_block2, R.id.widget_tomorrow_block3,
    };
    private static final int[] TOMORROW_NAME_IDS = {
            R.id.widget_tomorrow_name1, R.id.widget_tomorrow_name2, R.id.widget_tomorrow_name3,
    };
    private static final int[] TOMORROW_DETAIL_IDS = {
            R.id.widget_tomorrow_detail1, R.id.widget_tomorrow_detail2, R.id.widget_tomorrow_detail3,
    };
    private static final int[] TOMORROW_TIME_IDS = {
            R.id.widget_tomorrow_time1, R.id.widget_tomorrow_time2, R.id.widget_tomorrow_time3,
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        RemoteViews views = build(context);
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, views);
        }
    }

    public static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, TwoDaysWidgetProvider.class));
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
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_two_days);
        int theme = AppTheme.color(context);
        Timetable timetable = CourseStore.load(context);
        int week = CourseStore.currentWeek(context, timetable);
        int today = TodayWidgetProvider.todayWeekday();
        int tomorrow = today >= 7 ? 1 : today + 1;

        views.setTextColor(R.id.widget_title_today, theme);
        views.setTextColor(R.id.widget_title_tomorrow, theme);
        views.setInt(R.id.widget_accent, "setBackgroundColor", theme);

        SimpleDateFormat format = new SimpleDateFormat("MM-dd", Locale.CHINA);
        Calendar calendar = Calendar.getInstance();
        String todayText = format.format(calendar.getTime());
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        String tomorrowText = format.format(calendar.getTime());

        views.setTextViewText(R.id.widget_title_today,
                "今天 " + todayText + (week > 0 ? " · 第" + week + "周" : ""));
        views.setTextViewText(R.id.widget_title_tomorrow, "明天 " + tomorrowText);

        List<Course> todayCourses = new ArrayList<>();
        List<Course> tomorrowCourses = new ArrayList<>();
        if (timetable != null) {
            todayCourses = CourseStore.coursesOn(timetable, today, week);
            tomorrowCourses = CourseStore.coursesOn(timetable, tomorrow, nextWeekIfNeeded(week, today));
            TodayWidgetProvider.sortByNode(todayCourses);
            TodayWidgetProvider.sortByNode(tomorrowCourses);
        }

        fillRows(views, TODAY_BLOCK_IDS, TODAY_NAME_IDS, TODAY_DETAIL_IDS, TODAY_TIME_IDS,
                todayCourses, R.id.widget_today_empty, timetable);
        fillRows(views, TOMORROW_BLOCK_IDS, TOMORROW_NAME_IDS, TOMORROW_DETAIL_IDS, TOMORROW_TIME_IDS,
                tomorrowCourses, R.id.widget_tomorrow_empty, timetable);

        views.setOnClickPendingIntent(R.id.widget_root, TodayWidgetProvider.openApp(context));
        return views;
    }

    /** 周日看「明天」就是下一周了 */
    private static int nextWeekIfNeeded(int week, int today) {
        if (week > 0 && today >= 7) {
            return week + 1;
        }
        return week;
    }

    private static void fillRows(RemoteViews views, int[] blockIds, int[] nameIds, int[] detailIds,
                                 int[] timeIds, List<Course> courses, int emptyId, Timetable timetable) {
        for (int i = 0; i < ROWS_PER_DAY; i++) {
            if (i < courses.size()) {
                Course course = courses.get(i);
                views.setViewVisibility(blockIds[i], View.VISIBLE);
                views.setInt(blockIds[i], "setBackgroundResource",
                        TodayWidgetProvider.blockBackground(course));
                views.setTextViewText(nameIds[i], course.name);
                views.setTextViewText(detailIds[i], TodayWidgetProvider.detailLine(course));
                // 上课时间（如 13:30–15:10），取不到就隐藏这一行
                String time = timetable == null ? "" : timetable.courseTimeText(course);
                views.setTextViewText(timeIds[i], time);
                views.setViewVisibility(timeIds[i], time.isEmpty() ? View.GONE : View.VISIBLE);
            } else {
                views.setViewVisibility(blockIds[i], View.GONE);
            }
        }
        views.setViewVisibility(emptyId, courses.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
