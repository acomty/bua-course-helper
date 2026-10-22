package com.bua.coursehelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/**
 * 界面主题色：用户可以自选，存在本地偏好里，主界面和桌面小组件都用它。
 */
public final class AppTheme {

    /** 可选主题色（名字 + 颜色） */
    public static final String[] NAMES = {"经典红", "天空蓝", "森林绿", "葡萄紫", "活力橙", "科技青"};
    public static final int[] COLORS = {
            0xFF94070A,   // 经典红（默认）
            0xFF1565C0,   // 天空蓝
            0xFF2E7D32,   // 森林绿
            0xFF6A1B9A,   // 葡萄紫
            0xFFE65100,   // 活力橙
            0xFF00838F,   // 科技青
    };

    private static final String KEY_INDEX = "theme_index";

    private AppTheme() {
    }

    public static int index(Context context) {
        SharedPreferences prefs = CourseStore.prefs(context);
        int index = prefs.getInt(KEY_INDEX, 0);
        if (index < 0 || index >= COLORS.length) {
            index = 0;
        }
        return index;
    }

    public static int color(Context context) {
        return COLORS[index(context)];
    }

    public static void setIndex(Context context, int index) {
        CourseStore.prefs(context).edit().putInt(KEY_INDEX, index).apply();
    }

    /** 和白色混合，得到浅一点的同色系（用于背景、选中态） */
    public static int lighten(int color, float ratio) {
        float r = Math.min(1f, Math.max(0f, ratio));
        int red = (int) (Color.red(color) + (255 - Color.red(color)) * r);
        int green = (int) (Color.green(color) + (255 - Color.green(color)) * r);
        int blue = (int) (Color.blue(color) + (255 - Color.blue(color)) * r);
        return Color.argb(255, red, green, blue);
    }

    /** 带透明度的主题色（用于课表里"今天"那一列的高亮） */
    public static int withAlpha(int color, int alpha) {
        return Color.argb(Math.min(255, Math.max(0, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    public static GradientDrawable filled(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable outlined(Context context, int color, float radiusDp, float strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(Math.max(1, (int) dp(context, strokeDp)), color);
        return drawable;
    }

    public static GradientDrawable circle(Context context, int color, float sizeDp, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (selected) {
            drawable.setStroke((int) dp(context, 3), Color.WHITE);
        }
        return drawable;
    }

    private static float dp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }
}
