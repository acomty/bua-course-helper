package com.bua.coursehelper;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 课表网格：整个星期一到星期日铺满屏幕宽度，节次行铺满可用高度，
 * 也就是说打开就是完整一周课表，不需要左右或上下滑动。
 *
 * 格子里字小放不下时，点一下格子会回调 {@link OnCourseTapListener} 显示完整信息。
 */
public class TimetableGridView extends View {

    /** 点某个格子的回调 */
    public interface OnCourseTapListener {
        void onCourseTap(List<Course> courses, int weekday, int node);
    }

    /** 左右滑动切周的回调：delta = +1 下一周，-1 上一周 */
    public interface OnWeekSwipeListener {
        void onWeekSwipe(int delta);
    }

    private static final String[] DAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private Timetable timetable;
    private int week = 0;              // 0 = 全部周
    private int todayWeekday = 0;      // 1..7，用来把今天那一列淡淡标出来
    private int themeColor = 0xFF94070A;   // 主题色（可被用户改）
    private OnCourseTapListener tapListener;
    private OnWeekSwipeListener weekSwipeListener;
    private float downX;
    private float downY;
    private boolean dragged;

    /** 切周过渡动画用：上一周的畫面快照 + 动画进度 */
    private Bitmap transitionBitmap;
    private float transitionProgress = 1f;
    private int transitionDirection = 1;
    private ValueAnimator transitionAnimator;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBold = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float firstColWidth;
    private float dayWidth;
    private float rowHeight;
    private float headerHeight;
    private int rows = 12;

    private final float density;

    public TimetableGridView(Context context) {
        this(context, null);
    }

    public TimetableGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        line.setColor(0xFFE3E3E8);
        line.setStrokeWidth(Math.max(1f, dp(0.6f)));

        text.setColor(0xFF3A3A42);
        text.setTextAlign(Paint.Align.CENTER);

        textBold.setColor(0xFF1F1F23);
        textBold.setTextAlign(Paint.Align.CENTER);
        textBold.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    // ---------------- 数据 ----------------

    public void setTimetable(Timetable timetable) {
        this.timetable = timetable;
        if (timetable != null) {
            rows = Math.max(timetable.maxNode(), Math.min(timetable.nodeTimes.length, 12));
            rows = Math.max(rows, 4);
        }
        requestLayout();
        invalidate();
    }

    public int getWeek() {
        return week;
    }

    public void setWeek(int week) {
        this.week = week;
        clearTransition();
        invalidate();
    }

    /**
     * 带过渡动画地切到某一周：旧的一周往一侧滑出，新的一周从另一侧滑入。
     *
     * @param direction +1 表示看下一周（新页从右边进来），-1 表示看上一周
     */
    public void setWeekAnimated(int week, int direction) {
        if (week == this.week || direction == 0) {
            setWeek(week);
            return;
        }
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            setWeek(week);          // 还没布局好（比如测试环境）就直接切
            return;
        }

        // 先把"当前这一周"的画面拍下来，动画期间把它滑出去
        try {
            Bitmap snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            drawContent(new Canvas(snapshot));
            clearTransition();
            transitionBitmap = snapshot;
        } catch (Exception e) {
            setWeek(week);
            return;
        }

        transitionDirection = direction;
        transitionProgress = 0f;
        this.week = week;           // 新的一周立即生效，动画只是视觉过渡

        if (transitionAnimator != null) {
            transitionAnimator.cancel();
        }
        transitionAnimator = ValueAnimator.ofFloat(0f, 1f);
        transitionAnimator.setDuration(240);
        transitionAnimator.setInterpolator(new DecelerateInterpolator());
        transitionAnimator.addUpdateListener(animation -> {
            transitionProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        transitionAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                clearTransition();
                invalidate();
            }
        });
        transitionAnimator.start();
        invalidate();
    }

    private void clearTransition() {
        transitionProgress = 1f;
        if (transitionBitmap != null) {
            Bitmap bitmap = transitionBitmap;
            transitionBitmap = null;
            bitmap.recycle();
        }
    }

    public void setTodayWeekday(int weekday) {
        this.todayWeekday = weekday;
        invalidate();
    }

    /** 主题色（用户自选）：影响「今天」那一列的底色和表头高亮 */
    public void setThemeColor(int color) {
        this.themeColor = color;
        invalidate();
    }

    public void setOnCourseTapListener(OnCourseTapListener listener) {
        this.tapListener = listener;
    }

    public void setOnWeekSwipeListener(OnWeekSwipeListener listener) {
        this.weekSwipeListener = listener;
    }

    // ---------------- 测量 ----------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        if (width <= 0) {
            width = (int) dp(320);
        }

        firstColWidth = Math.max(dp(26), width * 0.125f);   // 左侧「节次」列
        dayWidth = (width - firstColWidth) / 7f;            // 其余 7 等分给周一~周日

        headerHeight = Math.max(dp(20), Math.min(dp(28), height * 0.06f));

        int rowCount = Math.max(rows, 1);
        int available = height - (int) headerHeight;
        if (heightMode == MeasureSpec.UNSPECIFIED || available <= 0) {
            // 没有约束时给个默认高度
            rowHeight = dp(44);
            height = (int) (headerHeight + rowHeight * rowCount);
        } else {
            rowHeight = Math.max(dp(26), available / (float) rowCount);
            height = (int) (headerHeight + rowHeight * rowCount);
        }
        setMeasuredDimension(width, height);
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);

        if (transitionBitmap == null || transitionProgress >= 1f) {
            drawContent(canvas);
            return;
        }

        float width = getMeasuredWidth();
        // 新的一周从一侧滑入
        canvas.save();
        canvas.translate(transitionDirection * width * (1f - transitionProgress), 0);
        drawContent(canvas);
        canvas.restore();
        // 旧的一周往另一侧滑出
        canvas.save();
        canvas.translate(-transitionDirection * width * transitionProgress, 0);
        canvas.drawBitmap(transitionBitmap, 0, 0, null);
        canvas.restore();
    }

    /** 画整张课表（不含切周过渡的叠加层） */
    private void drawContent(Canvas canvas) {
        float w = getMeasuredWidth();
        float h = getMeasuredHeight();

        // 今天那一列浅浅标一下（用主题色）
        if (todayWeekday >= 1 && todayWeekday <= 7) {
            fill.setColor(AppTheme.withAlpha(themeColor, 0x18));
            float left = firstColWidth + dayWidth * (todayWeekday - 1);
            canvas.drawRect(left, headerHeight, left + dayWidth, h, fill);
        }

        // 表头
        fill.setColor(0xFFF6F6F9);
        canvas.drawRect(0, 0, w, headerHeight, fill);

        float headerText = Math.max(sp(9), Math.min(sp(11), headerHeight * 0.46f));
        textBold.setTextSize(headerText);
        for (int i = 0; i < 7; i++) {
            float cx = firstColWidth + dayWidth * i + dayWidth / 2f;
            textBold.setColor(i + 1 == todayWeekday ? themeColor : 0xFF1F1F23);
            canvas.drawText(DAYS[i], cx, headerHeight / 2f + headerText * 0.36f, textBold);
        }
        textBold.setColor(0xFF6B6B73);
        textBold.setTextSize(Math.max(sp(8), headerText * 0.85f));
        canvas.drawText("节次", firstColWidth / 2f, headerHeight / 2f + headerText * 0.34f, textBold);

        // 网格线
        for (int r = 0; r <= rows; r++) {
            float y = headerHeight + rowHeight * r;
            canvas.drawLine(0, y, w, y, line);
        }
        for (int c = 0; c <= 8; c++) {
            float x = (c == 0) ? 0 : firstColWidth + dayWidth * (c - 1);
            canvas.drawLine(x, 0, x, h, line);
        }

        // 左侧节次 + 时间
        float nodeTextSize = Math.max(sp(8), Math.min(sp(10), rowHeight * 0.26f));
        float timeTextSize = Math.max(sp(7), Math.min(sp(8.5f), rowHeight * 0.21f));
        for (int r = 1; r <= rows; r++) {
            float top = headerHeight + rowHeight * (r - 1);
            textBold.setColor(0xFF1F1F23);
            textBold.setTextSize(nodeTextSize);
            canvas.drawText(String.valueOf(r), firstColWidth / 2f, top + rowHeight * 0.42f, textBold);

            String time = timetable == null ? "" : timetable.timeOfNode(r);
            if (time != null && !time.isEmpty()) {
                String brief = time.length() > 11 ? time.substring(0, 5) : time;
                text.setColor(0xFF8A8A92);
                text.setTextSize(timeTextSize);
                canvas.drawText(brief, firstColWidth / 2f, top + rowHeight * 0.76f, text);
            }
        }

        if (timetable == null) {
            return;
        }

        // 课程块
        for (int day = 1; day <= 7; day++) {
            List<Course> visible = new ArrayList<>();
            for (Course c : timetable.courses) {
                if (c.weekday == day && c.matchesWeek(week)) {
                    visible.add(c);
                }
            }
            for (int i = 0; i < visible.size(); i++) {
                Course c = visible.get(i);
                boolean overlap = false;
                for (int j = 0; j < i; j++) {
                    Course other = visible.get(j);
                    if (c.startNode <= other.endNode && other.startNode <= c.endNode) {
                        overlap = true;
                        break;
                    }
                }
                drawCourse(canvas, c, day, overlap);
            }
        }
    }

    private void drawCourse(Canvas canvas, Course c, int day, boolean rightLane) {
        float left = firstColWidth + dayWidth * (day - 1);
        float usableWidth = rightLane ? dayWidth / 2f : dayWidth;
        if (rightLane) {
            left += dayWidth / 2f;
        }
        float top = headerHeight + rowHeight * (c.startNode - 1);
        float bottom = headerHeight + rowHeight * c.endNode;

        RectF rect = new RectF(left + dp(1.5f), top + dp(1.5f),
                left + usableWidth - dp(1.5f), bottom - dp(1.5f));

        int hue = Math.abs(c.name.hashCode()) % 360;
        int background = Color.HSVToColor(new float[]{hue, 0.30f, 0.97f});
        int edge = Color.HSVToColor(new float[]{hue, 0.55f, 0.72f});

        fill.setColor(background);
        float radius = dp(5);
        canvas.drawRoundRect(rect, radius, radius, fill);

        line.setColor(edge);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.2f));
        if (c.custom) {
            // 自己加的（旁听课）用虚线边框，和教务系统的课区分开
            line.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(3), dp(2)}, 0));
        }
        canvas.drawRoundRect(rect, radius, radius, line);
        line.setPathEffect(null);
        line.setStyle(Paint.Style.FILL);
        line.setStrokeWidth(Math.max(1f, dp(0.6f)));
        line.setColor(0xFFE3E3E8);

        // 课程名（尽量多塞几行）+ 地点（有空间才画）
        float nameSize = Math.max(sp(8), Math.min(sp(11), usableWidth * 0.22f));
        float lineHeight = nameSize * 1.18f;
        float cx = rect.centerX();
        float maxWidth = rect.width() - dp(3);
        int maxLines = Math.max(1, (int) ((rect.height() - dp(3)) / lineHeight));

        text.setColor(0xFF1F1F23);
        text.setTextSize(nameSize);
        text.setFakeBoldText(true);
        float y = drawWrapped(canvas, c.name, cx, rect.top + nameSize, maxWidth, maxLines, lineHeight, text);

        float placeSize = Math.max(sp(7), nameSize - sp(1.5f));
        if (c.place != null && !c.place.isEmpty() && y + placeSize * 0.9f < rect.bottom - dp(1)) {
            text.setFakeBoldText(false);
            text.setTextSize(placeSize);
            text.setColor(0xFF4A4A52);
            drawWrapped(canvas, c.place, cx, y + placeSize * 0.2f, maxWidth, 1, placeSize * 1.1f, text);
        }
        text.setFakeBoldText(false);
    }

    /** 按字换行，返回下一行的基线 y */
    private float drawWrapped(Canvas canvas, String content, float cx, float y,
                              float maxWidth, int maxLines, float lineHeight, Paint paint) {
        if (content == null || content.isEmpty() || maxLines <= 0) {
            return y;
        }
        StringBuilder current = new StringBuilder();
        List<String> lines = new ArrayList<>();
        boolean truncated = false;

        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (paint.measureText(current.toString() + ch) > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
                if (lines.size() >= maxLines) {
                    truncated = true;
                    break;
                }
            }
            current.append(ch);
        }
        if (!truncated && current.length() > 0 && lines.size() < maxLines) {
            lines.add(current.toString());
        } else if (truncated || (current.length() > 0 && lines.size() >= maxLines)) {
            truncated = true;
        }
        if (truncated && !lines.isEmpty()) {
            String last = lines.get(lines.size() - 1);
            lines.set(lines.size() - 1, (last.length() > 1 ? last.substring(0, last.length() - 1) : last) + "…");
        }

        float cursor = y;
        for (String item : lines) {
            canvas.drawText(item, cx, cursor, paint);
            cursor += lineHeight;
        }
        return cursor;
    }

    // ---------------- 手势：点格子看详情 / 左右滑切周 ----------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                dragged = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(x - downX) > dp(10) || Math.abs(y - downY) > dp(10)) {
                    dragged = true;
                }
                return true;

            case MotionEvent.ACTION_UP:
                float dx = x - downX;
                float dy = y - downY;
                boolean horizontal = Math.abs(dx) > Math.abs(dy) * 1.4f;
                if (weekSwipeListener != null && Math.abs(dx) > dp(50) && horizontal) {
                    // 向左滑看下一周，向右滑看上一周
                    weekSwipeListener.onWeekSwipe(dx < 0 ? 1 : -1);
                    return true;
                }
                if (!dragged && tapListener != null && timetable != null && x > firstColWidth && y > headerHeight) {
                    int day = (int) ((x - firstColWidth) / dayWidth) + 1;
                    int node = (int) ((y - headerHeight) / rowHeight) + 1;
                    if (day >= 1 && day <= 7 && node >= 1 && node <= rows) {
                        List<Course> here = timetable.coursesAt(day, node, week);
                        if (!here.isEmpty()) {
                            performClick();
                            tapListener.onCourseTap(here, day, node);
                        }
                    }
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragged = false;
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
