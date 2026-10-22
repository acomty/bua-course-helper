package com.bua.coursehelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 检查 GitHub Releases 上有没有新版本。不依赖任何第三方库。
 *
 * 注意：国内网络访问 api.github.com 可能不通，所以调用方必须处理好失败情况
 * （失败时引导用户手动打开 releases 页面）。
 */
public class UpdateChecker {

    public static final String REPO = "acomty/bua-course-helper";
    public static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";

    /** 一次检查的结果 */
    public static class Info {
        public final String tag;        // 如 v3.7
        public final String notes;      // 更新说明（release body）
        public final String downloadUrl;// apk 附件地址；没有附件时退回 release 页面
        public final String pageUrl;    // release 页面

        public Info(String tag, String notes, String downloadUrl, String pageUrl) {
            this.tag = tag == null ? "" : tag;
            this.notes = notes == null ? "" : notes;
            this.downloadUrl = downloadUrl == null || downloadUrl.isEmpty() ? RELEASES_PAGE : downloadUrl;
            this.pageUrl = pageUrl == null || pageUrl.isEmpty() ? RELEASES_PAGE : pageUrl;
        }
    }

    /** 拉取最新 release；失败抛异常（HTTP 403 一般是访问太频繁） */
    public static Info fetchLatest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_API).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "CourseHelper-Android");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            return parseRelease(readAll(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    /** 解析 release JSON（单独抽出来方便测试） */
    static Info parseRelease(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String tag = root.optString("tag_name", "");
        String notes = root.optString("body", "");
        String pageUrl = root.optString("html_url", RELEASES_PAGE);

        String apkUrl = "";
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");
                if (name.toLowerCase().endsWith(".apk") && !url.isEmpty()) {
                    apkUrl = url;
                    break;
                }
            }
        }
        return new Info(tag, notes, apkUrl, pageUrl);
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 去掉开头的 v/V 和空格 */
    static String normalize(String version) {
        if (version == null) {
            return "0";
        }
        String text = version.trim();
        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1);
        }
        return text.isEmpty() ? "0" : text;
    }

    /**
     * 比较版本号（"3.6" / "3.6.1" / "v3.7"）：
     * 返回 &gt;0 表示 a 比 b 新，0 相等，&lt;0 表示更旧。
     */
    public static int compareVersions(String a, String b) {
        String[] left = normalize(a).split("[^0-9]+");
        String[] right = normalize(b).split("[^0-9]+");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int x = numberAt(left, i);
            int y = numberAt(right, i);
            if (x != y) {
                return x > y ? 1 : -1;
            }
        }
        return 0;
    }

    private static int numberAt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 远端版本是否比本地新 */
    public static boolean isNewer(String localVersion, String remoteTag) {
        return compareVersions(remoteTag, localVersion) > 0;
    }
}
