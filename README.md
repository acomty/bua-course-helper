# 课表助手（北京农学院）

把教务系统（正方 `jwglxt`）的课表导入安卓手机：**一屏看全、自动定位本周、两个桌面小组件**。
纯 Java 手写，**零第三方运行时依赖**（网络用 `HttpURLConnection`，JSON 用系统自带 `org.json`），
不收集数据、不上传任何东西。

> 非官方工具，作者与学校无关；请自行评估使用风险。

## 下载

**最新安装包**：[Releases](https://github.com/acomty/bua-course-helper/releases/latest) → `CourseHelper-v3.6.apk`
（安卓 7.0 以上；安装时允许「未知来源」）

## 功能

- **导入课表的三种方式**
  - 企业微信导出的课表 **PDF** —— 本地解析（自己写的 PDF 文字/表格提取，无需 OCR）
  - `timetable.json` —— 电脑上跑 `run-fetch-test.bat` 抓下来的数据
  - 登录教务系统直接导入（需校园网 WiFi）
- **一屏看全**一周课表，按当前周次 + 单双周自动过滤
- **填一次「当前是第几周」** 之后按日期自动往后推算（按自然周，周一进位）
- 手势：课表上**左右滑**切上一周/下一周（带动画）、**上下滑**开合设置
- **自建课程**（旁听课，虚线框），重新导入不覆盖
- **6 种主题色**
- **两个桌面小组件**：今天课表 / 今天+明天课表，每门课显示课程名、节次·地点、上课时间
- 完全离线可用（导入后缓存本地）

## 快速开始

1. 在 [Releases](../../releases) 下载 `课表助手-*.apk`，传到手机安装（需允许"未知来源"）
2. 企业微信里把课表导出成 PDF → 发到手机
3. App 里「设置 → 从文件导入」选那个 PDF
4. 填一次「当前是第几周」→「设为本周」

详细说明见 [使用说明.md](使用说明.md)。

## 自己构建

```
JDK 17
Android SDK 34（build-tools 34.0.0）
Gradle 8.7（工程自带 wrapper）
```

```bash
./gradlew assembleRelease        # 产出 app/build/outputs/apk/release/
./gradlew testDebugUnitTest      # 32 项 Robolectric 界面/逻辑测试
```

`local.properties` 里写自己的 SDK 路径（已在 .gitignore 中排除）。

## 技术说明

| 关注点 | 做法 |
| --- | --- |
| 正方登录 | GET 登录页取 `csrftoken` → GET 公钥接口 → `RSA/ECB/PKCS1Padding` 加密密码 → POST 登录；连续失败后带 `yzm` 验证码重试 |
| 课表接口 | `POST /kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151`（`xnm` 学年、`xqm` 学期 3/12/16） |
| 课表 PDF | 解 FlateDecode 流 → 解析文字算子与坐标 → 按「星期一…星期日」分列、按行距分块 → 从块内提取节次/周次/场地/教师 |
| 周次 | 支持 `1-16周`、`1-5,7-9`、`1-16周(单)`、`3-5周(单),6-8周(双),9-18周`、裸写「单周/双周」 |
| 校外访问 | 学校 SSL VPN 网关会把内网域名改写成 `xxx-s.vpn.bua.edu.cn:8118`；WebVPN 走 `/https/<token>/…`。校外导入是**在 WebView 页面内** `fetch` 课表接口（同源，会话由浏览器带），而不是把 Cookie 搬到 App 网络层 |
| 小组件 | `AppWidgetProvider` + `RemoteViews`（只能用 `@RemoteView` 控件） |

## 隐私

- 不接入任何统计/广告/第三方 SDK；除你的学校教务系统外不发起任何网络请求
- 学号密码只提交给学校系统，可留空（用 PDF 导入则完全不需要）
- 缓存只存在手机本地

## 已知限制

- 目前只适配**北京农学院**（正方系统）；其他学校需改 `JwglxtClient.DEFAULT_BASE_URL` 与校外网关规则
- 校外：SSL VPN 网关的"网页模式"对教务系统返回 **403**（学校策略），默认走网页版 WebVPN；两者都不行时请用 PDF 导入
- PDF 第 2 页的「实践课程 / 其他课程」（无固定星期节次）不会导入
- 仅安卓；iOS 原生版需要 Mac + Xcode，暂无

## 许可

未添加开源许可证（保留所有权利）。如需他人自由使用/修改，可自行添加 MIT 等许可证。
