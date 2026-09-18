# 原版 RingFitness Android 0.5.3 基线验证：计划

> 归档说明（2026-09-18）：本文件为原流程计划，当前执行顺序见[新计划](../independent-step-collection/plan.md)。P01/P02 的桌面环境与已有测试核查继续承接；原 P03 的用户服务配置和登录任务撤销。原 P04–P06 的设备、恢复及数据链路检查由独立版本承接。下文的登录配置要求仅为历史记录。

历史状态：原草稿待审阅，现已归档。以下为总纲 1.0.0 下的原计划，仅供追溯；当前计划以顶部链接为准。

依据：[总纲](../../CONSTITUTION.md) 第 6 节阶段 0、第 7 节；范围与决策以 [requirements.md](requirements.md) 为准；执行结果统一记入 [validation.md](validation.md)。

## 1. 本轮仓库操作

- 从包含总纲的 `d534afbf96087eef13c6cd8efd350afa56f59ed4` 创建 `codex/phase-0-baseline-spec`；创建前分支为 `codex/constitution`。
- 创建前已跟踪文件无未提交改动，`android/`、`original/` 为原有未跟踪目录；已有 APK、测试报告和 `local.properties` 保留。
- 本轮新增文件限定为当前目录的三份 Markdown 规格，完成审阅和文档检查后提交为待审阅草稿。总纲只引用，原始材料作为参考。
- Android 与后端代码尚未成为 Git 基线的一部分。下轮复现之前需记录文件哈希；业务源码是否纳入提交，在审阅后的版本控制安排中处理。

用户于 2026-09-18 补充本地 Git 授权，替代此前等待逐次提交指令的安排：今后每完成一个独立、可检查的小步骤，先审阅改动并执行适当验证，再由开发代理判断提交时机，只提交相关文件并使用明确的提交说明。每次提交后，用中文告知本次完成内容、已通过和未执行的验证、下一步。授权限于本地 Git 提交；需求范围、实验方案和重要交互的选择仍先与用户确认。保存规格草稿的提交不改变其待审阅状态。

## 2. 从现有能力到待执行工作

| 步骤 | 需求 | 已有基础与涉及组件 | 前置条件 | 待完成事项与交付物 | 验证 |
| --- | --- | --- | --- | --- | --- |
| P00 规格审阅 | R00 | 总纲、原始材料、当前源码和历史报告 | 本轮仓库可读 | 核对范围、建立分支、记录证据、三文档交叉审阅；检查后将待审阅草稿提交至本地 Git | V00 |
| P01 固定环境和输入 | R00、R01 | Gradle wrapper、构建文件、Android 工作副本、后端归档 | 规格审阅完成 | 记录源码差异与哈希，确认 JDK/SDK/Python 路径版本，准备后端工作副本；形成可重现环境清单 | V00、V01 |
| P02 现有自动测试 | R02 | 4 个 JVM 测试类、1 个 Android 文件测试、Python 现有测试 | P01；设备测试还需手机或模拟器 | 运行对应测试，保留数量、失败项、退出码及报告；结果按测试类型分开 | V03、V04、V05 |
| P03 配置并构建 | R01、R03、R07 | Gradle 本地配置、用户服务客户端、Seafile 上传器 | P01；Q03、Q04 补齐后才能用于完整真机流程 | 填本地配置、核对云盘两端映射、构建并记录 APK 哈希；安装、登录及权限结果 | V02、V06、V13 |
| P04 一次正常采集 | R03、R04、R05 | MainActivity、RingCaptureService、RingBleClient、RingProtocol、HealthFlashDownload、HealthRawV2 | P03 完成；实物戒指就绪 | 连接、读取版本、选择活动和位置、开始结束、保存上传；记录各状态与时间；核对 ZIP；V10 先填手机记录，在 P06 解码后补齐 | V07、V08、V09、V10 |
| P05 最小恢复检查 | R06、R07 | UploadSessionStore、活动状态恢复、Flash 断点、UploadScheduler | 正常流程已可运行；测试 session 可识别 | 针对下载断连、本地已保存时重启、上传中断/重试，分别记录触发点和恢复结果 | V11、V12、V14 |
| P06 本机处理闭环 | R02、R05、R08 | Python 下载函数、Flask 导入器、health_raw_v2、活动账本 | P01、P03；至少一个真实云盘 ZIP；Python 测试通过 | 定向同步、导入、解码、核对 session；重复导入并比较计数和哈希 | V05、V09、V10、V15、V16 |
| P07 汇总基线结论 | R00–R08 | 各项实际记录 | V00–V16 结果齐备或限制明确 | 逐项给出通过/失败/待执行/受条件限制；列问题和后续规格影响，判定是否具备进入阶段 1 的证据 | V00–V16 |

P02 的 JVM/Python 测试可与设备准备分别推进。任何需要修复业务代码的失败先形成问题记录，经规格审阅后安排修复任务；失败记录不等于该功能验收通过。

## 3. 环境与命令约定

### Android

构建文件实际采用 Android Gradle Plugin 8.13.0、Gradle 8.13、Kotlin 2.2.21，`compileSdk/targetSdk=36`、`minSdk=30`。总纲中的 Java 11 对应源码/字节码目标；运行 Android 构建插件需兼容的 JDK，暂定 JDK 17，不以 Java 11 作为构建运行时要求。设备要求 Android 11 或更高并支持 BLE。

当前 shell 未在 PATH 找到 `java`、`javac`、`adb`；`sdk.dir` 已配置，且已有构建和测试产物。下轮应先定位实际安装目录，再判断是否需要补装。记录 `java -version`、Gradle JVM、SDK platform/build-tools/platform-tools 与操作系统版本。

以下命令是审阅后由开发者执行的计划，目录为 `android/`：

```powershell
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

设备文件测试可在模拟器上执行，连接戒指和完整采集需实物手机。每次运行记录工作目录、配置代号、开始结束时间、退出码和报告；本机已通过的 25 项历史报告保留为独立证据。

### Python

目前 `original/RingFitness-0.5.3-Backend-source/` 是归档副本。执行前建立单独工作副本，暂定根目录 `backend-baseline/`，保留包内 `backend/ringo_data` 与 `backend/oura` 的相对结构。此工作副本尚未创建。

暂定 Python 3.11 虚拟环境；需核对实际可用版本。安装现有要求中的 Flask、requests，测试工具使用 pytest（`test_health_raw_v2.py` 使用 pytest 的 `tmp_path`，仅跑 unittest 会遗漏该项）。在工作副本 `backend/ringo_data/` 执行：

```powershell
python -m pytest app/tests/test_importer.py tests/test_health_raw_v2.py tests/test_label_daily_activity.py
```

Python 测试使用隔离测试目录；真实云盘检查使用确认过的测试目录。报告记录实际收集和通过的测试数量；0 项测试不算通过。

## 4. 配置和现有入口的衔接

| 位置 | 需设置或核对的项目 | 含义 |
| --- | --- | --- |
| `android/local.properties` | `ringfitness.authorizationUrl`、`ringfitness.enrollmentCode` | 原版首次登录依赖的用户服务，当前本机未设置 |
| 同一文件 | `ringfitness.activityUploadLink` | 手机活动数据上传入口，当前未显式设置；未设置时源码会回退到旧默认地址 |
| Python 本地环境 | `RINGFITNESS_SEAFILE_SERVER`、`RINGFITNESS_ACTIVITY_SEAFILE_REPOSITORY_ID`、`RINGFITNESS_ACTIVITY_SEAFILE_REMOTE_DIRECTORY` | 本机同步应读取的云盘主机、资料库与目录 |
| Python 本地环境 | `RINGFITNESS_SEAFILE_TOKEN` 或 `RINGFITNESS_SEAFILE_TOKEN_FILE` | 允许 Python 读取目标目录的凭据 |
| Python 本地环境 | `RINGFITNESS_ACTIVITY_CLOUD_INBOX`、`RINGFITNESS_ACTIVITY_RAW_DATA_ROOT`、`RINGFITNESS_ACTIVITY_PROCESSED_DATA_ROOT`、`RINGFITNESS_ACTIVITY_CLOUD_HISTORY` | 测试收件包、解码数据、处理结果和去重账本所在位置 |

上传链接相当于“投递入口”，后端的资料库与目录相当于“取件位置”。例如手机投递到你的“戒指测试”文件夹，Python 就应从同一文件夹取出同一个 ZIP。配置记录使用代号，秘密值留在本地。

原版把活动上传地址写入每个 session。新配置只用于之后新建的活动；验收前核对实际 session 的地址归属，不能依靠更换构建配置迁移既有活动记录。

本轮建议的本机运行路径（暂定 A04）：

1. 定向调用 `sync_all_and_label.py` 现有 `_read_seafile_token()` 和 `_download_cloud_source(session, token, source)`，`source` 只选择 `CLOUD_SOURCES` 中的 `daily_activity`。这是复用现有函数的验证调用，执行记录需保留实际命令；尚无证据证明存在活动专用的一键命令。
2. 启动工作副本 `ringo_data/app/app.py` 的本机网页，在“检查清华云盘新数据”入口调用现有导入器，跟踪返回的任务 ID 与完成状态。导入器扫描本地收件箱，活动包按 manifest 路由、解码和记账。
3. 核对活动 CSV 与账本中的 session；再处理同一 ZIP 检查去重。

直接运行原 `sync_all_and_label.py` 主入口会额外加载 Junction/Oura 配置，即使 `--local-only` 也有该前置条件；网页“检查清华云盘新数据”只处理本地收件包，不负责从远端下载。这两个入口的职责应在执行前确认。若定向复用不能在现有代码下运行，记为限制并重新审阅方案。

## 5. 记录方法与完成条件

每项检查记录：验证编号、执行者/时间、源码指纹、工具与设备版本、配置代号、实际步骤、退出码或可见结果、证据路径、结论及限制。真机记录增加手机和戒指身份、session UUID；敏感值与原始采集数据保存在本地受控目录。

暂定证据放在 Git 仓库外的本机测试目录，以执行日期和 V 编号组织。审阅之后，先明确证据目录并配置其与源码仓库的边界，再运行会生成数据的命令。

“规格准备完成”表示三份文档可审阅且待确认项清楚。“阶段 0 验证完成”要有逐项执行结论；“阶段 0 验收通过”还要求适用检查通过、关键条件齐备，且无未解决的数据丢失、错误归属或损坏问题。固件身份未核实、首次登录缺配置或真机链路未跑通时，阶段 0 保持未完成。

时间字段的已知含义及其差值需形成实际记录；它作为后续步数功能的输入问题保留。完成本阶段不表示已获得步数训练所需的时间对齐数据。
