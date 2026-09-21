# RingFitness Android

当前版本：**步数采集 0.8.8-ring-defer（versionCode 50）**，应用ID为`com.nexthci.ringfitness.steps`。本版以原版Android 0.5.3为实验设计和设备行为的默认参考；现行需求见[功能规格](../specs/independent-step-collection/requirements.md)，实施顺序见[执行计划](../specs/independent-step-collection/plan.md)，实际通过范围见[验证记录E35](../specs/independent-step-collection/validation.md#e35原版戒指暂存收尾2026-09-21)。

## 当前流程

正式入口为同一个原生Android App：

1. 首次填写研究者分配的用户名，选择佩戴位置和戒指；日常打开恢复已保存信息。
2. 每段重新选择走路或跑步，佩戴设备、清零计步器，再开始采集。
3. 戒指确认开始后显示“正在采集”；点击结束后等待STOP及Flash记录确认。
4. 在同一收尾页填写非负整数总步数，`0`有效，然后选择：
   - **保存并上传：** 先保存参考值，再下载并校验原始文件；手机保存完整后由后台上传。
   - **暂存到戒指：** 先保存参考值，原始文件保留在戒指，不自动下载或上传；用户稍后从首页或记录中执行“下载并上传”。
   - **放弃本段：** 二次确认后排除本段上传与统计。

戒指待下载期间保持原session、用户名、佩戴位置和戒指绑定，禁止开始下一段或切换这些设置。手机原始文件完整后，云盘上传与下一段采集相互独立。历史`SAVE_LATER`仍表示“文件已在手机、等待手动云盘上传”，不会转换为戒指暂存。

本地journal v13用于恢复`DEFER_ON_RING`，研究manifest仍按v2–v7处理；内部暂存状态不进入研究包。旧journal、冻结ZIP和历史参考保持原文。

Debug版在模拟器中提供“更多 → 流程演示（模拟）”，演示数据与正式记录隔离。Release不显示演示入口，正式状态只依据真实设备、文件与上传回执。

## 构建与检查

使用JDK 21、Android SDK 36和工程Gradle wrapper，最低Android版本为11。上传位置只配置在Git忽略的`local.properties`中；研究端读取位置须指向同一云盘目录。

在`android`目录执行开发检查：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintVitalRelease --no-daemon --console=plain
```

Debug APK位于`app/build/outputs/apk/debug/app-debug.apk`。`assembleRelease`只生成未签名中间产物，不能直接发放。

模拟器页面与恢复检查使用项目的instrumentation测试；BLE、真实采集、Flash下载、后台上传和长时行为须在真机与戒指上另行取证。完整命令、用例数量、失败及跳过项统一写入validation，不在README复制历史日志。

## 正式签名包

仓库脚本[scripts/Build-ReleaseApk.ps1](../scripts/Build-ReleaseApk.ps1)负责构建、对齐、签名、证书检查和SHA-256记录。首次建立项目专用发布身份，并为已安装的同证书Debug版本生成签名轮换链：

```powershell
.\scripts\Build-ReleaseApk.ps1 -InitializeSigning -UpgradeFromDebug
```

后续版本复用同一签名材料：

```powershell
.\scripts\Build-ReleaseApk.ps1
```

产物位于`android/dist/<version>/RingFitness-Steps-<version>.apk`，同目录生成`SHA256SUMS.txt`。以下材料属于发布身份，必须加密备份并限制访问：

- `.local/signing/release.jks`
- `.local/signing/release-password.txt`
- `.local/signing/release.lineage`
- `.local/signing/previous-debug.keystore`（脚本保存的旧签名私钥副本）

这些文件和`android/dist/`已被Git忽略。遗失或更换任一私钥会影响后续升级；不得重新初始化发布身份代替复用。

签名轮换只能覆盖由同一旧证书签名的既有安装。每次交付保存旧证书、新证书及lineage核验结果，并完成：旧版原位升级、应用私有数据哈希保持、applicationId和versionCode核对、Release无演示入口、新装后再升级一版。完成模拟器和目标真机验证前，不将签名包标为可发放。

## 后台任务

`RealCollectionService`负责BLE采集与下载，`RealUploadService`负责持久网络任务。上传队列绑定session、冻结ZIP、长度和SHA；普通网络错误按既有上限重试，永久错误保留人工入口。戒指暂存任务在手机备份完成前不得进入上传队列。

原版上传任务在执行期间使用可观察的前台通知。当前独立服务已接入`dataSync`前台保护，系统停止、销毁和超时路径保留持久任务。API33+首次进入采集询问通知权限，拒绝后仍可继续；设置页根据实际状态提供通知和电池设置入口。自动检查与实际设备范围见E35，锁屏、网络切换、整机重启和长文件继续按设备矩阵取证。

## 数据与恢复边界

- 原始文件为无损`.rfbin` v2，上传包按走路／跑步和`session_id`独立冻结。
- 参考步数、处理选择、设备记录锚和恢复状态先写入应用私有账本。
- 下载采用同一戒指、同一记录证据和断点校验；重复片段须一致，缺口或替换保持隔离。
- 放弃只处理本段手机任务并保存排除标记；已核对协议没有单段Flash物理删除命令。
- 原位升级用于保留数据。卸载或清除应用数据会删除尚未外部保全的记录，不作为恢复步骤。
- 上传链接、凭据、被试数据、APK、日志和截图不进入Git。

被试可见说明见[participant-guide.md](../docs/participant-guide.md)。原版0.5.3源码保存在`original/`，仅作为基线与历史证据；当前正式入口不得启动旧采集或旧上传服务。
