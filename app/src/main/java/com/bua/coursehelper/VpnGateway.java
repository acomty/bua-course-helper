package com.bua.coursehelper;

import java.net.URL;
import java.net.URLEncoder;

/**
 * 学校 SSL VPN（Array 网关）的地址改写规则。
 *
 * 背景：教务系统只在校内网（10.98.49.75），校外直连会拿到一个"请在校内访问"的提示页。
 * 但学校提供了 SSL VPN，它会把内网服务反向代理到公网域名上，规则是：
 *
 *     jwglxt.bua.edu.cn   →   jwglxt-bua-edu-cn-s.vpn.bua.edu.cn:8118
 *     cas.bua.edu.cn      →   cas-bua-edu-cn-s.vpn.bua.edu.cn:8118
 *     （域名里的点换成横线，后面接 -s.vpn.bua.edu.cn，端口 8118，走 http）
 *
 * 未登录时访问网关地址会 302 跳到 https://vpn.bua.edu.cn/?redirect_uri=原地址，
 * 登录后自动跳回原地址 —— 也就是说"登录一次，教务系统就在校外可访问了"。
 * 这个类只做地址换算，不发任何请求。
 */
public class VpnGateway {

    /** VPN 门户（校外可访问） */
    public static final String PORTAL = "https://vpn.bua.edu.cn/";

    /** 网页版 WebVPN（学校推荐校外访问用，专门做网页代理） */
    public static final String WEBVPN_PORTAL = "https://webvpn.bua.edu.cn/";
    public static final String WEBVPN_HOST = "webvpn.bua.edu.cn";

    /** 网关端口与后缀 */
    public static final int GATEWAY_PORT = 8118;
    public static final String GATEWAY_SUFFIX = "-s.vpn.bua.edu.cn";

    /**
     * 把校内地址换算成校外可用的网关地址。
     * 已经是网关地址、或网址不合法时，原样返回（空串表示无法换算）。
     */
    public static String toGatewayUrl(String campusUrl) {
        if (campusUrl == null || campusUrl.trim().isEmpty()) {
            return "";
        }
        String url = campusUrl.trim();
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost();
            if (host == null || host.isEmpty()) {
                return "";
            }
            String lower = host.toLowerCase();
            if (lower.endsWith(GATEWAY_SUFFIX)) {
                return url;                        // 已经是网关地址
            }
            if (!lower.endsWith(".bua.edu.cn") && !lower.equals("bua.edu.cn")) {
                return "";                          // 只处理本校域名
            }
            String path = parsed.getPath() == null ? "/" : parsed.getPath();
            String query = parsed.getQuery() == null ? "" : "?" + parsed.getQuery();
            return "http://" + host.replace('.', '-') + GATEWAY_SUFFIX + ":" + GATEWAY_PORT + path + query;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 判断 WebView 当前地址是不是"已经穿过网关"（说明 VPN 登录成功）。
     *
     * 注意：VPN 门户本身（vpn.bua.edu.cn，或带 ?redirect_uri= 的登录页）不算通过，
     * 只有落到 xxx-s.vpn.bua.edu.cn 上的校内资源才算。
     */
    public static boolean isThroughGateway(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        try {
            String host = new URL(url.trim()).getHost();
            return host != null && host.toLowerCase().endsWith(GATEWAY_SUFFIX);
        } catch (Exception e) {
            return false;
        }
    }

    /** 门户登录地址（把目标地址作为 redirect_uri 带过去），登录后会自动跳回目标 */
    public static String portalLoginUrl(String campusUrl) {
        String target = toGatewayUrl(campusUrl);
        if (target.isEmpty()) {
            return PORTAL;
        }
        try {
            return PORTAL + "?redirect_uri=" + URLEncoder.encode(target, "UTF-8");
        } catch (Exception e) {
            return PORTAL;
        }
    }

    /**
     * 判断是不是已经进到教务系统页面了（而不是还停在 VPN 门户或登录页）。
     * 只有进到教务系统页面，页面内发起的取课表请求才会带上学籍会话。
     *
     * 两种校外入口都算：
     *   * SSL VPN 网关：http://jwglxt-…-s.vpn.bua.edu.cn:8118/jwglxt/…
     *   * 网页版 WebVPN：https://webvpn.bua.edu.cn/https/…/jwglxt/…
     */
    public static boolean isTimetablePage(String url) {
        if (!isThroughVpn(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("/jwglxt") && !lower.contains("sign_in");
    }

    /** 是否已经通过校外入口（SSL VPN 网关或 WebVPN）访问 */
    public static boolean isThroughVpn(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        try {
            String host = new URL(url.trim()).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            return host.endsWith(GATEWAY_SUFFIX) || host.equals(WEBVPN_HOST);
        } catch (Exception e) {
            return false;
        }
    }
}
