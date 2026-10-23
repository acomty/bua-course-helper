package com.bua.coursehelper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 校外导入：用学校自己的校外通道（网页版 WebVPN 或 SSL VPN 网关）登录一次，
 * 然后**在网页内部**请求课表接口。
 *
 * 为什么在网页里发请求：
 *   把 WebView 的 Cookie 搬到 App 的网络层并不成功——网关会把没带会话的请求跳回门户，
 *   于是报"返回的是网页，不是数据"。在页面内用 fetch（同源）则由浏览器自己带会话，
 *   网关看到的是普通浏览器请求。
 *
 * 为什么要能切换入口：
 *   SSL VPN 网关的"网页模式"实测对教务系统返回 403（网关不放行），
 *   所以默认走学校推荐校外使用的网页版 WebVPN，同时保留网关入口备用。
 *
 * 登录页是学校自己的页面，密码由它处理，App 不接触、不保存。
 */
public class VpnImportActivity extends Activity {

    public static final String EXTRA_BASE_URL = "base_url";
    public static final String EXTRA_XNM = "xnm";
    public static final String EXTRA_XQM = "xqm";
    public static final String EXTRA_RESULT_JSON = "result_json";
    public static final String EXTRA_PAGE_URL = "page_url";
    public static final String EXTRA_COOKIES = "cookies";

    private static final String ENTRY_WEBVPN = "webvpn";
    private static final String ENTRY_GATEWAY = "gateway";

    private static final String NOT_LOGGED_IN = "NOT_LOGGED_IN";
    private static final String FORBIDDEN = "FORBIDDEN";

    private WebView webView;
    private TextView statusView;
    private Button importButton;
    private Button entryButton;

    private String entry = ENTRY_WEBVPN;
    private String campusBase = "";
    private String gatewayBase = "";
    private String xnm = "";
    private String xqm = "3";
    private boolean fetching = false;
    private boolean autoTried = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        campusBase = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_BASE_URL);
        if (campusBase == null || campusBase.trim().isEmpty()) {
            campusBase = JwglxtClient.DEFAULT_BASE_URL;
        }
        xnm = getIntent() == null ? "" : String.valueOf(getIntent().getStringExtra(EXTRA_XNM));
        xqm = getIntent() == null ? "3" : String.valueOf(getIntent().getStringExtra(EXTRA_XQM));
        gatewayBase = VpnGateway.toGatewayUrl(campusBase);

        setContentView(buildLayout());

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (VpnGateway.isTimetablePage(url)) {
                    importButton.setEnabled(true);
                    if (!autoTried) {
                        autoTried = true;
                        setStatus("已进入教务系统，正在取课表…");
                        fetchTimetable();
                    } else {
                        setStatus("已进入教务系统，点「导入课表」即可");
                    }
                } else {
                    setStatus(hintFor(entry));
                }
            }
        });

        loadEntry();
    }

    // ---------------- 界面 ----------------

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(12), dp(12), dp(2));

        TextView title = new TextView(this);
        title.setText("校外导入");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        entryButton = new Button(this);
        entryButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        entryButton.setOnClickListener(v -> {
            entry = ENTRY_WEBVPN.equals(entry) ? ENTRY_GATEWAY : ENTRY_WEBVPN;
            autoTried = false;
            importing = false;
            importButton.setEnabled(false);
            loadEntry();
        });
        head.addView(entryButton);
        root.addView(head);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setTextColor(Color.parseColor("#6B6B73"));
        statusView.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(statusView);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(12));

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        bar.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        importButton = new Button(this);
        importButton.setText("导入课表");
        // 始终可点：学校门户的课表是在 iframe 里打开的，整页跳转回调可能等不到，
        // 让用户随时可以手动触发（脚本会自己找课表接口）。
        importButton.setEnabled(true);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        importParams.setMarginStart(dp(8));
        importButton.setOnClickListener(v -> fetchTimetable());
        bar.addView(importButton, importParams);

        root.addView(bar);
        return root;
    }

    private boolean importing = false;

    private void loadEntry() {
        boolean webvpn = ENTRY_WEBVPN.equals(entry);
        entryButton.setText(webvpn ? "入口：网页版 WebVPN" : "入口：SSL VPN 网关");
        String url = webvpn ? VpnGateway.WEBVPN_PORTAL : gatewayBase;
        if (url == null || url.isEmpty()) {
            setStatus("这个教务系统地址换不成校外地址，请检查设置里的地址");
            return;
        }
        setStatus(hintFor(entry));
        importing = false;
        if (webView != null) {
            webView.loadUrl(url);
        }
    }

    private String hintFor(String which) {
        if (ENTRY_WEBVPN.equals(which)) {
            return "请登录学校 WebVPN → 打开「个人课表查询」→ 看到课表后点下面「导入课表」"
                    + "（不用等它自动，随时可以点）";
        }
        return "请登录学校 SSL VPN；若提示 403，说明网关不放行，请切到网页版 WebVPN";
    }

    private void setStatus(final String text) {
        runOnUiThread(() -> {
            if (statusView != null) {
                statusView.setText(text);
            }
        });
    }

    // ---------------- 取课表 ----------------

    private void fetchTimetable() {
        if (importing || webView == null) {
            return;
        }
        importing = true;
        setStatus("正在通过学校校外通道获取课表…");
        webView.evaluateJavascript(buildFetchScript(xnm, xqm), null);
    }

    /**
     * 生成页面内取课表的 JS。
     *
     * 难点：学校门户是"我的应用"式页面，课表查询是**在 iframe 内部**打开的，
     * 这时顶层页面的地址里没有 /jwglxt，只有 iframe 里有。所以这里不猜一个地址，
     * 而是**收集所有候选前缀**（顶层路径、各个 iframe 的路径、页面里 iframe/a 的 src/href），
     * 逐个 POST 试探，谁的响应是 JSON 就用谁。
     *
     *   SSL VPN 网关：/jwglxt/...                        → 前缀 ""
     *   WebVPN：      /https/xxxx/jwglxt/...             → 前缀 "/https/xxxx"
     *   iframe 场景：  课表在 frames[i] 里                 → 从 frames[i].location.pathname 取
     */
    static String buildFetchScript(String xnm, String xqm) {
        return "(function(){"
                + "var list=[];"
                + "function add(p){if(!p){return;}var i=p.indexOf('/jwglxt');"
                + "if(i<0){return;}var pre=p.substring(0,i);if(list.indexOf(pre)<0){list.push(pre);}}"
                + "try{add(location.pathname);}catch(e){}"
                + "try{for(var f=0;f<frames.length;f++){try{add(frames[f].location.pathname);}catch(e){}}}catch(e){}"
                + "try{var ns=document.querySelectorAll('iframe,a');"
                + "for(var k=0;k<ns.length;k++){"
                + "add(ns[k].getAttribute('src')||'');add(ns[k].getAttribute('href')||'');}}catch(e){}"
                + "if(list.indexOf('')<0){list.push('');}"
                + "var idx=0;"
                + "function tryNext(){"
                + "if(idx>=list.length){Android.onError('" + NOT_LOGGED_IN + "');return;}"
                + "var url=list[idx++]+'/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151';"
                + "var body=new FormData();"
                + "body.append('xnm','" + xnm + "');"
                + "body.append('xqm','" + xqm + "');"
                + "fetch(url,{method:'POST',body:body,credentials:'include'})"
                + ".then(function(r){return r.text();})"
                + ".then(function(t){"
                + "var s=t.replace(/^\\s+/,'');"
                + "if(s.indexOf('{')===0||t.indexOf('kbList')>=0){Android.onResult(t);}"
                + "else if(t.indexOf('Forbidden')>=0){Android.onError('" + FORBIDDEN + "');}"
                + "else{tryNext();}"
                + "}).catch(function(){tryNext();});"
                + "}"
                + "tryNext();"
                + "})();";
    }

    private String collectCookies() {
        try {
            CookieManager cookies = CookieManager.getInstance();
            StringBuilder all = new StringBuilder();
            append(all, cookies.getCookie(gatewayBase));
            append(all, cookies.getCookie(VpnGateway.WEBVPN_PORTAL));
            append(all, cookies.getCookie(VpnGateway.PORTAL));
            return all.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void append(StringBuilder out, String header) {
        if (header == null || header.trim().isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append("; ");
        }
        out.append(header.trim());
    }

    /** 网页里回调用的桥（回调在后台线程，要转回主线程） */
    private class Bridge {
        @JavascriptInterface
        public void onResult(String json) {
            importing = false;
            final String payload = json;
            runOnUiThread(() -> {
                Intent data = new Intent();
                data.putExtra(EXTRA_RESULT_JSON, payload);
                data.putExtra(EXTRA_PAGE_URL, webView == null ? "" : String.valueOf(webView.getUrl()));
                data.putExtra(EXTRA_COOKIES, collectCookies());
                setResult(RESULT_OK, data);
                finish();
            });
        }

        @JavascriptInterface
        public void onError(String message) {
            importing = false;
            final String reason = String.valueOf(message);
            runOnUiThread(() -> {
                if (NOT_LOGGED_IN.equals(reason)) {
                    setStatus("没找到课表接口：请先在页面里打开「个人课表查询」并点「查询」看到课表，"
                            + "再点「导入课表」；还不行就用「从文件导入」");
                } else if (FORBIDDEN.equals(reason)) {
                    setStatus("学校网关拒绝代理教务系统（403）：请点右上角切到「网页版 WebVPN」再试；"
                            + "两条路都不行就用「从文件导入」");
                } else {
                    setStatus("取课表失败：" + reason + "（可点「导入课表」重试）");
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
