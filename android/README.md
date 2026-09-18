# RingFitness Android

0.5.3（versionCode 25）将采集结束评分改为可选项：用户可关闭“填写评分”并直接保存评价。日常活动评价在“主观评价”之前新增最多 500 字的“活动细节”，可填写工作内容、饮食内容、步行/骑行/跑步路线等。未评分时上传清单省略 `score`，活动细节非空时写入 `activity_detail`；睡眠评价不显示或上传活动细节。

0.5.2（versionCode 24）新增采集结束后的主观评价。睡眠采集仅在启用 Oura 时评价；日常活动中的走路、骑车、跑步、工作和吃饭需要评价，“其他活动”跳过。用户先选择“立即上传”或“暂存到戒指”，App 停止传感器后要求滑动选择 1–10 分，可选填写最多 500 字评价，再继续 Flash 查询、下载或暂存。评价会持久化并随上传包写入 `manifest.json`；同时修正 Flash 收尾期间过早显示下载进度的问题。

0.5.1（versionCode 23）在日常活动采集开始前新增必选的戒指佩戴位置：左右手的食指、中指、无名指共六项。位置以 `ring_placement`、`ring_hand`、`ring_finger` 字段写入云盘上传包的 `manifest.json`；睡眠/心率采集不写入这些字段。

0.5.0（versionCode 22）新增“日常活动采集”模式。戒指连接后可选择进入原有睡眠/心率页面或日常活动页面；日常活动支持走路、骑车、跑步、工作、吃饭和其他活动六类标签，并可选同时采集 Polar H10 实时 HR/RR。两种模式共享戒指待传锁、固件/电量显示和四项结束操作，本地及已上传记录按模式分开显示。日常活动数据上传到独立的 `ringfitness.activityUploadLink`。

0.4.5（versionCode 21）将“从 0 重新下载”救援版的 Flash READ 窗口从 512 字节提升至 8 KiB，同时保留逐窗口等待 `READ_END`、防息屏、超时暂停、断点落盘和二次确认。

0.4.4（versionCode 20）与 iOS Flash 救援流程对齐：“戒指待传”增加二次确认的“从 0 重新下载”。它只删除手机端部分临时文件并将断点归零，保留原用户、原戒指、session 元数据和戒指 Flash 记录。该救援构建使用与 iOS 相同的 512 字节安全窗口，并保留防息屏、串行 `READ_END` 和超时暂停保护。

0.4.3（versionCode 19）接入戒指私有 NUS INFO 协议（请求 `2A 01`，响应 `2A 02`）并在采集页显示固件版本。Flash 停止、收尾、查询、下载和本地处理期间自动防止息屏，并与 iOS 一致显示红色离页警告。

0.4.0（versionCode 18）启用无损 HEALTH 原始包 v2：结束采集后直接保存戒指 Flash 原始字节并上传，按需异步导出/系统预览 CSV。

0.3.2（versionCode 15）与 iOS 0.3.2 Build 16 对齐：在 0.3.1 的待传任务与逻辑放弃机制上，修复部分 Android 蓝牙栈未回调 `disconnect()` 时旧 GATT 永久占用的问题；1.5 秒内未正常断开会强制关闭旧 GATT 并重新建链。删除数据或任一 HEALTH 命令超时后都会恢复连接，重连稳定 800 ms 后才继续发送 STATUS/READ，避免界面解锁但电量、START 和 STATUS 均无响应。

Android 数据采集端，应用 ID 为 `com.nexthci.ringfitness`。它同时支持：

- Ringo 戒指 HEALTH 模式（IMU + PPG，结束后从 Flash 下载）
- Oura 睡眠分期授权（无痕浏览器会话；按用户名 + App installation ID 绑定当前手机）
- Polar H10 实时 HR + RR 采集
- 戒指待传、云盘失败重试、当前用户上传记录；上传成功后保留本地 CSV

## 本地配置

在忽略提交的 `local.properties` 中配置：

```properties
ringfitness.uploadLink=https\://cloud.example.edu/shared/ringfitness/
ringfitness.activityUploadLink=
ringfitness.authorizationUrl=https\://<worker-host>
ringfitness.enrollmentCode=<worker-enrollment-code>
```

Polar Android SDK 8.1.0 的 AAR 已从本机 `polar-ble-sdk` 复制到 `app/libs/polar-ble-sdk.aar`，构建不依赖 JitPack。

## 构建

```powershell
$env:JAVA_HOME='D:\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

从 0.1.6 起，Oura 登录会枚举 Custom Tabs 浏览器并优先选择明确支持账号隔离会话的 Chrome，不再误用 vivo 等不支持该能力的默认浏览器。后端同时执行 RingFitness 用户与 Oura 账号严格一对一校验。

采集文件保存到 `Download/RingFitness/YYYY-MM-DD/`。用户名规则为 3–24 位字母或数字，不区分大小写。
