# RingFitness Android

当前开发版：**步数采集 0.7.4-start-timing（versionCode 38）**，应用ID为`com.nexthci.ringfitness.steps`。基于原版0.5.3继续开发，适用需求与下一项工作分别见[功能规格](../specs/independent-step-collection/requirements.md)和[执行计划](../specs/independent-step-collection/plan.md)。

## 当前可运行范围

正常入口已接通：首次登记编号与佩戴位置、选择/连接戒指、等待真实开始确认、后台采集、停止确认、整段计步器读数保存、原始文件下载校验、冻结ZIP和清华云盘上传。准备档案与采集记录使用独立App私有目录；单一前台采集服务管理BLE，独立上传服务管理网络任务。页面依据真实保存、设备证据和回执呈现结果。

0.7.2在打开准备页时恢复待上传队列，无需先连接戒指。queued/sending及已手动重试的任务继续等待网络；明确失败在原记录中手动重试，保持同一session、目标和冻结包。真机短采、本地保全、云盘人工读回与Python处理分别见E18/E19，恢复及自动云端读取见[验证记录E20](../specs/independent-step-collection/validation.md#19-启动上传恢复与自动云端读取2026-09-19e20)。研究端入口见[活动导入器](../backend/ringo_data/README.md)。

0.7.3增加未确认开始请求的“结束本次尝试”入口：填写原因后重新连接，完整查询并复核原始文件，满足条件才保存归档审计并返回准备。该操作保留原记录，未确认的尝试不进入研究数据；本地账本升级v4，研究manifest快照及冻结包维持原契约。软件验证及真机归档、强停重开和原包保全已通过[验证记录E21](../specs/independent-step-collection/validation.md#20-未确认开始请求的受控归档2026-09-19e21)；固件仍返回-16，新采集保持禁用，根因继续调查。

0.7.4补齐启动时序：可靠保存请求后等待500ms，再发送一次START，入队后1000ms开始完整状态检查；仍为空闲且基线不变时，间隔1500ms只读复查，含首轮最多3轮。空闲连接错误采用相同的有限完整复查，错误归零且完整空闲基线不变才恢复开始入口，发送START仍须通过原文件保全预检；“重新检查”始终针对当前设备。Debug日志增加单调时钟时序。349项JVM、44项模拟器及相关构建沿用`e497dee`的通过证据，本轮未重跑。版本38已原位安装，真机初查、重新检查及强停重开各完成3轮STATUS/LIST，页面保持未就绪，四次快照中的21个保护文件不变，独立复核通过，见[验证记录E22](../specs/independent-step-collection/validation.md#21-启动时序与有限错误复查2026-09-19e22)。设备持续返回-16，未发新START，500/1000ms实际设备时序待验证；错误清除条件、-16触发条件及保留数据的恢复步骤仍待固件依据。

Debug从“更多 → 流程演示（模拟）”进入完整交互演示，复用原生页面、协调器与持久化规则，设备、信号和传输使用替身；演示目录与正式记录隔离。Release不提供该入口。页面色彩、字号、容器和主要操作沿用功能规格的最小视觉规则。

当前已验证的是限定设备上的短段技术链路。戒指时钟偏差、实际样本与参考时段对应、完整采集/下载故障矩阵及逐级长时继续验收，最长支持时长尚未确定。云端自动读取、试用版本与测试者独立操作以执行计划和最新证据为准。[被试操作说明](../docs/participant-guide.md)为待发放前核对的草稿。

## 构建与本地配置

使用JDK 21、Android SDK 36及工程Gradle wrapper，最低Android版本为11。手机真实上传位置仅配置在Git忽略的`local.properties`中，键为`ringfitness.activityUploadLink`；研究端读取须指向同一云盘目录。准备与采集入口采用本地编号，旧平台登录和睡眠授权退出独立流程。

从`android`目录执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease --console=plain
```

Debug APK位于`app/build/outputs/apk/debug/app-debug.apk`；Release构建当前生成未签名产物。中文Windows启动器存在编码兼容问题时，可追加`'-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK'`，项目文件继续使用UTF-8，依据见[基线记录](../docs/android-baseline-20260918.md)。测试APK构建、设备测试运行与真实设备验收分别记录。

## 页面与恢复验证

已有账本和原文件均保全的空闲戒指可运行`IdleRingDiagnosticInstrumentedTest`。该测试默认跳过，只有显式传入`-e verifyIdleRingDiagnostic true`才查询指定戒指；只发送STATUS/LIST，并逐轮核对本地文件。需先确认没有待处理采集并关闭生产App服务，仅选择该测试方法运行。卸载重装或换机后缺少原账本时会拒绝执行，具体前置条件与结果见E22。

模拟器用于页面、输入、导航、本地保存和受控异常。安装Debug及对应AndroidTest APK，在指定模拟器上运行：

```powershell
adb -s <emulator-serial> shell am instrument -w -e class com.nexthci.ringfitness.PreparationNavigationInstrumentedTest,com.nexthci.ringfitness.CollectionFlowInstrumentedTest,com.nexthci.ringfitness.RealCollectionBridgeInstrumentedTest -e verifyPreparationNavigation true -e verifyCollectionFlow true com.nexthci.ringfitness.steps.test/androidx.test.runner.AndroidJUnitRunner
```

准备导航用例要求模拟器具备已授权的蓝牙条件；缺少前置会明确跳过。测试数据在隔离空间，执行期间保持App不受手动操作干扰。BLE协议、真实采集、下载与长时由手机和戒指另行取证。

`recoveryQa`仅用于对已核对副本注入上传故障，独立应用ID为`com.nexthci.ringfitness.steps.recoveryqa`，显示“步数采集·恢复验证”。它与正常安装的数据隔离，使用同一上传实现：

```powershell
.\gradlew.bat -Pringfitness.recoveryQa=true assembleRecoveryQa assembleRecoveryQaAndroidTest --console=plain
```

`UploadRecoveryInstrumentedTest`默认跳过，执行需显式`verifyUploadRecovery=true`、独立包名、已审阅的本地marker和原包SHA。调度检查要求离线且BLE权限拒绝，手动重试另指定session；具体准备、方法与已验证故障范围见E20。保持正式安装、凭据和实验记录完整，测试结束恢复设备原网络设置。每次交付按相关规格补运行结果，历史通过项保留原版本范围。

## 原版历史说明

以下保留交接版本的能力和操作说明，适用范围为原版 0.5.3。新版本已退出这些旧导航入口，后续采集与上传按新规格逐步接入。

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

当前工作副本的构建方法与 APK 位置见本文开头；原版运行路径仅作历史参考。

从 0.1.6 起，Oura 登录会枚举 Custom Tabs 浏览器并优先选择明确支持账号隔离会话的 Chrome，不再误用 vivo 等不支持该能力的默认浏览器。后端同时执行 RingFitness 用户与 Oura 账号严格一对一校验。

采集文件保存到 `Download/RingFitness/YYYY-MM-DD/`。用户名规则为 3–24 位字母或数字，不区分大小写。
