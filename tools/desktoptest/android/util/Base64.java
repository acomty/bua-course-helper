package android.util;

/**
 * 桌面自测用的替身：安卓里有 android.util.Base64，桌面 JVM 上没有，
 * 这个文件只在电脑上跑协议自测时参与编译，不会打进 APK。
 */
public class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;

    public static byte[] decode(String text, int flags) {
        return java.util.Base64.getMimeDecoder().decode(text);
    }

    public static String encodeToString(byte[] data, int flags) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }
}
