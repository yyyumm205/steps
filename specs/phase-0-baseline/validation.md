# 原版 RingFitness Android 0.5.3 基线验证：验收与证据

> 归档说明（2026-09-18）：E00–E04 保留为原版证据和源码事实。当前判据见[新验收规格](../independent-step-collection/validation.md)。原 V01–V05 的环境、构建和自动测试由新 B01 承接；V06 的原用户服务登录检查撤销；V07–V16 的设备与数据链路检查迁移到新版本的验收项。迁移和撤销均不代表检查通过。下文原 Q04 不再是项目依赖。

历史状态：原规格待审阅，现已归档；记录日期：2026-09-18。下文保留总纲 1.0.0 下的判据与当时证据。

依据：[总纲](../../CONSTITUTION.md) 第 5、6、7 节；需求见 [requirements.md](requirements.md)，执行顺序见 [plan.md](plan.md)。本文件区分历史测试、当前只读核查与后续执行。真机和上传操作在本轮均未执行。

## 1. 状态口径

- **已有测试通过**：存在实际测试报告，明确报告对应的测试类型、数量和时间。
- **已核查**：本轮读取文件、核对哈希或源码得出的事实；不表示已经执行功能。
- **待执行**：列入计划，尚无相应执行结果。
- **受条件限制**：检查尚待执行，当前存在具体前置条件；解除后再执行。
- **失败**：实际执行后不满足预期；应附证据，保留问题直到复核。

## 2. 已有证据

### E00：仓库与源码

- 进入本轮时分支 `codex/constitution`；HEAD 为 `d534afbf96087eef13c6cd8efd350afa56f59ed4`，提交说明 `docs: establish RingFitness project constitution`。
- 当时 `git status --short --branch` 只有原有未跟踪的 `android/`、`original/`；跟踪文件和暂存区无差异。仓库无 `specs/` 规格或独立验收记录。
- 当前文档分支从该 HEAD 建立：`codex/phase-0-baseline-spec`。首次交付规格时没有新增提交；用户随后授权由开发代理在审阅和适当验证后自行本地提交，当前三份草稿按该规则保存版本，规格状态仍为待审阅。
- 将 `original/RingFitness-0.5.3-Android-source/` 下 36 个文件按相对路径逐一与 `android/` 比较 SHA-256，0 项缺失、0 项不同。此结论只覆盖原始文件；工作副本另外含本机配置和生成内容，尚未绑定源码提交。
- 在当前 Android `app/src/` 与归档后端 `backend/` 搜索 `ground_truth|groundTruth`，无匹配；原交接说明也明确列为待开发。

### E01：Android JVM 单元测试

实际目录：[testDebugUnitTest](../../android/app/build/test-results/testDebugUnitTest/)。HTML 汇总：[index.html](../../android/app/build/reports/tests/testDebugUnitTest/index.html)。

| XML 报告 | 测试数 | 失败/错误/跳过 | 报告内 UTC 时间 |
| --- | ---: | --- | --- |
| `TEST-com.nexthci.ringfitness.CapturePurposeTest.xml` | 5 | 0 / 0 / 0 | 2026-09-18T08:34:53.958Z |
| `TEST-com.nexthci.ringfitness.HealthTimeAnchorTest.xml` | 5 | 0 / 0 / 0 | 2026-09-18T08:34:53.986Z |
| `TEST-com.nexthci.ringfitness.ParticipantProfileStoreTest.xml` | 3 | 0 / 0 / 0 | 2026-09-18T08:34:53.990Z |
| `TEST-com.nexthci.ringfitness.RingProtocolTest.xml` | 12 | 0 / 0 / 0 | 2026-09-18T08:34:53.996Z |
| 合计 | 25 | 0 / 0 / 0 | 北京时间 2026-09-18 16:34:53 |

按上表顺序，四份 XML 的 SHA-256 为：

```text
995693C5286F0C7F71018A7FAF4A2A81A4410DC3EA3F6F65D620557AF7CA2483
47F5FB3FF329EF381630507EFE32BB74E10554544BE80E8F4BBEBB7B01A0B0D9
513930029D417939309A04E07441B34E924AA61CAFE1CD90E5A31B43DD9D7C4F
B0753D9CC79FE9A8C6042E195FA7EACA36071F27F294009E2DFDE814E6860C23
```

该证据覆盖活动/佩戴位置、时间锚点、用户名与协议等单元行为。设备文件测试、蓝牙实物通信、重启恢复、云盘上传及 Python 运行均需各自的证据。本轮只是读取旧报告。

### E02：现有 APK

- 路径：[app-debug.apk](../../android/app/build/outputs/apk/debug/app-debug.apk)。
- 元数据：[output-metadata.json](../../android/app/build/outputs/apk/debug/output-metadata.json)：`com.nexthci.ringfitness`，debug，0.5.3，versionCode 25，minSdk 30。
- 大小 15,306,633 字节；文件修改时间 2026-09-18 16:32:43（北京时间）。文件时间仅作为线索。
- SHA-256：`64BD9B26B9425818223917A88B9E77A39466DB3BBDD0D2043FAC896BD38E14A2`。
- 结论：已核查构建产物存在。构建命令退出码、运行时版本、配置与源码输入尚未组成完整复现记录；APK 能否安装、登录、连接戒指另行验证。

### E03：环境与访谈

- 已读取 `android/local.properties` 的配置键存在性：`sdk.dir` 有值；`ringfitness.activityUploadLink`、`ringfitness.authorizationUrl`、`ringfitness.enrollmentCode` 未设置。本轮未输出配置秘密值。
- 当前 shell 的 PATH 中找不到 java、javac、adb，能找到 python。此结果不证明机器未安装相关工具，具体路径与版本待核对。
- 用户确认 Android 11 及以上手机、戒指和充电盒可用；选择自己的清华云盘测试文件夹及本机 Python。
- 手机型号和具体系统版本待补录；固件版本待设备读取，云盘及原用户服务配置待落实。

### E04：源码审阅发现

| 事实 | 依据 |
| --- | --- |
| 首次登录要求用户服务 URL 与注册码 | `MainActivity.kt`，`submitUsername`（229 行） |
| 结束活动后仍需保存评价，可留空评分和文字 | `MainActivity.kt`，`showStopChoices` / `showSubjectiveFeedback` |
| 结束时间包含下载或整理的时刻 | `RingCaptureService.kt`，`completeHealthDownload`（1023 行）、`finalizeUploadGroup`（1752 行） |
| 旧活动记录保留创建时的上传地址 | `RingCaptureService.ensureUploadGroup`；`UploadSessionStore.migrateUploadLink` 只迁移睡眠记录 |
| 上传器使用系统任务队列，需网络；一般异常累计尝试达到 5 次后进入 failed，永久错误可直接失败 | `UploadWorker.kt`，`UploadScheduler.enqueue`、`runUpload`、`MAX_RETRIES`；具体延迟由系统调度 |
| “本地待上传”列表筛选 failed | `UploadSessionStore.failedUploadsForParticipant` |
| 上传成功后临时 ZIP 被删除，本地采集文件和回执保留 | `UploadWorker.runUpload`；ZIP 应从云盘或成功前的暂存包取证 |
| Python 主同步入口依赖 Junction/Oura；独立导入器可处理本地活动包 | `sync_all_and_label.py::main`；`ringo_data/app/app.py::_run_cloud_import_job` |

本轮在包含忽略文件的目录清单中发现上述 JVM 报告和 APK；未找到设备测试报告、真机采集记录或 Python 执行报告。交付说明中的“测试通过”作为交付方声明保留，不替代本机实测。

## 3. 可执行验收清单

以下预期为审阅后的检查判据。表中“受条件限制”均仍属未执行；所有结果由执行记录更新。

| 编号 / 需求 | 如何检查 | 预期结果与证据 | 执行条件 | 当前状态 |
| --- | --- | --- | --- | --- |
| V00 / R00 | 核对分支、HEAD、暂存区、变更文件；与原始 Android 比较；检查三文档引用和编号；核对本地提交文件范围 | HEAD 含总纲；本步提交仅包含三份规格；原有工作保留；输入版本及差异明确，暂定方案和待确认项保留 | 仓库可读 | 已核查 E00；三文档链接及 R/P/V 对应无缺项；按补充授权进行提交范围复核 |
| V01 / R01 | 记录 JDK、Gradle JVM、SDK 36、platform-tools、Python 和虚拟环境路径及版本；建立后端工作副本 | Gradle 可启动；SDK/JDK 兼容；Python 可导入 Flask、requests、pytest；源码副本与归档差异明确 | 审阅完成，工具安装位置可确认 | 待执行；E03 仅为路径初查 |
| V02 / R01 | 在 android 运行 `assembleDebug`，保存完整日志、退出码及输出元数据；计算 APK 哈希 | 退出码 0；生成应用 ID、版本、SDK 与源码配置一致的 APK；记录源码指纹和配置代号 | V01；真机用 APK 还需 Q03/Q04 配置 | 待执行；E02 已有产物 |
| V03 / R02 | 核对现有 XML；环境/测试配置确定后运行 `testDebugUnitTest` 并保留新报告 | 原版应发现 25 项；0 失败、0 错误、0 跳过；数量改变需说明原因 | 读旧报告无附加条件；重跑需 V01 | 已有测试通过 E01；复现运行待执行 |
| V04 / R02 | 运行 `connectedDebugAndroidTest`，核对 `CaptureFileInstrumentedTest` 的报告 | 至少该 1 项实际执行并通过；核对写入 Downloads 的时间和传感器字段。该测试使用合成数据 | V01，Android 11+ 真机或模拟器、adb 可连接 | 待执行；未找到报告 |
| V05 / R02 | 用 pytest 执行 plan 中指定的 importer、health_raw_v2、label_daily_activity 测试 | 发现测试数量大于 0；所选测试全部通过，无错误/跳过；含原始格式解码及幂等导入检查 | 后端工作副本、Python 测试环境 | 待执行 |
| V06 / R03 | 记录 APK 哈希与手机型号/系统；安装；以测试用户首次登录；按系统版本授予蓝牙等权限 | 正确进入戒指页面，用户归属明确；缺配置和拒绝权限时有可记录的阻断原因 | V02，测试手机，用户服务配置和测试用户名 | 受条件限制：Q04 待落实 |
| V07 / R03 | 搜索指定戒指、读取电量、连接至服务就绪；读取固件版本 | 戒指身份明确且可进入采集页；版本记录为实测值；不支持查询时保留提示并由同伴确认版本 | V06，已充电戒指和盒 | 受条件限制：依赖 V06；实物已就绪 |
| V08 / R04 | 按 requirements 第 2 节完成暂定 3 分钟走路，保留开始、采集中、停止、下载、保存状态和 session | 仅一个 session；活动 walking、实际佩戴位置正确；停止指令得到确认，下载并保存可用原始文件 | V07，测试目录配置确认；已有待传任务已处理 | 受条件限制：依赖登录/目标配置；待执行 |
| V09 / R05 | 读取该 session ZIP 的 manifest；核验每个文件的实际大小/SHA-256；由解码器检查 rfbin 头、payload 长度和 CRC | `version=2`，session/被试/活动/位置一致；非空 `.rfbin` 与校验值一致；保留原包和核查记录。基线允许没有 ground_truth 字段 | V08；成功前暂存包或从云盘取得的原 ZIP | 待执行 |
| V10 / R05 | 将操作观察、停止确认、下载完成、manifest、rfbin 头、CSV 首末时间列在同表；计算差值 | 单位及时区一致；CSV 时间、三轴列可解析；记录首末样本与活动区间差异及已知整理延迟，不把处理时间当成步数边界 | V08、V09、V15；手机时钟和观察记录 | 待执行；已有源码风险 E04 |
| V11 / R06 | 在测试 session 下载进度大于 0 且未完成时中断蓝牙，再恢复并连接原戒指；核对断点、本地任务和待传锁 | 原 session 与位置标签保持；从有效断点继续，完成后校验通过；下载未完成时不能开始新采集覆盖数据 | 正常采集已可运行；可中断的 Flash 下载 | 待执行；下载太快无法触发时记录未覆盖并调整测试时长 |
| V12 / R06 | 完整保存到手机后、尚未上传成功时，记录文件哈希及 session；在系统设置中“强行停止”App 后手动打开，全程保留应用数据 | session、身份、活动、位置与原始文件哈希一致；能继续处理原任务，状态如实反映 queued/failed。停止进程期间不要求后台继续运行 | 有本地已完成 session；可在结束前关闭 Wi-Fi 和移动数据并保留蓝牙 | 待执行；仅覆盖此阶段重启 |
| V13 / R07 | 配置新活动上传链接；核对 Seafile 资料库/远端目录及 Python 本地收件目录；检查本次 session 目标 | 手机与 Python 对应用户的同一测试目录；显式覆盖旧默认值；新 session 使用新地址，旧记录不被假定迁移 | Q03 完成；本地配置可读，不输出秘密值 | 受条件限制：真实位置与读取凭据待配置 |
| V14 / R07 | 在上传已开始时中断网络，记录失败/排队状态；恢复网络跟踪重试；若进入 failed，在“本地待上传”操作重试 | 同一 session 和原始文件保持；queued 有调度记录、failed 可手动重试；最终回执与目标 ZIP 对应。分别记录自动恢复、失败后手动重试是否真的触发 | V13、真实测试 session、可控制手机网络 | 待执行；仅离线排队不能算已验证失败重试 |
| V15 / R08 | 按 P06 用 Python 定向同步同一活动目录，导入一个真实 ZIP，解码并检查活动账本和 CSV | 云盘与本地 ZIP SHA-256 一致；同一 session 的文件清单可追溯；生成非空 `ringfitness_imu_lp_acc_50hz_*.csv`，含时间及三轴原始/物理单位列；数值可解析 | V05、V09、V13；Python 读凭据、实际上传包 | 受条件限制：配置/真实包待准备 |
| V16 / R08 | 对 V15 已导入的同一个 ZIP 再同步/导入；比较前后账本、CSV 数量与哈希；核对现有冲突测试 | 相同 session+同哈希被跳过，无重复训练记录、原文件不变；不同内容冲突保留为错误，不覆盖原记录 | V15 完成，保留原 ZIP 和账本 | 待执行 |

V14 包含两种不同情形：系统排队后的自动恢复、真正失败后的手动重试。执行记录分别标明；未触发的情形仍待验证，不能用成功上传替代。不会为了生成失败状态手动篡改 session JSON。若现有网络条件难以复现 failed，记录限制并保留该项待验证。

## 4. 时间与数据核对记录模板

以下均为待填，不是测量值。每次实验使用同一个 session 记录表。

| 项目 | 实际值 | 来源/证据 |
| --- | --- | --- |
| 测试用户 / session UUID / 戒指 HEALTH ID | 待填 | 本地 JSON、manifest、rfbin 头 |
| 手机型号、系统、App 版本与 APK 哈希、戒指固件 | 待填 | 设备页面与工具输出 |
| 点击开始 / 界面确认采集中 | 待填 | 带时间的观察或日志 |
| 活动开始 / 活动结束 | 待填 | 观察记录 |
| 发送停止 / 戒指确认停止 | 待填 | 现有状态/日志；若日志缺失明确标注 |
| 下载完成 / 本地保存完成 / 上传完成 | 待填 | 任务状态与回执 |
| manifest 开始、结束 / rfbin 头开始、结束 | 待填 | 原始包 |
| CSV 首末时间、总行数、采样间隔分布 | 待填 | Python 检查输出 |
| 各时间差值、缺口和原因 | 待填 | 同一时区下计算；避免把下载耗时算入活动 |

采样频率检查统计相邻时间差：50 Hz 标称间隔为 20 ms。记录非正间隔、较大间隔与无效数值数量，异常须说明。具体丢样容忍阈值属于后续数据质量/实验规格，本阶段不预设一个未经验证的阈值；解码失败、无有效加速度数据或无法解释的时间倒退会阻止基线通过。

## 5. 阶段判定和本轮审阅结果

当前阶段 0 尚未通过。E01 支持历史 JVM 测试通过；E02 支持存在 APK；真实设备行为、恢复和完整数据链路均需实际证据。

完成后由 P07 按 V00–V16 汇总，未执行或受限制的关键项保持开放；不得合并表述为“全部测试通过”。缺陷发现后的修复、重测和范围调整按总纲的工作流另行推进。

本轮一致性审阅已落实以下修正：

1. 将基线验证限定为原版行为，评价页面和多活动入口保持按源码描述；步数字段和界面精简明确列入后续。
2. 纳入实际发现的 25 项测试报告和 APK，区分已有产物与可重现构建。
3. 用 R00–R08、P00–P07、V00–V16 串联需求、步骤和判据；历史证据集中于 E00–E04。
4. 补齐原用户服务、Android/JDK 版本、Python 测试框架、实物设备及云盘双端映射条件。
5. 标出 session 结束时间的真实语义；后续参考步数须与有效采集区间对齐。
6. 明确下载断点、本地重启、排队/失败重试、旧地址和重复导入的规则及取证方法。
7. 记录用户确认的设备和部署方向；手机详情、固件实测值、云盘配置、原登录服务保持待确认/待补录。

请重点审阅 requirements.md 的 A01–A04 暂定方案，以及 Q04 原用户服务是否可取得。正式计步器操作方式、采集频次与时间对齐容忍度仍由后续实验和功能规格决定。
