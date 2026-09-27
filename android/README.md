# RingFitness Android

当前工作版本：**步数采集 0.9.0（versionCode 61）**，应用 ID 为 `com.nexthci.ringfitness.steps`。新增七种运动和可选 Polar H10，沿用原版 Android 0.5.3 的 H10 实时 HR/RR 方案与现有戒指采集流程。数据及生命周期契约见 [多运动与心率](../specs/independent-step-collection/multisport-heart-rate.md)，发布说明见 [release-0.9.0](../docs/release-0.9.0.md)，本轮验证见 [E46](../specs/independent-step-collection/validation.md#e46多运动与可选-polar-h102026-09-27)。0.8.18 戒指长时与云端读回证据保留在 E41–E45；新增 H10 的硬件支持范围单独验证。

## 当前流程

正式入口为同一个原生Android App：

1. 首次填写研究者分配的用户名，选择佩戴位置和戒指；日常打开恢复已保存信息。
2. 每段选择走路、跑步、羽毛球、足球、篮球、网球、乒乓球、排球或力量训练。走跑清零计步器；其他运动无需计步。九种运动均可选 H10，每段默认关闭，可在同页启用、搜索并连接，就绪后开始。
3. 开始前核对戒指未充电；当前连接确认采集后显示“正在采集”。戒指断线时原位显示“采集状态待确认”和稳定重连入口，恢复只查询同一段。点击结束后，走路、跑步可立即在同一收尾页填写非负整数总步数，`0`有效；其他七种运动直接等待停止确认。STOP及Flash记录确认在后台继续。
4. 戒指确认结束后，在原页选择：
   - **保存并上传：** 先保存本段信息、适用的参考值与已接收心率，再下载并校验戒指文件；手机保存完整后由后台上传。
   - **暂存到戒指：** 先保存本段信息、适用的参考值与已接收心率；戒指文件延后下载，用户从首页或记录中执行“下载并上传”。
   - **放弃本段：** 二次确认后清理本段手机数据文件和任务，包括已接收的心率，保留其他记录，并排除本段上传与统计。

戒指待下载期间保持原session、用户名、佩戴位置和戒指绑定，禁止开始下一段或切换这些设置。手机原始文件完整后，云盘上传与下一段采集相互独立。历史`SAVE_LATER`仍表示“文件已在手机、等待手动云盘上传”，不会转换为戒指暂存。

H10 通过手机实时接收 HR/RR，设备身份随本段开始意图固定。心率连接中断时戒指继续采集，手机保留缺口；同一心率带恢复连接后继续本段接收。胸带离线回补属于后续范围，当前恢复与暂存流程均处理手机已经收到的心率文件。

本地 journal v14 保存运动与可选心率，研究 manifest v8；Python 兼容 v2–v8。非计步运动为 `none/not_applicable`，参考数字为空。每段 H10 文件在首次打包前封存，与戒指原始文件进入同一 ZIP。旧 journal 和冻结 ZIP 保持兼容。

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

`RealCollectionService`负责BLE采集与下载，`RealUploadService`负责持久网络任务；旧`RingCaptureService`和`UploadJobService`不在正式Manifest中注册。上传队列绑定session、冻结ZIP、长度和SHA；DNS、建连失败、408、429及5xx等明确可恢复错误按既有上限重试。任务v3在POST正文写入前先持久保存边界；随后若成功回执丢失，或目标明确永久拒绝，任务停止重发并保留本地ZIP，页面提示研究者核对。戒指暂存任务在手机备份完成前不得进入上传队列。

原版上传任务在执行期间使用可观察的前台通知。当前独立服务已接入`dataSync`前台保护，系统停止、销毁和超时路径保留持久任务。API33+首次进入采集询问通知权限，拒绝后仍可继续；设置页根据实际状态提供通知和电池设置入口。自动检查与实际设备范围见E35，锁屏、网络切换、整机重启和长文件继续按设备矩阵取证。

## 数据与恢复边界

- 戒指文件为无损 `.rfbin` v2，上传包按九种运动和 `session_id` 独立冻结；所选 H10 CSV、样本数和缺口进入同包。
- 参考适用性、适用的步数、处理选择、设备记录锚和恢复状态先写入应用私有账本；已接收的心率在本段停止时保存到手机。
- 下载采用同一戒指、同一记录证据和断点校验；重复片段须一致，缺口或替换保持隔离。
- 连续3轮读取无进展后保留已保存部分并提供重试；相同重复片段不延长超时。旧记录保全的内部检查保持保存状态。
- 首页与记录页按当前用户名展示历史；旧用户已获准上传的记录仍按采集时冻结的ID在后台处理。
- 放弃只清理本段手机数据文件与任务，并保存排除标记；已核对协议没有单段Flash物理删除命令。
- 原位升级用于保留数据。卸载或清除应用数据会删除尚未外部保全的记录，不作为恢复步骤。
- 上传链接、凭据、被试数据、APK、日志和截图不进入Git。

被试可见说明见[participant-guide.md](../docs/participant-guide.md)。原版0.5.3源码保存在`original/`，仅作为基线与历史证据；当前正式入口不得启动旧采集或旧上传服务。
