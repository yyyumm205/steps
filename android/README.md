# RingFitness Android

当前开发版：**步数采集 0.6.4-t2b（versionCode 30）**，应用 ID 为 `com.nexthci.ringfitness.steps`。基于原版 0.5.3 继续开发，规格见 [独立采集规格](../specs/independent-step-collection/requirements.md)。

本版仅交付采集准备：离线登记编号、记住六种戒指佩戴位置、选择和连接戒指、查询电量/固件/采集状态。重新打开后保留准备信息，连接状态重新核对。发现正在采集或已有设备记录时提示研究者处理。开始采集按钮暂未开放；尚不产生 session、计步器参考数、下载或上传。

T1b准备导航保持原行为。T2b.1新增纯Kotlin采集协调器，使用现有请求账本和可替换传输接口，验证请求先落盘、设备观察后确认以及异常保全；尚未接入页面、真实BLE或前台服务。协调器须由未来单一采集服务串行持有；协议缺少请求nonce/boot ID，跨连接或进程恢复暂保持待核对。完整页面设计、服务迁移和真实入口的安全门槛见规格，模拟测试通过不代表真实采集已开放。

首次在同页填写编号和佩戴位置，点击“保存并连接戒指”。一次原子保存成功后按需请求权限并直接搜索；选中戒指保存成功后回首页连接，完成后自动显示结果。修改位置在首页弹窗中选择即保存，成功才更新，失败保留原值并允许重试；取消或选择原值不写入。

已有完整档案冷启动时，权限、蓝牙及必要定位齐备便自动连接一次；条件不足或连接失败时显示恢复动作。自动连接不弹系统授权，不在后台执行；取消、返回、关闭弹窗及页面重建不循环重连。首页共用同一判断显示当前状态和主要操作，电量、固件及 STATUS 未齐时持续显示检查中。技术信息、系统设置和更换戒指位于“设备详情”。本轮测试结果见 [B20 与验证记录](../specs/independent-step-collection/validation.md)，历史版本通过项保留原适用范围。

独立入口不需要旧平台注册、Oura 或 enrollment code。实际上传配置继续保存在 Git 忽略的 `local.properties`，本版准备页不使用它。私有准备档案由独立应用身份隔离；后续公共数据目录统一为 `Download/RingFitnessSteps/`。

构建使用 JDK 21 和 Android SDK 36。在 `android` 目录执行：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK' --console=plain
```

此命令的 GBK 参数适用于当前 Windows Java 启动器与中文 Gradle 缓存路径的兼容问题，项目文件仍保持 UTF-8；原因见 [基线记录](../docs/android-baseline-20260918.md)。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。设备测试须另行连接 Android 11+ 手机或模拟器执行 `connectedDebugAndroidTest`；测试 APK 构建成功不代表设备测试通过。

准备导航测试仅在模拟器显式启用，测试前备份准备档案、结束后恢复。连接到指定模拟器，安装上述构建产物后可执行：

```powershell
$emulatorSerial = '<模拟器序列号>'
adb -s $emulatorSerial install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $emulatorSerial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s $emulatorSerial shell pm grant com.nexthci.ringfitness.steps android.permission.BLUETOOTH_SCAN
adb -s $emulatorSerial shell pm grant com.nexthci.ringfitness.steps android.permission.BLUETOOTH_CONNECT
adb -s $emulatorSerial shell svc bluetooth enable
adb -s $emulatorSerial shell am instrument -w -e class com.nexthci.ringfitness.PreparationNavigationInstrumentedTest -e verifyPreparationNavigation true com.nexthci.ringfitness.steps.test/androidx.test.runner.AndroidJUnitRunner
```

以上权限命令适用于API31模拟器；缺少蓝牙条件时，相关用例会明确跳过。测试期间避免同时操作App。测试使用合成档案与可控设备回复检查保存、恢复、等待及重试，真实BLE另用手机和戒指验证。手动复核首次登记后自动搜索、选择后首页状态及强停重开；写失败用可控夹具验证，操作结果同时核对持久档案。具体数量、失败和未覆盖项统一记录在validation。

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
