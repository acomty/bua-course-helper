package com.bua.coursehelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面。
 *
 * v1.7 新增：
 *   * 自建课程：可以手动添加/编辑/删除自己的课（旁听课等），虚线边框区分，导入课表时不会被覆盖；
 *   * 主题色：6 种预设，点一下立即生效（含桌面小组件）；
 *   * 数据变化后自动刷新桌面小组件（今日课表 / 今日+明日课表）。
 */
public class MainActivity extends Activity {

    private static final String PREF = CourseStore.PREF;
    private static final int REQ_IMPORT_JSON = 1001;

    private static final String[] TERM_NAMES = {"第一学期", "第二学期", "第三学期"};
    private static final String[] TERM_CODES = {"3", "12", "16"};
    private static final String[] WEEKDAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private TextView tvSummary;
    private TextView tvStatus;
    private TextView tvThemeName;
    private Button btnCourseList;
    private Button btnToggleConfig;
    private Button btnImport;
    private Button btnShare;
    private Button btnImportFile;
    private Button btnAddCourse;
    private Button btnVpnImport;
    private Button btnCheckUpdate;
    private Button btnSetCurrentWeek;
    private LinearLayout configCard;
    private LinearLayout weekContainer;
    private LinearLayout themeColors;
    private HorizontalScrollView weekScroll;
    private EditText etBaseUrl;
    private EditText etUsername;
    private EditText etPassword;
    private EditText etCurrentWeek;
    private Spinner spYear;
    private Spinner spTerm;
    private TimetableGridView gridView;

    private Timetable timetable;
    private int selectedWeek = 0;
    private boolean configExpanded = true;
    private final List<TextView> weekChips = new ArrayList<>();
    private final List<Integer> weekValues = new ArrayList<>();

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = CourseStore.prefs(this);

        tvSummary = findViewById(R.id.tvSummary);
        tvStatus = findViewById(R.id.tvStatus);
        tvThemeName = findViewById(R.id.tvThemeName);
        btnCourseList = findViewById(R.id.btnCourseList);
        btnToggleConfig = findViewById(R.id.btnToggleConfig);
        btnImport = findViewById(R.id.btnImport);
        btnShare = findViewById(R.id.btnShare);
        btnImportFile = findViewById(R.id.btnImportFile);
        btnAddCourse = findViewById(R.id.btnAddCourse);
        btnVpnImport = findViewById(R.id.btnVpnImport);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        btnSetCurrentWeek = findViewById(R.id.btnSetCurrentWeek);
        configCard = findViewById(R.id.configCard);
        weekContainer = findViewById(R.id.weekContainer);
        weekScroll = findViewById(R.id.weekScroll);
        themeColors = findViewById(R.id.themeColors);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        etCurrentWeek = findViewById(R.id.etCurrentWeek);
        spYear = findViewById(R.id.spYear);
        spTerm = findViewById(R.id.spTerm);
        gridView = findViewById(R.id.gridView);

        setupSpinners();
        loadPrefs();
        applyTheme();

        gridView.setOnCourseTapListener(this::showCourseDetail);
        // 课表上左右滑动：向左看下一周，向右看上一周
        gridView.setOnWeekSwipeListener(delta -> {
            if (timetable == null) {
                return;
            }
            int maxWeek = Math.max(timetable.maxWeek(), 1);
            int target = selectedWeek <= 0 ? 1 : selectedWeek + delta;
            target = Math.max(1, Math.min(maxWeek, target));
            if (target != selectedWeek) {
                selectWeekAnimated(target, delta);      // 带两页滑动过渡
            }
        });

        btnImport.setOnClickListener(v -> doImport());
        btnShare.setOnClickListener(v -> shareTimetable());
        btnImportFile.setOnClickListener(v -> pickTimetableFile());
        btnVpnImport.setOnClickListener(v -> startVpnImport());
        btnCheckUpdate.setOnClickListener(v -> checkUpdate());
        btnAddCourse.setOnClickListener(v -> showCourseEditor(null));
        btnSetCurrentWeek.setOnClickListener(v -> applyWeekInput());
        btnCourseList.setOnClickListener(v -> showCourseList());
        btnToggleConfig.setOnClickListener(v -> setConfigExpanded(!configExpanded));

        // 先显示上次导入的课表：打开就能看，离线也能看
        Timetable cached = CourseStore.load(this);
        if (cached != null && !cached.courses.isEmpty()) {
            applyTimetable(cached);
            boolean located = selectCurrentWeek(true);
            setConfigExpanded(!located);
            if (!located) {
                setStatus("请在「当前是第 __ 周」里填一下这周是第几周（只填一次，以后自动往后推算）");
                detectWeekInBackground();
            }
        } else {
            setConfigExpanded(true);
            setStatus("请填写学号与密码，然后点「登录并导入课表」（需要连校园网 WiFi）；"
                    + "不在校内的话点「校外导入（学校 VPN）」");
        }

        // 顺带把桌面小组件刷新一次（每天第一次打开时日期就更新了）
        CourseStore.updateWidgets(this);
    }

    // ---------------- 手势：上划收起设置 / 下滑展开设置 ----------------

    private float gestureDownX;
    private float gestureDownY;

    /**
     * 在分发阶段观察手势，不影响输入框、按钮等子控件的正常点击：
     *   * 设置展开时，向上划 → 收起；
     *   * 设置收起时（课表页），向下划 → 展开。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureDownX = event.getX();
                gestureDownY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                float dy = event.getY() - gestureDownY;
                float dx = event.getX() - gestureDownX;
                boolean vertical = Math.abs(dy) > Math.abs(dx) * 1.4f;
                if (vertical) {
                    if (configExpanded && dy < -dp(70)) {
                        setConfigExpanded(false);
                        return true;      // 这一下算"收起设置"，不再触发底下的点击
                    }
                    if (!configExpanded && dy > dp(70)) {
                        setConfigExpanded(true);
                        return true;      // 这一下算"展开设置"
                    }
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    // ---------------- 主题色 ----------------

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void applyTheme() {
        int theme = AppTheme.color(this);
        getWindow().setStatusBarColor(theme);

        btnImport.setBackground(AppTheme.filled(this, theme, 8));
        btnSetCurrentWeek.setBackground(AppTheme.filled(this, theme, 8));

        Button[] secondary = {btnShare, btnImportFile, btnAddCourse, btnVpnImport, btnCourseList, btnToggleConfig};
        for (Button button : secondary) {
            button.setBackground(AppTheme.outlined(this, theme, 8, 1));
            button.setTextColor(theme);
        }
        gridView.setThemeColor(theme);

        if (tvThemeName != null) {
            tvThemeName.setText("当前主题色：" + AppTheme.NAMES[AppTheme.index(this)] + "（点一下圆点即可切换）");
        }
        buildThemeDots();
        refreshChips();
    }

    private void buildThemeDots() {
        if (themeColors == null) {
            return;
        }
        themeColors.removeAllViews();
        int size = dp(26);
        int current = AppTheme.index(this);
        for (int i = 0; i < AppTheme.COLORS.length; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.rightMargin = dp(10);
            dot.setLayoutParams(params);
            dot.setContentDescription(AppTheme.NAMES[i]);

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(AppTheme.COLORS[i]);
            if (i == current) {
                background.setStroke(dp(3), 0xFF3A3A42);
            }
            dot.setBackground(background);

            final int index = i;
            dot.setOnClickListener(v -> {
                AppTheme.setIndex(this, index);
                CourseStore.updateWidgets(this);
                toast("主题色已切换为「" + AppTheme.NAMES[index] + "」");
                recreate();
            });
            themeColors.addView(dot);
        }
    }

    // ---------------- 初始化 ----------------

    private void setupSpinners() {
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        List<String> years = new ArrayList<>();
        List<String> yearValues = new ArrayList<>();
        for (int y = thisYear + 1; y >= thisYear - 6; y--) {
            years.add(y + "-" + (y + 1) + " 学年");
            yearValues.add(String.valueOf(y));
        }
        spYear.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, years));
        spTerm.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, TERM_NAMES));

        Calendar now = Calendar.getInstance();
        int guess = now.get(Calendar.MONTH) + 1 >= 9 ? thisYear : thisYear - 1;
        int index = yearValues.indexOf(String.valueOf(guess));
        if (index >= 0) {
            spYear.setSelection(index);
        }
    }

    private void loadPrefs() {
        etBaseUrl.setText(prefs.getString("base_url", JwglxtClient.DEFAULT_BASE_URL));
        etUsername.setText(prefs.getString("username", ""));
        etPassword.setText(prefs.getString("password", ""));
        etCurrentWeek.setText(prefs.getString("week_input", ""));

        String xnm = prefs.getString("xnm", "");
        if (!xnm.isEmpty()) {
            for (int i = 0; i < spYear.getCount(); i++) {
                if (String.valueOf(spYear.getItemAtPosition(i)).startsWith(xnm + "-")) {
                    spYear.setSelection(i);
                    break;
                }
            }
        }
        String xqm = prefs.getString("xqm", "3");
        for (int i = 0; i < TERM_CODES.length; i++) {
            if (TERM_CODES[i].equals(xqm)) {
                spTerm.setSelection(i);
                break;
            }
        }
    }

    private String selectedYear() {
        String label = String.valueOf(spYear.getSelectedItem());
        int dash = label.indexOf('-');
        return dash > 0 ? label.substring(0, dash)
                : String.valueOf(Calendar.getInstance().get(Calendar.YEAR));
    }

    private String selectedTermCode() {
        int index = Math.max(0, Math.min(TERM_CODES.length - 1, spTerm.getSelectedItemPosition()));
        return TERM_CODES[index];
    }

    private void savePrefs() {
        prefs.edit()
                .putString("base_url", etBaseUrl.getText().toString().trim())
                .putString("username", etUsername.getText().toString().trim())
                .putString("password", etPassword.getText().toString())
                .putString("xnm", selectedYear())
                .putString("xqm", selectedTermCode())
                .apply();
    }

    private void setConfigExpanded(boolean expanded) {
        this.configExpanded = expanded;
        configCard.setVisibility(expanded ? View.VISIBLE : View.GONE);
        btnToggleConfig.setText(expanded ? "收起" : "设置");
    }

    // ---------------- 导入 ----------------

    private void doImport() {
        final String baseUrl = etBaseUrl.getText().toString().trim();
        final String username = etUsername.getText().toString().trim();
        final String password = etPassword.getText().toString();
        final String xnm = selectedYear();
        final String xqm = selectedTermCode();

        if (username.isEmpty() || password.isEmpty()) {
            toast("请先填写学号与密码");
            return;
        }
        savePrefs();
        btnImport.setEnabled(false);
        setStatus("正在登录教务系统并抓取课表…");

        final JwglxtClient client = new JwglxtClient(baseUrl, username, password);
        new Thread(() -> runImport(client, xnm, xqm, "")).start();
    }

    private void runImport(JwglxtClient client, String xnm, String xqm, String captcha) {
        try {
            JwglxtClient.Result result = client.login(captcha);
            if (!result.ok) {
                if (result.message != null && result.message.contains("验证码")) {
                    final byte[] image = client.getCaptchaImage();
                    runOnUiThread(() -> askCaptcha(image, code ->
                            new Thread(() -> runImport(client, xnm, xqm, code)).start()));
                    return;
                }
                finishImport(false, result.message, null);
                return;
            }

            String json = client.fetchTimetableJson(xnm, xqm);
            Timetable parsed = Timetable.parse(json, xnm, xqm);

            // 顺带把教务系统里读到的开学日期存起来：用户没填周次时用它兜底
            String fromServer = client.fetchTermStart(xnm);
            if (fromServer != null && !fromServer.isEmpty()) {
                prefs.edit().putString("term_start", fromServer).apply();
                if (parsed.termStart == null || parsed.termStart.isEmpty()) {
                    parsed.termStart = fromServer;
                    parsed.termStartGuessed = false;
                }
            }

            if (parsed.courses.isEmpty()) {
                finishImport(false, "查询结果为空：这个学期可能没有课，或者学年/学期选错了", null);
                return;
            }
            finishImport(true, "导入成功：" + parsed.courses.size() + " 条课程安排", parsed);
        } catch (Exception e) {
            finishImport(false, "导入失败：" + e.getMessage(), null);
        }
    }

    // ---------------- 检查更新 ----------------

    /** App 当前版本（来自 AndroidManifest 的 versionName） */
    private String appVersion() {
        try {
            return String.valueOf(getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            return "";
        }
    }

    /** 查 GitHub Releases 有没有新版本 */
    private void checkUpdate() {
        final String current = appVersion();
        btnCheckUpdate.setEnabled(false);
        btnCheckUpdate.setText("正在检查…");
        setStatus("正在检查更新（当前 v" + current + "）…");
        new Thread(() -> {
            try {
                final UpdateChecker.Info info = UpdateChecker.fetchLatest();
                final boolean newer = UpdateChecker.isNewer(current, info.tag);
                runOnUiThread(() -> {
                    resetUpdateButton();
                    if (newer) {
                        setStatus("发现新版本 " + info.tag + "（当前 v" + current + "）");
                        showNewVersionDialog(info, current);
                    } else {
                        setStatus("已是最新版本（v" + current + "）");
                        toast("已是最新版本 v" + current);
                    }
                });
            } catch (Exception e) {
                final String reason = e.getMessage() == null ? "网络不通" : e.getMessage();
                runOnUiThread(() -> {
                    resetUpdateButton();
                    setStatus("检查更新失败：" + reason);
                    showUpdateFailedDialog(reason);
                });
            }
        }).start();
    }

    private void resetUpdateButton() {
        if (btnCheckUpdate != null) {
            btnCheckUpdate.setEnabled(true);
            btnCheckUpdate.setText("检查更新");
        }
    }

    private void showNewVersionDialog(UpdateChecker.Info info, String current) {
        String notes = info.notes == null ? "" : info.notes.trim();
        if (notes.length() > 1200) {
            notes = notes.substring(0, 1200) + "…";
        }
        String message = notes.isEmpty()
                ? "最新版本 " + info.tag + "（当前 v" + current + "）"
                : notes;
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + info.tag)
                .setMessage(message)
                .setPositiveButton("去下载", (dialog, which) -> openUrl(info.downloadUrl))
                .setNeutralButton("复制链接", (dialog, which) -> copyText(info.downloadUrl))
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void showUpdateFailedDialog(String reason) {
        new AlertDialog.Builder(this)
                .setTitle("检查更新失败")
                .setMessage(reason + "\n\n国内网络访问 GitHub 有时不通，"
                        + "可以用浏览器手动打开下载页看看有没有新版本。")
                .setPositiveButton("打开下载页", (dialog, which) -> openUrl(UpdateChecker.RELEASES_PAGE))
                .setNegativeButton("知道了", null)
                .show();
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("打不开浏览器，请手动访问：" + url);
        }
    }

    private void copyText(String text) {
        try {
            android.content.ClipboardManager manager =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            manager.setPrimaryClip(android.content.ClipData.newPlainText("下载链接", text));
            toast("链接已复制");
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    // ---------------- 校外导入（学校 SSL VPN）----------------

    private static final int REQ_VPN_LOGIN = 1002;

    /** 打开学校 VPN 登录页：校外也能用，不需要连校园网 */
    private void startVpnImport() {
        String baseUrl = etBaseUrl.getText().toString().trim();
        if (baseUrl.isEmpty()) {
            baseUrl = JwglxtClient.DEFAULT_BASE_URL;
        }
        if (VpnGateway.toGatewayUrl(baseUrl).isEmpty()) {
            toast("教务系统地址无法换算成校外地址，请检查设置里的地址");
            return;
        }
        savePrefs();
        setStatus("正在打开学校 VPN：登录成功后会自动取课表");
        Intent intent = new Intent(this, VpnImportActivity.class);
        intent.putExtra(VpnImportActivity.EXTRA_BASE_URL, baseUrl);
        intent.putExtra(VpnImportActivity.EXTRA_XNM, selectedYear());
        intent.putExtra(VpnImportActivity.EXTRA_XQM, selectedTermCode());
        startActivityForResult(intent, REQ_VPN_LOGIN);
    }

    /**
     * 校外导入返回：课表 JSON 是 WebView 在**页面内部**取到的（同源请求，会话由浏览器带上），
     * 这里只管解析和保存。
     */
    private void handleVpnResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            setStatus("已取消校外导入");
            return;
        }
        final String json = data.getStringExtra(VpnImportActivity.EXTRA_RESULT_JSON);
        if (json == null || json.trim().isEmpty()) {
            setStatus("校外导入失败：没有拿到课表数据，可再点一次「校外导入」重试");
            return;
        }

        final String xnm = selectedYear();
        final String xqm = selectedTermCode();
        btnImport.setEnabled(false);
        setStatus("正在整理课表…");
        new Thread(() -> {
            try {
                Timetable parsed = Timetable.parse(json, xnm, xqm);
                if (parsed.courses.isEmpty()) {
                    finishImport(false, "校外导入：查询结果为空，可能是学年/学期选错了", null);
                    return;
                }
                finishImport(true, "校外导入成功：" + parsed.courses.size() + " 条课程安排", parsed);
            } catch (Exception e) {
                finishImport(false, "校外导入失败：" + e.getMessage(), null);
            }
        }).start();
    }

    /** 弹验证码输入框 */
    private void askCaptcha(byte[] png, java.util.function.Consumer<String> onCode) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));

        if (png != null && png.length > 0) {
            android.widget.ImageView image = new android.widget.ImageView(this);
            image.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length));
            image.setAdjustViewBounds(true);
            box.addView(image);
        }

        final EditText input = new EditText(this);
        input.setHint("请输入图中的验证码");
        box.addView(input);

        setStatus("登录失败次数较多，需要输入验证码");
        new AlertDialog.Builder(this)
                .setTitle("需要验证码")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("确定", (dialog, which) -> {
                    setStatus("正在重试登录…");
                    onCode.accept(input.getText().toString().trim());
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    setStatus("已取消导入");
                    btnImport.setEnabled(true);
                })
                .show();
    }

    // ---------------- 从文件导入 ----------------

    private void pickTimetableFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT_JSON);
        } catch (Exception e) {
            toast("这台手机上没找到可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 校外导入：学校 VPN 登录页回来了
        if (requestCode == REQ_VPN_LOGIN) {
            handleVpnResult(resultCode, data);
            return;
        }

        if (requestCode != REQ_IMPORT_JSON || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        setStatus("正在读取文件…");
        new Thread(() -> {
            try {
                byte[] bytes = readBytesFile(uri);
                Timetable imported;
                if (isPdf(bytes)) {
                    setStatus("正在解析课表 PDF…");
                    imported = TimetablePdfParser.parse(bytes, selectedYear(), selectedTermCode());
                } else {
                    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    // 先按本 App 导出的格式读，再兼容正方接口原始返回的 kbList
                    imported = Timetable.fromJson(text);
                    if (imported == null || imported.courses.isEmpty()) {
                        try {
                            imported = Timetable.parse(text, selectedYear(), selectedTermCode());
                        } catch (Exception ignored) {
                            imported = null;
                        }
                    }
                }
                if (imported == null || imported.courses.isEmpty()) {
                    finishImport(false, "文件里没有解析出课程：支持本 App 导出的 JSON，"
                            + "以及教务系统导出的课表 PDF", null);
                    return;
                }
                String kind = isPdf(bytes) ? "PDF" : "JSON";
                finishImport(true, "从 " + kind + " 导入成功：" + imported.courses.size() + " 条课程安排",
                        imported);
            } catch (Exception e) {
                finishImport(false, "读取文件失败：" + e.getMessage(), null);
            }
        }).start();
    }

    private static boolean isPdf(byte[] bytes) {
        if (bytes == null || bytes.length < 5) {
            return false;
        }
        return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    private byte[] readBytesFile(android.net.Uri uri) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new Exception("打不开这个文件");
        }
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int len;
        while ((len = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, len);
        }
        in.close();
        return buffer.toByteArray();
    }

    private void finishImport(final boolean ok, final String message, final Timetable parsed) {
        runOnUiThread(() -> {
            btnImport.setEnabled(true);
            if (ok && parsed != null) {
                parsed.keepCustomFrom(timetable);      // 自建课程（旁听等）不会被导入冲掉
                timetable = parsed;
                CourseStore.save(this, parsed);
                CourseStore.updateWidgets(this);
                applyTimetable(parsed);
                boolean located = selectCurrentWeek(true);
                setConfigExpanded(!located);
                toast("课表导入成功");
            } else {
                setStatus(message);
                toast(message);
            }
        });
    }

    // ---------------- 展示 ----------------

    private void applyTimetable(Timetable timetable) {
        this.timetable = timetable;
        gridView.setTimetable(timetable);
        gridView.setTodayWeekday(todayWeekday());
        gridView.setThemeColor(AppTheme.color(this));
        buildWeekChips(timetable);
        updateSummary();
    }

    private int todayWeekday() {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return day == Calendar.SUNDAY ? 7 : day - 1;
    }

    private void buildWeekChips(Timetable timetable) {
        weekContainer.removeAllViews();
        weekChips.clear();
        weekValues.clear();

        int maxWeek = Math.max(timetable.maxWeek(), Math.max(computeCurrentWeek(), 1));
        addChip("全部", 0);
        for (int week = 1; week <= maxWeek; week++) {
            addChip("第" + week + "周", week);
        }
        refreshChips();
    }

    private void addChip(String label, final int week) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(6);
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> selectWeekAnimated(week, Integer.compare(week, selectedWeek)));
        weekContainer.addView(chip);
        weekChips.add(chip);
        weekValues.add(week);
    }

    private void selectWeek(int week) {
        selectedWeek = week;
        gridView.setWeek(week);
        refreshChips();
        updateSummary();
        scrollToSelectedChip();
    }

    /** 切周并播放"两页滑动"过渡动画：direction +1 下一周、-1 上一周 */
    private void selectWeekAnimated(int week, int direction) {
        if (timetable == null || week == selectedWeek) {
            selectWeek(week);
            return;
        }
        selectedWeek = week;
        gridView.setWeekAnimated(week, direction);
        refreshChips();
        updateSummary();
        scrollToSelectedChip();
    }

    private void refreshChips() {
        if (weekChips.isEmpty()) {
            return;
        }
        int theme = AppTheme.color(this);
        for (int i = 0; i < weekChips.size(); i++) {
            TextView chip = weekChips.get(i);
            boolean active = weekValues.get(i) == selectedWeek;
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(16));
            if (active) {
                background.setColor(theme);
                chip.setTextColor(Color.WHITE);
            } else {
                background.setColor(Color.parseColor("#F1F1F5"));
                chip.setTextColor(Color.parseColor("#3A3A42"));
            }
            chip.setBackground(background);
        }
    }

    private void scrollToSelectedChip() {
        int index = weekValues.indexOf(selectedWeek);
        if (index < 0 || index >= weekChips.size()) {
            return;
        }
        final View chip = weekChips.get(index);
        weekScroll.post(() -> {
            int target = chip.getLeft() - (weekScroll.getWidth() - chip.getWidth()) / 2;
            weekScroll.smoothScrollTo(Math.max(0, target), 0);
        });
    }

    // ---------------- 本周定位 ----------------

    private int computeCurrentWeek() {
        return CourseStore.currentWeek(this, timetable);
    }

    private boolean selectCurrentWeek(boolean showStatus) {
        if (timetable == null) {
            return false;
        }
        int week = computeCurrentWeek();
        if (week <= 0) {
            selectWeek(0);
            if (showStatus) {
                setStatus("还判断不出今天是第几周：请在下面填一下「当前是第几周」，只填一次");
            }
            return false;
        }
        selectWeek(week);
        etCurrentWeek.setText(String.valueOf(week));
        if (showStatus) {
            setStatus("已定位到第 " + week + " 周（以后每次打开都会自动往后推算）");
        }
        return true;
    }

    private void setWeekAnchor(int week) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        prefs.edit()
                .putInt("week_anchor_week", week)
                .putString("week_anchor_date", today)
                .apply();
    }

    private void applyWeekInput() {
        String text = etCurrentWeek.getText().toString().trim();
        if (text.isEmpty()) {
            toast("请先填当前是第几周");
            return;
        }
        int week;
        try {
            week = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            toast("周次要填数字，比如 6");
            return;
        }
        if (week < 1 || week > 40) {
            toast("周次应该在 1~40 之间");
            return;
        }
        setWeekAnchor(week);
        prefs.edit().putString("week_input", String.valueOf(week)).apply();
        CourseStore.updateWidgets(this);
        if (timetable == null) {
            toast("已记住第 " + week + " 周，导入课表后就会按这个基准显示");
            return;
        }
        selectCurrentWeek(true);
        setConfigExpanded(false);
        toast("已定位到第 " + week + " 周，以后会自动往后推算");
    }

    /** 用户没填周次时的兜底：后台问一次教务系统的开学日期 */
    private void detectWeekInBackground() {
        if (prefs.getInt("week_anchor_week", 0) > 0) {
            return;
        }
        final String baseUrl = etBaseUrl.getText().toString().trim();
        final String username = etUsername.getText().toString().trim();
        final String password = etPassword.getText().toString();
        final String xnm = selectedYear();
        if (username.isEmpty() || password.isEmpty()) {
            return;
        }
        new Thread(() -> {
            String termStart = "";
            try {
                JwglxtClient client = new JwglxtClient(baseUrl, username, password);
                JwglxtClient.Result result = client.login("");
                if (result.ok) {
                    termStart = client.fetchTermStart(xnm);
                }
            } catch (Exception ignored) {
            }
            final String detected = termStart;
            if (detected.isEmpty()) {
                return;
            }
            runOnUiThread(() -> {
                prefs.edit().putString("term_start", detected).apply();
                if (timetable != null) {
                    timetable.termStart = detected;
                    timetable.termStartGuessed = false;
                    CourseStore.save(this, timetable);
                }
                selectCurrentWeek(true);
                CourseStore.updateWidgets(this);
            });
        }).start();
    }

    // ---------------- 自建课程 ----------------

    /** 添加（existing = null）或编辑一门自建课程 */
    private void showCourseEditor(final Course existing) {
        if (timetable == null) {
            toast("请先导入一次课表，再加自建课程");
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(8));

        final EditText etName = new EditText(this);
        etName.setHint("例如：旁听 · 数据结构");
        etName.setText(existing == null ? "" : existing.name);
        form.addView(hint("课程名称"));
        form.addView(etName);

        final EditText etTeacher = new EditText(this);
        etTeacher.setHint("可不填");
        etTeacher.setText(existing == null ? "" : existing.teacher);
        form.addView(hint("教师"));
        form.addView(etTeacher);

        final EditText etPlace = new EditText(this);
        etPlace.setHint("可不填");
        etPlace.setText(existing == null ? "" : existing.place);
        form.addView(hint("地点"));
        form.addView(etPlace);

        final Spinner spDay = new Spinner(this);
        spDay.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, WEEKDAY_NAMES));
        if (existing != null && existing.weekday >= 1 && existing.weekday <= 7) {
            spDay.setSelection(existing.weekday - 1);
        }
        form.addView(hint("星期"));
        form.addView(spDay);

        LinearLayout nodeRow = new LinearLayout(this);
        nodeRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText etStart = new EditText(this);
        etStart.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etStart.setText(String.valueOf(existing == null ? 1 : Math.max(1, existing.startNode)));
        final EditText etEnd = new EditText(this);
        etEnd.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etEnd.setText(String.valueOf(existing == null ? 2 : Math.max(1, existing.endNode)));
        nodeRow.addView(etStart, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView dash = new TextView(this);
        dash.setText(" 到 ");
        nodeRow.addView(dash);
        nodeRow.addView(etEnd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        form.addView(hint("节次（第几节到第几节）"));
        form.addView(nodeRow);

        final EditText etWeeks = new EditText(this);
        etWeeks.setHint("留空=每周；也可写 1-16 或 1-16周(单)");
        etWeeks.setText(existing == null ? "" : existing.weekText);
        form.addView(hint("周次"));
        form.addView(etWeeks);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加自建课程（旁听等）" : "编辑自建课程")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                toast("请填课程名称");
                return;
            }
            int start = parseInt(etStart.getText().toString(), 1);
            int end = parseInt(etEnd.getText().toString(), 2);
            start = Math.max(1, Math.min(12, start));
            end = Math.max(start, Math.min(12, end));

            Course course = new Course();
            course.name = name;
            course.teacher = etTeacher.getText().toString().trim();
            course.place = etPlace.getText().toString().trim();
            course.weekday = spDay.getSelectedItemPosition() + 1;
            course.startNode = start;
            course.endNode = end;
            course.nodeText = (start == end) ? String.valueOf(start) : (start + "-" + end);
            course.weekText = etWeeks.getText().toString().trim();
            course.weeks = Timetable.parseWeeks(course.weekText);
            course.custom = true;

            if (existing == null) {
                CourseStore.addCustomCourse(this, timetable, course);
            } else {
                CourseStore.replaceCustomCourse(this, timetable, existing, course);
            }
            dialog.dismiss();
            applyTimetable(timetable);
            selectCurrentWeek(false);
            toast(existing == null ? "已添加自建课程" : "已修改自建课程");
        });
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTextColor(Color.parseColor("#6B6B73"));
        view.setPadding(0, dp(6), 0, 0);
        return view;
    }

    /** 对自建课程的操作菜单 */
    private void showCustomCourseActions(final Course course) {
        new AlertDialog.Builder(this)
                .setTitle(course.name + "（自建）")
                .setItems(new String[]{"编辑", "删除"}, (dialog, which) -> {
                    if (which == 0) {
                        showCourseEditor(course);
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("删除这门自建课程？")
                                .setMessage(course.name + "｜" + course.weekdayName()
                                        + " 第" + course.nodeText + "节")
                                .setPositiveButton("删除", (d, w) -> {
                                    CourseStore.removeCustomCourse(this, timetable, course);
                                    applyTimetable(timetable);
                                    selectCurrentWeek(false);
                                    toast("已删除");
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                })
                .show();
    }

    private void updateSummary() {
        if (timetable == null) {
            tvSummary.setText("");
            return;
        }
        int shown = 0;
        for (Course c : timetable.courses) {
            if (c.matchesWeek(selectedWeek)) {
                shown++;
            }
        }
        String scope;
        if (selectedWeek == 0) {
            scope = "全部周";
        } else {
            String range = timetable.weekDateRange(selectedWeek);
            scope = "第 " + selectedWeek + " 周" + (range.isEmpty() ? "" : "（" + range + "）");
        }
        String who = timetable.student.isEmpty() ? "" : (timetable.student + " · ");
        tvSummary.setText(who + timetable.termText()
                + " · " + timetable.courseCount() + " 门课"
                + " · " + scope + " " + shown + " 条");
    }

    private void setStatus(String text) {
        tvStatus.setText(text);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    // ---------------- 详情 / 列表 ----------------

    private void showCourseDetail(List<Course> courses, int weekday, int node) {
        StringBuilder sb = new StringBuilder();
        Course custom = null;
        for (Course c : courses) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(c.name).append(c.custom ? "（自建）" : "").append('\n');
            sb.append("时间：").append(c.weekdayName()).append(" 第").append(c.nodeText).append(" 节");
            String time = timetable == null ? "" : timetable.courseTimeText(c);
            if (!time.isEmpty()) {
                sb.append("（").append(time).append("）");
            }
            sb.append('\n');
            sb.append("地点：").append(c.place.isEmpty() ? "未填写" : c.place).append('\n');
            sb.append("教师：").append(c.teacher.isEmpty() ? "未填写" : c.teacher).append('\n');
            sb.append("周次：").append(c.weeksDisplay());
            if (custom == null && c.custom) {
                custom = c;
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("课程详情")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null);
        if (custom != null) {
            final Course editable = custom;
            builder.setNeutralButton("编辑/删除", (dialog, which) -> showCustomCourseActions(editable));
        }
        builder.show();
    }

    private void showCourseList() {
        if (timetable == null || timetable.courses.isEmpty()) {
            toast("还没有课表，请先导入");
            return;
        }
        List<Course> sorted = new ArrayList<>(timetable.courses);
        Collections.sort(sorted, new Comparator<Course>() {
            @Override
            public int compare(Course a, Course b) {
                if (a.weekday != b.weekday) {
                    return a.weekday - b.weekday;
                }
                return a.startNode - b.startNode;
            }
        });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        for (Course c : sorted) {
            box.addView(makeCourseRow(c));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("全部课程（" + timetable.courseCount() + " 门）")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private TextView makeCourseRow(final Course c) {
        TextView row = new TextView(this);
        StringBuilder sb = new StringBuilder();
        sb.append(c.weekdayName()).append("  第").append(c.nodeText).append(" 节");
        String time = timetable == null ? "" : timetable.courseTimeText(c);
        if (!time.isEmpty()) {
            sb.append("  ").append(time);
        }
        sb.append('\n').append(c.name).append(c.custom ? "（自建）" : "");
        if (!c.place.isEmpty()) {
            sb.append("  @").append(c.place);
        }
        if (!c.teacher.isEmpty()) {
            sb.append("  ").append(c.teacher);
        }
        sb.append('\n').append("周次：").append(c.weeksDisplay());
        row.setText(sb.toString());
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.setTextColor(Color.parseColor("#1F1F23"));
        row.setLineSpacing(0, 1.2f);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), c.custom ? AppTheme.color(this) : Color.parseColor("#E3E3E8"));
        row.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        row.setLayoutParams(params);

        if (c.custom) {
            row.setOnClickListener(v -> showCustomCourseActions(c));
        }
        return row;
    }

    // ---------------- 分享 ----------------

    private void shareTimetable() {
        if (timetable == null) {
            toast("还没有课表可以分享");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("我的课表 · ").append(timetable.termText());
        if (selectedWeek > 0) {
            sb.append("（第 ").append(selectedWeek).append(" 周）");
        }
        sb.append('\n');
        String lastDay = "";
        for (Course c : timetable.courses) {
            if (!c.matchesWeek(selectedWeek)) {
                continue;
            }
            if (!c.weekdayName().equals(lastDay)) {
                lastDay = c.weekdayName();
                sb.append('\n').append("【").append(lastDay).append("】\n");
            }
            sb.append("  第").append(c.nodeText).append(" 节 ").append(c.name);
            if (c.custom) {
                sb.append("（自建）");
            }
            if (!c.place.isEmpty()) {
                sb.append(" @").append(c.place);
            }
            if (!c.teacher.isEmpty()) {
                sb.append(" ").append(c.teacher);
            }
            sb.append('\n');
        }
        sb.append("\n导出时间：")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date()));

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "我的课表");
        intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(intent, "分享课表"));
    }
}
