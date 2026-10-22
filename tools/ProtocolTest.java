import com.bua.coursehelper.JwglxtClient;

/**
 * 登录协议自测：直接用项目里真正的 JwglxtClient 去打学校服务器，
 * 用【不存在的假学号】验证 csrftoken + RSA 密码加密 + 提交字段是否正确。
 *
 * 期望结果：服务器回「用户名或密码不正确」，说明协议正确；
 * 如果报异常或返回其它提示，就是代码有问题。
 *
 * 用法（桌面 JVM，需要 org.json 和一个 android.util.Base64 替身）：
 *   javac -encoding UTF-8 -cp json.jar -d out \
 *      app/src/main/java/com/bua/coursehelper/JwglxtClient.java \
 *      tools/desktoptest/android/util/Base64.java tools/ProtocolTest.java
 *   java -cp "out;json.jar" ProtocolTest [教务系统地址]
 */
public class ProtocolTest {

    public static void main(String[] args) {
        String baseUrl = args.length > 0 ? args[0] : JwglxtClient.DEFAULT_BASE_URL;
        String fakeUser = "2025000000";
        String fakePass = "wrongpassword123";

        System.out.println("教务系统: " + baseUrl);
        System.out.println("假账号: " + fakeUser);
        System.out.println("--- 开始登录测试 ---");

        long start = System.currentTimeMillis();
        JwglxtClient client = new JwglxtClient(baseUrl, fakeUser, fakePass);
        JwglxtClient.Result result = client.login("");
        long cost = System.currentTimeMillis() - start;

        System.out.println("耗时: " + cost + " ms");
        System.out.println("成功: " + result.ok);
        System.out.println("消息: " + result.message);
        System.out.println("csrftoken 长度: " + (client.csrfToken == null ? 0 : client.csrfToken.length()));

        boolean protocolOk = (!result.ok)
                && result.message != null
                && (result.message.contains("密码") || result.message.contains("用户名"));
        System.out.println();
        if (protocolOk) {
            System.out.println("协议自测通过 ✅（服务器正确识别了加密后的密码，只是账号是假的）");
        } else {
            System.out.println("协议自测失败 ❌（返回内容不是预期的账号密码错误）");
            System.exit(1);
        }
    }
}
