# 采集需求修订：差异与代码影响

当前基线（2026-09-21，总纲3.6.0）：原版Android 0.5.3是实验设计与设备行为的默认信任参考。所有主动差异按第16节记录原版行为、当前行为、改变依据、数据影响、验证证据和确认状态；Python SDK用于补充协议证据，不替代原版Android行为。离线单一用户名及其研究编号规则见第16节。软件检查与真机结论分别记录。

当前增量（2026-09-20，E26）：按已确认走跑分段及三种收尾更新总纲3.1.0。新建session明确选择走路/跑步；停止确认后提供保存上传、保存稍后上传与二次确认放弃。页面复用CollectionFlow，持久策略与放弃审计由SessionStore统一维护，后台恢复和队列执行都检查策略。旧自由活动及冻结包原文保持。

SDK及原Android复核：旧Unix未知记录须先独立完整保全；停止后沿用5秒Flash收尾等待；已核对的HEALTH协议未提供单段物理删除，原版discard只删手机任务。本轮放弃限定本段手机文件和传输，并保存排除标记。无当前采集任务的恢复页可直接返回设备页；时间异常但可证明本次START产生的采集增加一次保护停止与独立保全出口，不生成研究成功状态。实际测试、审查、跨连接限制及设备结果统一记录E26，详细历史由Git保留。

当前增量（2026-09-19）：在[总纲](../../CONSTITUTION.md)2.1.0下统一既有全流程页面与系统控件主题，版本0.6.7-t2p（33），见第12节；实际验证结果及限制记入validation的E17。完整演示与入口恢复的历史证据分别保留于E15/E16，设备确认、数据内容和回执仍为模拟。Q01/Q02状态保持原义，后续交付继续为真实采集与本地数据保全。

更新日期：2026-09-21。分支：`codex/free-living-session-spec`。工作流文档检查与各版本运行证据见[validation](validation.md)，当前进度见[plan](plan.md)。第1–4节保留 `29486e6` 当时的文档审阅依据；第5节起为逐次变更。历史段落按当时版本理解，当前决策以requirements第6节和本文件第16节为准。原始资料和本地配置保留。

## 1. 面向审阅的变化

| 旧设计/表述 | 本次修订 | 依据及影响 |
| --- | --- | --- |
| 先选走路或跑步，一段对应一种活动 | 一个统一开始入口，期间自由混合活动 | 用户本轮明确；同步改 UI、活动元数据、白名单及标签生成 |
| 每段活动类型可用于整段样本标签 | free_living 表示场景，样本活动真值 unlabelled | 第一阶段只评估 session 总步数；保留将来单独设计分类标签的空间 |
| 计步器清零还是累计差值尚待确认 | 每次站定清零，开始确认后活动，停止确认/读数稳定后填总数 | 计步器已确认支持清零；旧问题关闭 |
| 结束后必填整数，异常分支不完整 | 正常确认非负整数；缺失、不可靠、有效零值分开；异常保留 | 参考值与信号质量分别表达，无法填数的具体交互供 Q03 审阅 |
| 开始/结束字段较笼统，代码结束包含下载整理 | 分别保存请求/确认、采集边界、设备时间证据、填写和下载完成 | 用户要求参考覆盖同段活动；处理耗时不计入时长 |
| 多文件已有结构，未定义参考聚合 | 一段全部对应 IMU 汇总后比较一个参考总数 | 文件切分和重传不增加参考步数 |
| 日统计未规定覆盖/跨日含义 | 明确已采集时段累计、缺失和跨午夜未分配 | 单一参考总数无法准确拆日；完整目标日需另有覆盖证据 |
| 至少5次走路＋5次跑步的历史数量要求 | 走路、跑步、同日多次、零步、误混、恢复及逐级长时场景矩阵 | **已确认改变**：验收按场景和数据质量，不预设固定次数；正式人数与周期由研究安排决定，不另设软件试运行阶段 |
| 无明确长时设备验证方案 | 量化电量、Flash/手机空间、时间/信号连续性和下载/解码耗时 | 时长自主，支持边界由实测给出；接近一天仍待验证 |
| 当前规格仍称云盘配置及构建待落实 | 引用原版重新构建、配置生效和电脑接口上传记录 | 保留已有证据；手机采集上传、读取和独立版本仍待验证 |

旧规格和本轮核对的源码中未见“必须整日/固定时长”的要求；当前采用灵活时长和实测边界，避免把示例时长或讨论的“两天/七天”变为限制。旧代码的48小时时间修正窗口也不代表硬件能力。

总纲由 1.1.0 修订为 2.0.0：采集单位内的活动语义、参考值有效性和分析契约发生变化，按既有修订规则提升主版本。`29486e6` 保存修订草稿，用户随后确认总纲；项目总纲继续唯一维护使命、技术栈和总体路线，三份规格引用它。

## 2. 原版代码的影响（规格修订时的只读核对）

以下行号对应规格修订时的原版 0.5.3 工作副本；脱敏源码已在 `2ac4688` 保存。链接指向当前工作文件，后续修改后的行号可能变化，原版依据以该提交及 original 归档核对。它们是静态事实，运行可靠性仍需验收。

| 组件与实际位置 | 当前行为 | 待调整及验收 |
| --- | --- | --- |
| [CapturePurpose.kt:13](../../android/app/src/main/java/com/nexthci/ringfitness/CapturePurpose.kt)、[MainActivity.kt:377](../../android/app/src/main/java/com/nexthci/ringfitness/MainActivity.kt)、[RingCaptureService.kt:504](../../android/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt) | 六个旧活动枚举；界面与服务均要求选择单活动 | 统一开始、free_living与未标注语义；B05/B06/B12 |
| [MainActivity.kt:793](../../android/app/src/main/java/com/nexthci/ringfitness/MainActivity.kt)、[RingCaptureService.kt:577](../../android/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt) | 结束可立即上传/暂存/删除；下载前等待主观评价 | 改为停止确认→读数稳定→保存参考→下载；简短异常独立于评分；B07/B08/B15 |
| [RingCaptureService.kt:817、1023、1752](../../android/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt) | 开始/接管用手机时刻；下载结束和最终整理时写 ended_at_ms | 增加边界来源/不确定状态；恢复或下载不能改写真实边界；B06/B08/B16 |
| [HealthFlashDownload.kt:206、225](../../android/app/src/main/java/com/nexthci/ringfitness/HealthFlashDownload.kt)、[health_raw_v2.py:116、177](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/app/health_raw_v2.py) | 包时间非正或相隔超过5分钟时推算为连续；首包候选有48小时窗口 | 审计原始时间、uptime与修正记录，保留真实缺口；B11/B16 |
| [UploadSessionStore.kt:74、94、406](../../android/app/src/main/java/com/nexthci/ringfitness/UploadSessionStore.kt) | 固定旧活动schema；已有captures数组、JSON临时写入；未实现ground_truth/异常状态 | 新契约与崩溃恢复；session顶层唯一参考；B07/B08/B13 |
| [UploadWorker.kt:188、238、264](../../android/app/src/main/java/com/nexthci/ringfitness/UploadWorker.kt) | 复用ZIP缓存；含rfbin用manifest v2；输出旧活动schema；任务有自动/手动重试 | 新元数据和确认/冻结/缓存失效；重试保持相同内容；B07/B09/B10 |
| [RingCaptureService.kt:518、978、1547、1633](../../android/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt) | 待传锁、下载断点、BLE重连/状态恢复已存在 | 保留机制并补实物证据；Flash落盘后上传排队与下一次采集解耦；B06/B08/B09 |
| [DailySummaryStore.kt:212](../../android/app/src/main/java/com/nexthci/ringfitness/DailySummaryStore.kt) | 基于BLE连接区间计算时长/样本/断连，未实现参考步数日累计 | 连接情况、真实信号覆盖和日参考累计分开；B09/B14 |
| [app.py:81、194](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/app/app.py) | 仅接受daily_activity_v1与六个旧活动；没有步数契约校验，manifest.version也未显式分路 | 新旧契约/字段/类型显式校验，合法异常档案可入库，未知版本隔离；B11 |
| [label_daily_activity.py:20、143](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/scripts/label_daily_activity.py)、[test_label_daily_activity.py:31](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/tests/test_label_daily_activity.py) | 每行继承整段活动，other=0是已知类别；已有walking标签测试 | 新自由活动分支真值留空，保留历史路径；算法标签另存来源；B12 |
| [app.py:889、899、965](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/app/app.py) | 同ID同ZIP哈希跳过、异哈希冲突；同session保存多文件；先批量扫描再写历史 | 复用关联/幂等，补同批去重、冲突及不同ID重叠检查；B13/B14 |
| [app.py:428](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/app/app.py) | 按CSV文件名日期归档，无参考步数日汇总 | 归档日期不充当整段参考的日分配；B14 |
| [sync_all_and_label.py:327、520、530](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/scripts/sync_all_and_label.py) | 主入口加载Oura/Junction并同步睡眠与活动 | 活动独立入口待开发，复用本地导入器；B10 |
| [UploadWorker.kt:510](../../android/app/src/main/java/com/nexthci/ringfitness/UploadWorker.kt)、[app.py:75](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/app/app.py) | 存在30分钟上传读取超时、5000文件/20GiB解压/2MiB清单边界；原始处理部分采用流式 | 测实际包量、手机空间/内存、传输与解码；代码阈值单独核对，最长采集时长由设备实测；B16 |

上述只读核对时Android与后端均无ground_truth实现。T2-P现已补Android共享账本与参考保存，见第10节；真实manifest与后端新契约仍待接通。原manifest原样留存或主观评价recorded_at_ms不等于参考数字契约已完成。

## 3. 可推进事项、审阅选择和缺少的证据

**可按已确认需求推进的方向：** 自由活动统一入口、每次清零、自主开始结束、编号/位置记忆、整段一个非负总数、异常/缺失区分、旧依赖退出、跨文件归属、未标注语义、已采集时段日汇总、恢复与长时验证设计。T1准备与T2-P演示已完成各自软件范围，真实链路按plan推进。

**当前交互结论：** Q01已确认空闲切换只影响新记录；Q02依据步数采集交接说明列为应恢复，上传首次尝试前允许修订并保留原值审计；Q03已由用户确认，无法读数时保存缺失及原因并继续保全数据。原版Android 0.5.3没有计步器字段，Q02的依据来自交接说明。

**待补设备或运行证据：** 手机/固件/计步器型号及佩戴说明、真实开始/停止和计步器延迟对应、电量/容量/满后行为、长时信号连续性、长记录下载恢复、手机采集上传及云盘读回。连接断开能否继续采到信号按Flash实际内容判断。

后端读取所需资料库/目录已有[核对记录](../../docs/upload-target-verification-20260918.md)，读取凭据和本机路径按本地配置管理。正式人数与周期由研究安排决定；可用时长及丢样／时间容忍阈值以开发验收中的设备实测为依据。

## 4. 历史文档检查结果（29486e6）

| 检查 | 结果 |
| --- | --- |
| 版本与组织 | 总纲2.0.0修订草稿与三规格引用一致；历史资料保持原状 |
| S/T/B对应 | 12条需求、5个实施/验证切片、17条顶层验收；双向映射无缺项，T4明确为联合验证 |
| 文档/源码链接 | 38处相对链接均可定位，0失效 |
| 源码核对与独立审阅 | Android、Python分别只读核对；独立审阅发现的停止边界保全、冻结时点和映射遗漏已修正，复核无阻碍交付的新冲突 |
| 敏感配置 | 5份本轮文档均不含实际上传链接/口令；local.properties仍被Git忽略且未跟踪 |
| 格式与范围 | git diff --check通过；本地提交范围限定总纲、三规格和本说明，原有代码/归档/本地配置保持原状 |
| 业务运行 | 本轮未执行构建、自动功能测试、真机采集或上传；历史构建和电脑接口上传仅证明原记录中的能力 |

该提交保存了当时的待审阅稿。用户随后确认总纲并授权准备流程实现；Q01–Q03 仍单独待确认，设备时长与数据质量仍待实测。该表不包含后续代码测试和构建结果。

## 5. Android 基线与 T1 准备切片

`2ac4688` 完成 Android 源码受控、原上传目标脱敏及忽略规则补齐。[桌面基线](../../docs/android-baseline-20260918.md) 记录原版 25 项 JVM 测试通过和 Debug 构建通过；设备/模拟器不可连接，Python 和真机检查继续待执行。原版证据与后续独立版结果分别保留。

| 原版行为/风险 | 本轮处理 | 证据与验收边界 |
| --- | --- | --- |
| 旧 ID 与名称，打开依赖远端用户服务 | 独立 ID `com.nexthci.ringfitness.steps`、名称“步数采集”，离线登记后进入准备页；版本暂定 `0.6.0-t1` | APK 元数据和导航检查；真实共存、升级与离线 UI 需设备执行 |
| 佩戴位置只在页面状态暂存，旧登录按版本重新确认 | PreparationStore 保存规范编号、installation UUID、确认位置和所选戒指；连接状态重新查询 | 自动测试覆盖重建存储读取、重复登记、关联与错误输入；进程/手机重启另取设备证据 |
| 关键准备信息保存失败或损坏可能被当成未登记 | 原子写入与校验和检查；失败不提示成功，损坏保留并阻止重新登记覆盖 | 注入失败/损坏的回归测试；实际文件系统与异常退出行为按 validation 记录 |
| RingCaptureService 在 IDLE 收到正在采集状态时自动接管，并以当前编号创建 session | 独立 RingPreparationController 复用 RingBleClient，只查询电量、固件、HEALTH STATUS；独立入口不绑定旧采集服务 | 传输替身检查只读命令、迟到回调与设备切换；真实 BLE 待设备验证 |
| STATUS 字节数/记录数不含下载回执，无法确认数据是否已经落手机 | 有记录显示待核对，正在采集或状态错误也不宣称就绪；不接管、不删除、不创建 session | 状态矩阵回归；研究者核对现有记录，设备内容保持原状 |
| 原流程可以开始、下载、评价并上传，尚缺自由活动参考契约 | T1 仅完成准备，开始按钮保持禁用；后续 T2 实现采集边界、身份快照与参考数字 | 本轮不进行 session/步数/上传验收，完整流程仍按 B06–B16 等后续项取证 |
| 公共文件目录仍沿用 RingFitness | 各公共输出路径统一为 RingFitnessSteps；独立私有目录随新 ID 隔离 | 目录代码核查与设备文件测试分开；原版文件未覆盖需设备实证 |

本轮文件影响集中于独立启动界面、AndroidManifest/build.gradle.kts、PreparationStore、RingPreparationController、公共目录配置及相应测试。底层 BLE 协议和原采集/下载/上传实现保留，后续数据闭环再逐步调整。Q01 确认前不提供编号更换；Q02/Q03 对应 T2 交互，不阻塞首次登记和设备准备。

本轮自动测试、构建、模拟验证、独立代码审查、修复和真机条件的实际结果统一记入 [validation.md](validation.md)，仅已执行且有证据的项目记为通过。本节说明改动与边界，不把准备信息保存等同于 session、参考步数或长时采集已完成。

## 6. T1 蓝牙稳定性与可见反馈（2026-09-19）

旧准备版再次出现连续控制命令提交失败。`RingBleClient` 原实现请求 MTU 后立即通知就绪，且重试等待可能被新入队命令绕过，无响应写依赖固定间隔而忽略本地回调。本轮新增 `RingGattCommandQueue`：初始化成功后就绪，逐条等待写回调，仅对未受理的提交按间隔重试；已受理命令失败或超时关闭通道，交上层查询实际状态，避免盲目重发 START。准备版版本为 0.6.1-t1（27）。

新增队列 17 项回归测试，与已有 63 项合计 80 项通过；Debug 与测试 APK 构建通过。华为 P40 Pro+ 上 6 次页面重连均收到电量、固件 1.2.66 和 STATUS；旧记录量未变，未开始采集。准备档案在原位更新及进程重开前后哈希相同，3 项 Android 界面/文件测试通过。追加自动 BLE 循环与后续构建分别记录于 validation，避免把中断任务计为通过。

计划和验收新增 B17–B19：每切片展示真实 APK 页面并补行为证据、BLE 稳定性、指定旧记录的一次性准备。模拟器组件/AVD 已配置，运行受当前虚拟化与软件模式崩溃限制；真实 UI 验证目前使用手机。`Invoke-AndroidUiCheck.ps1` 从新 UI 树选择目标，独立审查后补上包名和可见边界检查，17 项合成 XML 检查通过。Q03 同步为已确认的“缺失原因＋保全信号”，总纲无需重写。

独立审查的范围限制：Gatt 回调改到主线程使旧实时 IMU/PPG 路径的同步文件写入也进入主线程；该路径在准备版未启用。后续启用前须隔离文件 I/O 并验证。HEALTH 下载原有主线程小块写入是历史限制，本轮未回归下载。上次故障唯一根因及长期连接稳定性仍待更大范围证据。

## 7. T2a 请求账本及审查修复（2026-09-19）

新增 `FreeLivingSessionStore` 与 JVM/Android 测试，显式依赖 Gson 2.11.0 严格解析。账本保存单个尚未完成原始数据保全的 session，冻结编号、安装身份、位置与戒指；请求/确认与设备边界分列，未知时间和参考值为 null，场景为 free_living/unlabelled。重开恢复和重复操作保持身份，停止后不释放待传保护。当前页面和蓝牙仍未调用该账本。

独立审查发现并修复了原子替换后缺少目录同步的问题，同时要求目录预先存在；目录同步失败及后续幂等重试都不会误报成功。读取使用严格 JSON 并检查尾随内容；时区偏移保存采集时快照，避免未来时区库更新误判旧记录损坏。新增 31 项 JVM 测试通过，连同已有 80 项合计 111 项通过；Debug 及测试 APK 构建通过。Windows 用目录同步替身检查状态/失败行为，Android 默认实现另设设备测试，不混同证据。

后续边界：账本还不能补入下载后才获得的时间证据；首次 STATUS 与新 START 的关联由下一步控制器验证；没有步数填写、下载、上传和完成释放接口。物理断电、真实采集和防覆盖机制仍需实测。本轮只保存经过软件验证的底座，不开放正式采集。

## 8. T1b 准备页面与导航（2026-09-19）

原准备页同时展示登记、佩戴、设备状态与多个操作，难以判断下一步。本轮将 `StepPreparationActivity` 分成首次登记、佩戴位置、连接检查、准备概览四页。已登记用户冷开进入概览，每页主要操作固定底部；写入成功后再跳转，取消放弃草稿，同值位置允许继续。设备信息放入详情，等待、超时、已有记录和未连接分别给出对应动作。版本更新为 0.6.2-t1（28）。

独立审查后修复扫描取消/离页后的过时提示、已连接异常时不恰当的连接文案、设备详情计数遗漏，并补齐部分查询超时的重试入口。底层BLE协议/队列、PreparationStore和T2a账本保持既有实现，页面不创建session或发送START/STOP。

111项JVM测试、Debug和测试APK构建通过；API31模拟器10项Android测试通过，其中新增6项导航/失败恢复/设备状态UI测试。真实触屏输入、键盘可达、搜索取消、返回、保存后强停重开另外实测，原模拟器档案经哈希核对保留。Android默认session存储测试已补执行通过；完整采集与物理断电继续待验证。新版真机复查受锁屏限制，历史连接证据保留原版本范围。实际命令、证据和未覆盖项见 validation 第9节。

## 9. T1b 精简为登记并直接连接（0.6.3-t1）

四页准备流程存在重复保存、连接按钮只负责跳转，以及查询完成后仍需确认的问题。本轮合并首次编号和位置，主按钮改为“保存并连接戒指”；保存成功后检查系统条件并搜索，选中戒指可靠保存后回首页连接。首页显示一处实时状态，连接完成自动更新，取消、失败和待核对各有明确出口。

| 涉及组件 | 变更及设计依据 | 验证范围 |
| --- | --- | --- |
| `PreparationStore`、存储测试 | `register(raw, placement = null)` 首次一次原子保存编号与位置；兼容现有格式及调用。已有同编号原样返回，保持 UUID、位置及戒指，异编号继续拒绝 | 编号与位置共同落盘、写入失败无半份档案、编辑后重试、重复登记不覆盖 |
| `StepPreparationActivity`、导航测试 | 页面缩为登记、首页和设备选择；位置单选即保存，成功才关闭，失败恢复原选项并可重试。移除中间保存和完成确认，保留保存忙碌保护 | 首次路径、非法输入、原子保存、重建草稿、位置取消/同值/失败/重试、进程重开 |
| 首页状态与只读连接 | `HomeUi` 统一状态及主要动作；三项设备回复未齐不显示准备完成。完整档案冷启动在前台且前置齐备时自动尝试一次，取消和重建不循环；失败可重试，系统条件恢复后清除过期提示 | 只读连接次数、回复先后、等待防重复、超时、取消后无自动重连、准备完成门禁；真实 BLE 单独取证 |
| 设备详情与选择 | 地址、固件、记录计数、系统设置和更换戒指按需展开；选择成功落盘后才连接，离页隔离迟到扫描回调 | 搜索/停止/返回、选择保存失败、切换后状态及身份一致 |

本轮未改变总纲、编号切换或参考纠错决策，未接入 T2a 账本、START/STOP、参考数字、下载和上传。已有准备信息原位保留，底层协议与 BLE 队列继续复用。自动测试、构建、模拟器及真机结果集中记录于 validation；本节不将正在执行的检查记为通过。后续按 plan 的 T2b–T2e 接通数据链路，真实采集入口以停止、参考保全和原始记录保护均可用为前提。

## 10. T2-P完整原生流程与持久恢复（0.6.5-t2p）

准备页增加显式开发入口，在同一App完成登记、开始/停止等待、整段参考、真实本地保存、合成下载及模拟上传回执。页面采用Quiet UI：柔和背景、卡片分组、统一字号/间距、单一主操作和简短提示；内部阶段码、恢复判据留在实现与验证记录。

| 组件 | 变更与依据 | 验证及后续边界 |
| --- | --- | --- |
| `CollectionFlow`、`StepCollectionActivity`、`QuietUi` | 分离状态/意图与页面呈现，复用同一原生页面供后续真实owner接入；状态更新保留正在编辑的输入，返回不等于停止 | API31真实页面操作及8项新增Android行为测试；真实服务尚未接入 |
| Debug演示Activity/runtime/device store | 非导出Debug入口，进程级串行owner管理模拟设备和传输；独立测试目录、合成文件与回执，全程可见演示标记 | 23项runtime JVM、Release源集隔离、正式档案哈希；持久模拟设备UUID只适用于演示恢复 |
| `FreeLivingSessionStore` | 在原子账本中保存参考、完整文件清单与传输状态；journal v2校验覆盖当前及归档session，读兼容v1、后续写v2。`reference_saved_at_ms`记录参考数字或缺失/异常说明的确认时间，`ground_truth_recorded_at_ms`仅记录数字确认 | 新增23项行为回归及Android默认存储；参考锁定后文件/传输仍可推进，真实manifest迁移由T2e/T3处理 |
| `FreeLivingCaptureCoordinator` | 恢复和新开始使用`readPending()`；已有本地完整段可归档，新段仍保护至完整落盘 | 多session及归档重试回归；共享store核验提供的清单，实际Flash全部记录完整性由T2d证明 |
| 版本与文档 | 版本31/0.6.5-t2p；更新现有README、需求、计划及验收记录，保留历史证据范围 | 193项JVM、25项Android及Debug/测试/Release构建通过，详细条件和限制见E15 |

独立审查后的修复集中于本地完整判定、在途标志释放、历史传输重启、输入稳定性及事件时间依赖，均增加或补充相应行为检查。真实BLE协议、原Flash格式和旧云盘组件保持原实现，准备页正式开始仍关闭；后续以相同页面和账本接通真实服务、停止/参考保全与下载出口。

## 11. 入口层级与当前任务反馈（0.6.6-t2p）

准备页的开发演示与连接操作并列，容易被理解为连续步骤；恢复首页也未区分采集中、待填数和待下载。Debug演示改从“更多 → 流程演示（模拟）”进入，主操作沿用真实准备/连接。首次未登记、蓝牙条件不足时仍能访问演示，Release沿用设备详情。

`CollectionFlowState.taskPage`表达实际任务阶段，`page`表达当前浏览页。演示owner在首页更新任务结果，查看在途任务只导航；开始、保存和下载继续守住原记录，新段可在旧段本地完整后开始。保护新段开始检查不被旧上传回执覆盖。页面相应显示“待填写步数 → 填写步数”“待下载数据 → 重试下载”等明确路径；最近记录只展示本地完整段，在途上传不提供重复重试。

采集中与填数页删除多余说明，0值仍按原校验保存；开始时间使用原确认时间和session时区，统一显示为纯数字`yyyy-MM-dd HH:mm`。存储格式、参考含义、真实边界和BLE协议保持原义。验证包含任务恢复、日期跨午夜、时区切换、菜单可达和隔离；结果见E16。新增Kotlin编译缓存纳入已有忽略规则，原始运行证据保留本地。

## 12. 全流程五色主题（0.6.7-t2p）

按已确认色板替换旧配色：灰色背景、米白卡片、鼠尾草绿状态、深青主操作、深蓝文字。具体色值与对比度集中维护于requirements的最小视觉约定，QuietUi和Android资源主题共同实现；准备页、完整流程页、输入、菜单、下拉选择及弹窗保持一致。状态区使用深蓝字，演示标记采用深蓝底米白字；失败保留明确说明与重试，禁用状态保持文字可读。

修改范围为共享视觉组件、准备与采集页面样式、系统主题资源及版本文档；既有导航、存储契约、设备命令和模拟隔离沿用原实现。窄屏与放大字体检查推动按钮/选项按内容增高、计时单行适配和步数输入字号调整，系统栏与键盘占位统一由根布局处理。验收检查配色与文字对比度、代表性页面及系统控件、原路径操作和数据保留，并独立审阅本轮差异。构建、自动检查、模拟器及真机的实际执行范围分别记录于E17。完成本轮后继续真实采集与原始数据落手机的既有计划。

## 13. 未确认开始的恢复与状态区分（0.7.3-start-recovery）

未确认的开始请求会持续保护待处理数据；当用户确认只是体验操作，仍需以新连接的完整设备观察和已保存原始文件为依据收尾。新增“结束本次尝试”入口，先记录原因，再复核开始基线、文件长度、SHA及CRC，成功保存审计后返回准备页。设备记录变化或证据不足时继续保护原请求。v4仅扩展本地审计，研究manifest保持v3快照，归档尝试退出研究记录与上传路径。

页面分别表达蓝牙连接和采集就绪：已连接但设备返回异常时显示“戒指暂未就绪”，首页以“重新检查”重连查询，同一错误只呈现一次。软件、独立审查及真机归档、重开、数据保全与最终页面结果见[验证记录E21](validation.md#20-未确认开始请求的受控归档2026-09-19e21)。实测设备仍返回-16，含义和恢复方式待核实；界面修复与设备恢复分别验收。

## 14. 启动时序与有限错误复查（0.7.4-start-timing）

原始交接和基线中的`RingCaptureService`字节一致：空闲后等待500ms发送START，入队后1000ms首次查询，随后间隔1500ms，每次START尝试后最多3次STATUS、总计最多2次START；旧版对非零error仅作提示。新路径此前立即START并查询，且非零error阻止确认。历史两段及第三次体验开始前error均为0，第三次请求后7.311秒首次记录-16，随后约53分钟持续观察到该值及原记录指纹。缺少完整旧命令日志，现有证据只能支持核对时序和错误处理差异。

本轮在`FreeLivingCaptureCoordinator`中恢复500/1000ms等待，保持单次START及完整归属证据；同一空闲基线下最多3轮STATUS/LIST，后续间隔1500ms。`RealCollectionController`对空闲错误执行有限完整复查，error归零且完整空闲基线不变才恢复开始入口，发送START仍须通过既有保全预检；同时修复已有完整记录时“重新检查”跳到旧成功页的问题。等待、断连、关闭和迟到回调由相应模块隔离；`RingBleClient`与服务补充Debug单调时钟诊断。页面继续使用真实状态和现有恢复入口。

本切片沿用v4本地审计、研究manifest和冻结包契约，-16按未知错误保留。349项JVM、44项模拟器及相关构建沿用`e497dee`通过证据，本轮只补真机与文档。版本38原位安装后，初查、重新检查及强停重开各完成3轮STATUS/LIST，重试建立新连接，强停后建立新进程；持续空闲/-16时保持未就绪。安装和三条路径后的21个保护文件均与操作前相同，命令与数据保全独立复核通过；结果见[验证记录E22](validation.md#21-启动时序与有限错误复查2026-09-19e22)。本轮未发新START，500/1000ms实际设备时序仍待验证；下一项继续对照原版初始化、协议和只读证据排查。错误清除条件、-16触发条件及保留数据的恢复步骤继续待确认，实际恢复操作以明确的数据保全依据为前提。

## 15. 原版与 SDK 对照及本地恢复修复（2026-09-21，E27）

依据总纲3.3.0，对照原始Android 0.5.3、配套后端、步数采集交接说明和Python SDK `50549cc3`，复核当前工作区的用户流程、数据归属与恢复出口。Android 0.5.3是实验设计与设备行为的默认信任基线；Python SDK只补充原版Android未覆盖的协议证据。下表记录源码依据；软件、页面和设备检查结果集中见[validation](validation.md)，后续顺序见[plan](plan.md)。源码中的等待与校验条件分别说明软件行为，实际固件响应、Flash尾部稳定和可用时长继续按设备证据验收。

| 范围 | 原版／SDK依据 | 当前变化与复用边界 |
| --- | --- | --- |
| 原生界面与登记 | 原版[MainActivity:109、212、244](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/MainActivity.kt)以原生控件呈现页面，注册／登录调用远端用户服务 | 沿用原生界面；[独立安装身份](../../android/app/build.gradle.kts)和`PreparationStore`支持离线用户名、位置及戒指记忆，启动入口为StepPreparationActivity。旧平台账号、睡眠与外部设备流程退出当前被试入口 |
| 活动与三种收尾 | 原版[MainActivity:793](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/MainActivity.kt)在STOP前选择立即上传、暂存戒指或删除；后续收集主观评价 | 当前每段显式选择走路／跑步并冻结；[StepCollectionActivity:394](../../android/app/src/main/java/com/nexthci/ringfitness/StepCollectionActivity.kt)在停止确认后提供保存上传、保存稍后上传和二次确认放弃。两种保存均先持久化整段参考，再下载至手机；SAVE_LATER跨重开保持暂缓，手机完整保存后可开始下一段 |
| 放弃范围 | 原版[RingCaptureService.discardStoppedHealthCapture:1336](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)清理手机任务并明确保留戒指Flash；原版[HEALTH命令表:119](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingProtocol.kt)与SDK[命令表:460](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/core/constants.py)均只提供已核对的开始、停止、状态、列表及读取命令 | [FreeLivingSessionStore:225、240](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingSessionStore.kt)保存放弃标记、清理本段手机文件与任务，并排除重取和上传。戒指单条物理删除缺少协议依据，当前放弃按手机清理与排除语义执行 |
| 本地账本与参考 | 原版UploadSessionStore保存上传元数据及传输状态；原版停止／下载路径还承担采集结束信息整理 | [FreeLivingSessionStore](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingSessionStore.kt)以原子账本保存身份、活动、请求／确认、设备证据、参考、原始文件及传输策略；参考确认先于READ。当前账本v12兼容历史版本，研究manifest v2–v7和既有冻结包分别维护；有效零值、缺失与不可靠参考保留独立含义 |
| BLE串行与START等待 | 原版[RingCaptureService:813、2123](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)等待500ms发送START，1000ms后首查、后续间隔1500ms，最多两次START；SDK[control.py:492](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/ble/control.py)通过无响应GATT写入发送HEALTH命令 | 当前[RingGattCommandQueue:79](../../android/app/src/main/java/com/nexthci/ringfitness/RingGattCommandQueue.kt)等待本地写回调并串行执行；[Coordinator:554、581](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)保留500／1000ms等待和有限STATUS/LIST复查，以单次START及可靠保存的完整归属证据确认开始。命令受理、设备确认和研究边界分别记录 |
| STOP与Flash收尾 | 原版[RingCaptureService:1069–1127](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)在STOP后约500ms查询；仍采集时每约1000ms继续查询STATUS；首次收到stopped即进入FINALIZING，约5000ms后才查询LIST，并对记录迟到另做有限重试 | 当前[FreeLivingCaptureCoordinator](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)已恢复同一软件顺序：500ms后只查STATUS、仍采集每1秒续查、首次stopped后等待5秒再查LIST，LIST迟到每2秒重查最多30次；STOP命令入队、高水位和重开恢复证据持久化。自动测试通过，真实固件尾部与断线行为待真机复测 |
| TIME与恢复 | 原版Android 0.5.3没有TIME SET/GET；Python SDK[time_sync.py:28](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/core/time_sync.py)定义SET／GET／STATUS，[connection.py:145、184、324](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/session/connection.py)在连接／重连路径校时 | 当前真实服务按用户确认的手机时间基准启用开始前校时；[Controller](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionController.kt)保存TIME回复侧文件并绑定session，再复查空闲记录。该能力是依据Python SDK的已确认技术补齐；结束及重连只读TIME、统一开始锚核验与版本化上传证据继续按plan推进 |
| 下载与上传 | 原版HealthFlashDownload按8 KiB窗口读取并有限自动补缺，UploadWorker以持久任务对普通错误最多自动尝试5次；SDK[sensors.py:624](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/session/sensors.py)提供分窗读取接口 | 当前下载仍用16 KiB窗、超时后转手动恢复；完成后最终STATUS/LIST复核、尾部增长续传及跨重开处理已实现。上传已恢复最多5次持久自动重试，SAVE_LATER仍等待用户手动触发。手动入口仍受BLE权限前置影响 |
| 后端数据含义与校验 | 原版[app.py:81](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/app/app.py)接受六类daily_activity_v1；[label_daily_activity.py:128](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/scripts/label_daily_activity.py)将整段活动赋给样本，综合同步入口加载Oura | 当前[schema.py](../../backend/ringo_data/schema.py)显式校验v2–v7、走跑声明、唯一参考、停止来源与边界证据；[importer.py](../../backend/ringo_data/importer.py)独立导入原包、保留session关联并输出原始信号CSV和质量结果，逐样本活动保持未标注。START≤STOP≤最终记录的字节数／记录数约束已由`ce03677`实现；本轮测试补跨版本及兼容分支的回退拒绝、原包保留与合法非递减回归 |

E27已修复停止确认后断连仍可填数、输入焦点保持及后台完成后释放采集owner。当前工作区进一步完成设置写操作与真实owner统一门禁、STOP命令入队、来源审计及原版Flash收尾软件顺序、下载末尾最终STATUS/LIST复核、普通上传最多五次持久自动重试、上传前参考纠错，以及旧记录／已放弃记录不再直接形成文案阻断。Android全量565项、Python 506项及Debug／AndroidTest构建结果见E28。陌生旧记录开始前备份及手机边界入包仍是待编码缺口；STOP与Flash真机证据继续分开记录。

以下为静态核对仍成立的缺口。影响限定于可触发的软件分支；实际设备是否发生记录变化及其信号影响，须结合命令日志、设备记录和原始文件判断。

| 项 | 证据与影响 | 待实现／待验方案 |
| --- | --- | --- |
| A．采集中意外停止的收尾出口 | 当前[Coordinator](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)在同ID、error=0、计数不回退时启动完整只读定稿，保存`DEVICE_OBSERVED`来源并在5秒Flash等待后进入参考收尾；身份、错误或计数矛盾仍保留数据并拒绝归属 | **软件已实现，设备待验**。回归覆盖正常stopped、错误码、计数回退、重开及不发送第二次STOP；真机须验证固件自行停止、尾部增长与断线恢复 |
| B．TIME锚核验覆盖不一致 | [Controller.beginCapture:482](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionController.kt)仅为旧Unix0基线构造强锚证据；[Store.isDistinctStartRecord:749](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingSessionStore.kt)在普通基线遇新ID可直接接受。[Coordinator:589、696](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)允许Unix0、uptime非零的新记录进入COLLECTING，跨连接恢复又要求Unix非零，造成已开始任务的恢复缺口。TIME侧文件已普遍保存，缺口位于统一使用这些证据的确认规则 | 统一新开始的TIME与记录锚核验；时间矛盾或Unix0时保留受控停止与原始保全出口。覆盖空基线、普通旧记录、旧Unix0、新ID／复用ID、时间矛盾及跨连接恢复；同步核对上传证据 |
| E．手动上传入口仍经过BLE准备条件 | [StepCollectionActivity:342–370](../../android/app/src/main/java/com/nexthci/ringfitness/StepCollectionActivity.kt)已在唯一首页显示本地记录和手动上传入口；但[RealCollectionActivity:27–34](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionActivity.kt)仍在页面启动时统一要求BLE权限，权限撤回时无法进入记录页。后台上传已独立，用户主动查看和上传本地完整记录仍受BLE条件影响 | 将查看记录和手动上传从采集连接前置条件中分离；采集和下载继续在各自操作边界检查BLE。验证蓝牙关闭、权限撤回、仅网络可用时的查看、暂缓与手动上传 |
| F．历史卡日期已实现，验证待补 | [StepCollectionActivity:342–370](../../android/app/src/main/java/com/nexthci/ringfitness/StepCollectionActivity.kt)按`startedAtMs`显示日期、时间、活动、步数和状态；[StepCollectionActivity:795–797](../../android/app/src/main/java/com/nexthci/ringfitness/StepCollectionActivity.kt)当前按手机默认时区格式化历史时间 | 改为按session已保存的时区显示，并检查同日多段、跨日、手机时区变化及未知开始边界；完成行为验证后关闭本项 |
| G．原版后台恢复能力 | 原版采集中约每3秒持续重连，记录延迟出现时约每2秒查询、最多30次；0.8.4恢复未完成session持续重连，并覆盖连接立即失败、异常、重复和迟到回调；STOP后LIST等待已对齐。通用下载迟到与断线区间审计仍未完整对齐 | 持续重连由现有单owner调度，不启用第二套BLE重连循环；每轮先核对原记录，不重发START。软件回归与实机结果见E31；继续验证后台、进程重建、手机重启、长时离线后恢复及记录迟到 |
| H．长时系统保护不完整 | 当前采集服务存在，但通知权限、电池优化豁免和长时上传前台保护尚未形成完整用户路径 | 在支持系统上检查通知权限与电池优化状态；为长时上传提供可观察保护。验证拒绝、系统回收、重启和网络恢复 |
| I．卸载／清数据会删除私有记录 | session、参考、rfbin和上传队列位于应用私有目录；原位升级可保留，卸载或清除数据后无法自动恢复 | 正式交付前提供并验证卸载前保全／导出与校验；未保全记录禁止把卸载重装作为恢复建议 |
| J．上传前参考纠错 | 步数采集交接说明要求上传前可修改参考；原版0.5.3没有计步器字段 | 当前工作区已恢复未尝试上传记录的修订入口、原值审计和ZIP失效；修订与上传冻结共用发布锁，上传取得发布权后读数即冻结。自动回归和模拟器成功／失败反馈仍待本轮补证 |
| K．手机采集边界未进入新包 | 原版[RingCaptureService:813–821、1027–1048](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)在START命令受理时记录手机开始时间，在下载完成时写入手机结束时间和rfbin | 当前账本保存START/STOP请求、命令入队和设备确认时间，但`started_at_ms`、`ended_at_ms`只接受尚未生成的设备边界证据，正常正式包与rfbin多为空；TIME校时侧文件也未进入冻结ZIP | **应恢复**：保留原版手机边界并标明来源，设备精确边界另行表达 | 当前字段为空会削弱跨包排序、时长核对和研究端追溯。以新版本字段保存手机命令／确认窗口及TIME证据，不把手机时间宣称为精确样本边界；旧包空值保持原义 |

以上待验方案沿用已确认的分段、清零、唯一参考及三种收尾规则。下一步先恢复原版STOP顺序、手机边界和陌生旧记录非阻塞开始，再补齐持续重连／延迟记录、8 KiB补缺、统一TIME证据和卸载前保全；随后完成设备故障恢复、逐级长时及独立操作验收。意外停止的异常出口与下载末尾复核继续接受完整自动测试和真机验证。

## 16. 原版信任基线差异登记（总纲 3.6.0）

本表以 Android 0.5.3、配套后端、步数采集交接说明和 Python SDK 为基线，覆盖当前正式流程中的实质差异。后续设计和实现审查先查本表：界面精简只能改变呈现层级；实验字段、操作顺序、设备协议、Flash 处理或恢复规则发生变化时，必须补充批准依据、数据影响和验证证据。

用户名架构复核（2026-09-21）：原版`MainActivity.submitUsername`调用远端注册或登录，`RingFitnessApi`提交规范化用户名和installation ID，再读取服务端返回的`username`与`participant_id`；上传包同时保存二者，原版研究端主要按`participant_name`整理。独立版采用离线用户名：3–24位ASCII字母或数字，trim后按`Locale.ROOT`转为小写，并直接写入`participant_id`和`participant_name`。不同被试须使用不同用户名；同名跨安装得到同一编号。当前方案不接账号服务、不增加历史映射。现有历史记录均为测试数据，保留原文件但不迁移编号；运行中任务继续使用创建时冻结的快照。

分类含义如下：**应恢复**表示基线要求仍适用但当前尚未对齐；**已确认改变**表示项目负责人或总纲已明确批准；**证据不足**表示当前实现可能合理，但设备或研究证据尚不足以替代原版行为。

| 范围 | 原版／交接行为 | 当前行为 | 分类与改变依据 | 数据影响、证据及下一步 |
| --- | --- | --- | --- | --- |
| 身份 | 0.5.3通过远端注册／登录取得研究用户，只显示3–24位字母数字用户名；服务端另行返回`participant_id`，上传包保存用户名与编号 | 只显示一个用户名；本地trim并按`Locale.ROOT`转小写后，复用`RESEARCH_ID`路径直接作为`participant_id`和`participant_name`。不接账号服务，不保存历史映射 | **已确认改变**：负责人选择离线最简登记；页面字段沿用原版，编号生成方式明确不同 | 研究者须为不同被试分配不同用户名，例如`p001`，否则后端会把同名数据归为同一人。验证大小写／空格规范化、切换回来、退出重登、跨安装同ID及session冻结；历史测试记录不迁移、不改写 |
| App升级后的身份确认 | 0.5.3在versionCode变化后回到登录页，要求重新确认用户；服务端研究身份随后恢复 | 当前原位升级沿用本地身份、戒指、佩戴位置、session和上传任务 | **已确认改变**：负责人要求安装更新优先保留有效设置与新实验记录 | 避免升级中断采集，但须用连续两个versionCode验证身份与历史文件哈希不变、未完成任务仍归原session；卸载仍按独立保全规则处理 |
| 佩戴位置 | 明确保留左右手食指、中指、无名指，共六种，并写入手与手指字段 | 同样保存六种并冻结到session，另记住最近一次选择 | 六种位置为**应保持的基线**；记住最近选择为**已确认的便利变化** | 删除手指选项会改变实验字段，目前无批准依据。须验证最近值仅作为下一段默认值，不回写旧session |
| 戒指选择与连接 | 原版持久保存所选地址和名称；空闲冷启动仍回戒指页，要求搜索／选择并显式连接，只有活跃任务恢复时自动连接 | 选中后保存戒指并进入唯一首页；条件齐备的空闲冷启动自动尝试一次只读连接，由采集服务校时和检查 | **已确认改变**：总纲3.3.0允许收敛页面层级 | 原版并非完全不记戒指，差异在空闲冷启动是否自动连接。导航减少，但实际连接地址必须与保存地址一致；连接、切换、返回、自动尝试上限和进程重开仍需行为验证 |
| 设置与采集owner | 0.5.3在健康采集非空闲时拒绝切换用户；连接、采集及戒指任务由同一服务状态约束 | 当前工作区同时检查pending session与真实采集服务owner；连接、设备检查、保全、下载或保存期间只允许返回当前任务，owner释放后才允许切换 | **应恢复，软件已实现** | Android全量回归通过；真机还须验证设置页与服务竞态、进程重建及旧回调隔离，未取得这些证据前不记为设备通过 |
| 扫描候选信息 | 原版[MainActivity:294](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/MainActivity.kt)显示设备名称与RSSI，便于在多枚戒指中依据距离选择 | 当前[StepPreparationActivity:841](../../android/app/src/main/java/com/nexthci/ringfitness/StepPreparationActivity.kt)显示名称与地址尾号，RSSI未作为主候选信息 | **应恢复**：没有实验依据删除RSSI | 多戒指环境下地址尾号可追溯但不表达距离。恢复名称＋RSSI，地址尾号保留为辅助信息；验证刷新时选择对象稳定 |
| 设备详情 | 原版[MainActivity:340–343](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/MainActivity.kt)显示名称、RSSI、电量和固件，并可刷新电量 | 当前正式入口只展示保存的名称及连接／就绪状态；正式owner尚未查询或展示常规RSSI、电量、固件，完整地址和Flash计数也尚未形成可用详情。`RingPreparationController`中的相关实现当前不可达 | 原版四项为**应恢复**；完整地址与Flash计数为**已确认的技术补充** | 电量影响长时采集，固件、地址和记录量影响追溯。详情按需展开，不增加主路径步骤；验证数据来自当前连接而非缓存或死代码 |
| 活动范围 | 支持走路、骑车、跑步、工作、吃饭、其他，并含睡眠、Oura、Polar和评分流程 | 被试入口仅保留走路、跑步；每类为独立session，分别清零和填写参考步数 | **已确认改变**：总纲3.0.0及负责人明确选择“两次独立采集” | 活动枚举与原版不兼容，由版本化manifest和后端显式校验；旧平台、睡眠、外设和评分退出当前范围 |
| START时序与错误处理 | 空闲后约500 ms发START，约1000 ms首查，之后约1500 ms轮询；每次最多3查、最多2次START；非零错误仍进入有限尝试 | 保留500／1000／1500 ms和最多3轮完整STATUS/LIST；只发一次START；除严格的同连接charging `-16`恢复证据外，空闲错误阻止开始 | **证据不足**：单次START和更严格门禁保护归属，但尚未证明覆盖原版设备恢复能力 | 需用真实戒指验证正常开始、迟到确认、charging `-16`和断连恢复。现阶段不得宣称当前策略优于原版，也不得循环发送START试错 |
| HEALTH查询超时 | 原版START单轮约4秒，其他HEALTH命令通常约10秒 | 当前完整STATUS/LIST统一按30秒等待，超时后进入重连或恢复 | **证据不足** | 等待长度会改变失败反馈和重连节奏。分别记录命令发送、回复和超时，用两种手机及正常／迟到设备回复验证后决定保持或恢复原值 |
| 初次BLE连接超时 | 原版BLE客户端及采集服务没有同等的整段初连超时 | 当前[RealCollectionController:775–779](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionController.kt)在30秒仍未ready时主动断开并进入可重试状态 | 恢复出口属于技术增强，30秒阈值为**证据不足** | 用华为、三星覆盖正常、慢连接、戒指休眠和超时后重试；记录连接开始、GATT回调、ready及断开，确认阈值不会切断可恢复连接 |
| 连接就绪后的查询节奏 | 原版[RingCaptureService:1565–1581](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)在普通空闲连接就绪后约250ms查询STATUS、约600ms查询固件；电量查询按用户请求或恢复探测另行调度 | 当前[RealCollectionService:69–72](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionService.kt)在BLE ready回调后立即交给正式owner查询STATUS/LIST；不可达的旧准备控制器虽然能排入电量、INFO和STATUS，但不属于运行路径 | **证据不足** | 当前正式入口缺少原版固件／常规电量查询，立即STATUS/LIST也可能影响初始化和历史`-16`。用同一手机／戒指分别验证250ms与当前节奏，记录完整TX、回复与文件指纹；确认前保持差异显式可追溯 |
| STATUS扩展错误原因 | 原版兼容15字节STATUS并使用`collecting`、计数、`err_code`和`session_id`；第16字节不参与状态判断 | 当前继续兼容15字节，并依据Python SDK解析新版第16字节`error_reason`；只有同连接、5秒内、空闲`-16`、原因`charging`且电量接口确认未充电时，才允许一次兼容START | SDK协议解析为**已确认的技术补充**；用扩展原因改变START门禁仍需**真机验证** | 旧固件缺少第16字节时保持未知，不猜测原因。自动测试覆盖字段长度、符号错误码和证据组合；两枚戒指须验证charging原因何时清除、正常开始后错误归零及失败时不重复START |
| STOP与Flash收尾 | STOP后约500ms查STATUS；仍采集则每约1000ms继续查；首次stopped后等待约5000ms，再查询LIST并等待迟到记录 | 当前工作区已按原版顺序拆开停止确认与Flash读取；LIST迟到每约2000ms重查，最多30次。已受理STOP后的进程重开继续只读查询，不重复发送STOP | **应恢复，软件已实现** | 565项JVM覆盖持续collecting、LIST迟到、计数增长、已受理STOP重开和最终定稿。须用真机确认实际回调节奏、Flash尾部、断连和整机恢复后再记为设备通过 |
| 收尾选择 | STOP前选择立即上传、暂存戒指、删除或继续；“暂存”将原始数据留在戒指 | 先确认STOP，再选择保存并上传、保存稍后上传或放弃；两种保存均先下载到手机，网络上传独立执行 | **已确认改变**：总纲3.1.0及负责人确认的三分支 | 顺序变化提高手机端保全能力。须验证三分支、重启、删除中断和暂缓后手动上传；“放弃”只清理本段手机任务并排除重取，戒指Flash仍保留 |
| 参考步数与异常读数 | 原版Android 0.5.3无计步器字段；步数采集交接说明要求一个session对应一个整数，并允许上传前修改 | 支持有效0、缺失及不可靠读数；未尝试上传前可修改，保留原值审计并原子失效旧ZIP；参考修订与上传冻结共用发布锁 | 异常类型为**已确认改变**；上传前纠错为**应恢复，软件已实现** | 缺失不得当作0。上传取得发布权后冻结，已发送记录不得静默覆盖；弹窗须在持久化成功后才显示成功。完整自动回归与模拟器交互仍待本轮补证 |
| 独立session异常说明 | 原版结束时可填写本段活动细节；完整主观评分另属已退出范围 | 当前原因字段绑定计步器读数质量，无法独立表达误混其他活动、额外休息、佩戴中断或其他本段事件 | 窄范围session异常说明为**应恢复**；原版评分量表退出仍是**已确认改变** | 活动／佩戴事件不得写入计步器“不可靠”原因，以免改变参考值语义。收尾页提供简短可选说明，独立写入session质量与研究包；测试空白、重开、修订和后端读回 |
| 既有Flash记录与归属 | 0.5.3启动前查询STATUS，不以LIST中的既有Flash记录阻断新START，也不在开始前强制下载全部陌生记录 | 当前把完整STATUS/LIST作为START基线；陌生记录虽不再显示人工阻断，但[RealCollectionController:818–905](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionController.kt)通常会先逐条整段备份，数MB旧记录仍可能长时间占用入口 | 直接继续使用为**应恢复**；更严格的新段归属证据为**已确认的技术增强** | 只保护本App账本中的未完成任务；陌生旧记录作为只读基线，不归入当前被试。备份移到研究者工具或明确操作，不作为正常START前置；回归多记录、未知时间、ID复用和固件自然覆盖 |
| 陌生的正在采集任务 | 原版[RingCaptureService:1085–1091](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)在本地空闲／开始中观察到`collecting=true`时，会按当前profile和手机时刻接管该任务 | 当前[FreeLivingCaptureCoordinator:800–824](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)拒绝以正在采集的基线授权新START；没有本地pending时也不自动归属 | 自动归入当前被试会扩大错绑风险，当前保守策略为**证据不足** | 必须提供可执行的保全后停止、继续或导出路径，不能停在无出口恢复页。用同一戒指覆盖App重装、换机和服务账本丢失，确认研究身份规则后再决定是否恢复自动接管 |
| 放弃后的未知时间记录 | 0.5.3的“删除数据”只清理手机任务并保留戒指Flash，源码未以保留记录阻断下一次START | 当前工作区保留放弃标记并按设备指纹排除重取；`unix_ms=0`和跨连接不再直接锁住戒指，新段仍需同连接完整基线和可区分记录 | **应恢复，软件已实现** | 自动测试覆盖不重取、其他记录保持和继续开始；真机还须验证放弃后固件覆盖、重连和新段归属 |
| 下载窗口与补缺 | 原版[RingCaptureService:2138、2140](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)使用8 KiB READ窗口，并对缺失窗口作最多3次自动补读 | 当前[RealCollectionController:878、1137](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionController.kt)使用16 KiB窗口；超时后保留断点并转手动恢复 | **应恢复** | 不同窗口可能改变固件负载、超时率和长记录恢复体验。先恢复8 KiB及有限补缺，或取得真机证据后批准差异；验证断线、重复片段、缺窗和长记录 |
| 下载完成判据 | 0.5.3冻结目标记录后完成下载，没有当前增强的末尾设备复核 | 当前工作区在READ_END后重新取得完整STATUS/LIST；尾部增长继续下载，跨重开可继续，旧基线自然消失可接受，陌生新增或替换拒绝 | **已确认的完整性增强，软件已实现** | 定向测试及Android全量回归覆盖尾部多次增长、迟到READ_END、替换和重开；真机尾部增长仍待验证，原始文件与冻结ZIP保持原文 |
| 已停止记录跨连接续传 | 0.5.3持久保存记录编号、LIST锚和断点，重连后直接继续READ | 当前对设备日期0增加同连接门禁，造成已停止段无法续传；本轮以本段开始前TIME证据、当前只读TIME GET、完整STATUS/LIST及已下载前缀逐字节核对恢复续传 | 原版续传能力为**应恢复**；连续性核对为**技术增强，设备语义待验** | TIME为uint64、HEALTH uptime为uint32，采用模运算关联；连续性仅为组合恢复依据，SDK未保证其等同boot身份。参考值与原始日期0保持原义，恢复只查GET/STATUS/LIST/READ。软件覆盖重开、10小时虚拟时钟、回绕、重置、迟到回复及片段替换；真实固件跨重连与漂移另验。无原校时依据、设备重启及采集中未知日期恢复仍需独立保全出口 |
| TIME与实际时间 | 原版Android 0.5.3没有TIME SET/GET，但保存START受理与下载完成的手机边界；Python SDK在连接／重连路径提供SET、GET和STATUS | 当前只在新START前按手机时间校时并保存TIME侧文件；采集中重连不写时钟。请求、入队和确认时间在账本中，但新包的`started_at_ms`／`ended_at_ms`通常为空，TIME侧文件未冻结进ZIP | 手机校时为**已确认的技术补齐**；恢复原版手机边界及上传TIME证据为**应恢复**；重连期间是否SET为**证据不足** | 原版手机边界与设备精确边界须分来源保存。先在结束／重连做只读TIME GET，统一开始、恢复、结束和上传证据中的时间锚；真机验证漂移、重启及采集中连续性后再决定是否写时钟 |
| 历史时间显示时区 | 原版按查看时手机当前默认时区格式化历史时间 | 当前按session创建时保存的IANA时区显示，日期和时间使用纯数字格式 | **已确认的可追溯增强，软件已实现** | 用户换时区后仍能看到采集发生地的原日期，减少跨午夜误读。自动与模拟器测试已覆盖session时区；真机还须核对换时区、跨午夜和旧账本缺少时区时的回退 |
| 页面与后台任务 | 登录→戒指→模式→活动采集；采集中禁用返回，页面与采集服务共同管理流程 | 首次身份→选择戒指→唯一首页→同一采集页→同一收尾页；离开页面后服务继续持有任务 | **已确认改变**：总纲3.3.0的极简导航 | 页面可退出不等于任务终止。后台owner、账本恢复、停止出口和保存结果必须与页面状态一致；禁用外观不得伪装成可点击按钮 |
| 连接与就绪语义 | 原版将BLE连接和可执行采集状态分阶段呈现，操作入口由实际状态决定 | 当前工作区统一为“正在连接／连接已中断／已连接，正在检查／正在保存已有数据／可以开始／暂不可开始”；检查完成前不显示可开始 | **应恢复，软件已实现** | 模拟器验证同屏只有一个权威结论；真机仍须把实际GATT、STATUS/LIST与页面逐帧对应，每个阻断状态只保留真实可执行动作 |
| 设置入口与忙碌态 | 原版采集中禁止切换，但当前用户和设备信息仍可查看 | 首页空闲时提供“设置”；当前任务未完成时提供原位“信息”弹窗，关闭保持未保存的读数和原页面。身份／位置／戒指写操作统一核对owner | **应恢复，软件已实现** | 模拟器覆盖只读信息、读数草稿及owner接管；真机继续验证采集、下载、保存和上传各阶段的信息及返回目标 |
| 恢复页出口 | 原版按采集、传输、上传阶段提供继续、重连、重传或放弃等动作 | 当前部分恢复态没有可执行出口；服务停止后顶部“首页”动作可能失效 | **应恢复** | 每个恢复状态绑定可测试动作及目标页；服务不可用时重建owner或回到唯一首页。禁止只替换成“重开App”文案 |
| 记录列表与本地工具 | 原版[MainActivity:482、1055–1128](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/MainActivity.kt)提供完整列表、重传、删除本地记录和CSV导出 | 当前记录页展示该用户全部本地完整记录；普通上传失败可重试，稍后上传可手动触发。完整性校验异常保留并显示待检查，停止提供不能修复该问题的重试按钮；导出与无BLE权限独立上传继续待补 | 查看全部已恢复；导出及分类恢复为**应恢复**；删除本地记录为**证据不足** | 完整性异常的导出／修复出口尚未完成，继续列入交付缺口；普通失败、稍后上传及全列表由E30覆盖。删除须明确原始数据保全、云端回执及其他记录隔离后再开放 |
| 冷启动首帧 | 原版依据持久资料决定登录或主流程 | 当前工作区在档案与账本载入完成前显示中性进度，完成后一次进入登记或首页；保存中同样使用进度而非禁用按钮 | **应恢复，软件已实现** | 模拟器覆盖载入、保存、损坏副本恢复和正式入口冷启动；真机强停、低速存储及进程重建仍待验 |
| 错误与恢复入口 | 0.5.3按采集、传输和上传阶段显示具体失败，并保留相应重连、继续传输、放弃或重试入口；部分复杂错误仍需研究者处理 | 正式页面把所有含“联系研究者”的内部错误统一显示为“重新打开App后继续”；多条未知记录、设备信息不完整、时间证据不足和账本读取失败等状态重开后仍可能复现，部分状态没有可执行按钮 | **应恢复**：界面承诺的恢复操作必须与底层实际能力一致 | 虚假的重开建议会形成循环，并掩盖数据或设备阻断原因。每类错误应映射到已经实现且可验证的恢复动作；无自助恢复能力的状态保留数据并提供明确的导出／更换设备路径，不能只替换文案 |
| 旧MainActivity | 0.5.3的MainActivity承载原版登录、记录、导出和多实验流程 | 独立版启动入口已不可达旧MainActivity，但旧代码仍参与编译 | **证据不足**：暂留作行为参考，不作为当前运行路径 | 团队容易误把不可达代码当现行实现。文档和测试明确入口归属；完成对照与迁移后再决定隔离或删除，架构调整须单独审阅 |
| 正式包中的旧服务 | 原版只使用`RingCaptureService`和`UploadJobService`承担旧采集／上传契约 | 当前正式Manifest同时注册新的`RealCollectionService`、`RealUploadService`和两项旧服务；旧Activity虽不可达，旧服务仍可被包内代码启动 | **应恢复**：正式独立版只能有一套采集与上传owner | 发布前把旧服务从正式Manifest及可执行源集隔离，原版源码保留为只读参考；增加正式build无法启动旧采集／上传服务的回归，避免两套账本和两个BLE owner并存 |
| Manifest与隐私 | 上传用户名、服务端`participant_id`、六类`daily_activity_v1`及主观评价，没有参考步数字段 | 版本化manifest将规范化用户名同时写入`participant_id`和`participant_name`，并保存走跑任务、参考质量、请求／确认边界、设备证据、佩戴位置与App版本 | **已确认改变**：当前研究范围及离线单字段规则 | 用户名进入研究包、CSV和研究索引，因此使用研究者分配的非敏感唯一值。新旧包按版本分路，冻结ZIP保持原文；后端继续严格校验session与设备、参考和文件关联 |
| 上传目标配置 | 原版构建脚本为活动上传提供硬编码默认云盘链接 | 当前默认值为空，只从被忽略的本地构建配置注入；无配置时允许完整本地保存和稍后上传 | **已确认改变**：总纲要求链接与凭据不进入Git | 发布APK验收必须读取构建产物确认目标已注入，同时扫描仓库无明文；无配置版本不得显示虚假上传成功 |
| 上传网络超时 | 原版连接超时30秒、普通读取60秒、上传读取最长30分钟 | 当前连接20秒、读写30秒；单次任务总期限为120秒加文件大小按16 KiB/s计算的时间 | **证据不足** | 当前有界期限利于失败恢复，但小／中包在弱网下可能早于原版中止。用真实包覆盖正常网络、限速、断网和恢复，记录首字节、总耗时与重试；证据形成前不把当前阈值视为设备支持边界 |
| 上传回执校验 | 原版读取响应数组第一项的名称和ID | 当前要求响应恰有一项、文件ID格式有效、服务端size与冻结ZIP字节数完全一致 | **已确认的完整性增强** | 严格回执阻止错误文件被记为成功；须用真实云盘覆盖正常回执、多项／缺字段／大小不符、回执丢失与幂等重试，原ZIP和任务绑定保持不变 |
| 走跑上传包命名 | 原版上传包名不承担本轮走路／跑步分段辨认职责，活动依赖包内清单 | 新走路、跑步session分别使用`ringfitness-session-walking-<session_id>.zip`和`ringfitness-session-running-<session_id>.zip`，各自冻结、排队、回执和重试；历史包不改名 | **已确认改变**：总纲3.4.0及负责人明确要求云盘中走跑为两个可辨文件包 | 文件名只辅助人工辨认，研究端继续以经校验的`manifest.activity_code`为权威。自动测试覆盖双包双任务、严格回执和旧包稳定；真实云盘两个对象及自动读回待B10验收 |
| 后端活动标签 | 原版接受六类`daily_activity_v1`，并可把整段活动标签写到每个样本；综合入口还同步Oura等数据 | 独立导入器接受版本化走跑数据包；`walking/running`只作为session任务声明，逐样本活动保持未标注 | **已确认改变**：负责人已确认走路／跑步独立session，总纲要求任务声明与逐样本真值分开 | 休息、起止过渡或误混不会自动标成走路／跑步真值。后续活动分类评价需另采明确时间标签；历史自由活动保持原义 |
| 后端异常时间处理 | 原版解码器在设备epoch异常或相邻跳变超过5分钟时，使用手机开始时间和连续采样间隔重建确定时间 | 当前保留原始uptime，正式绝对时间未知时留空，并另给带来源和区间的手机估计时间 | 手机对齐方向为**已确认改变**；稳定估计仍依赖“手机边界入包”的**应恢复**项 | 当前方法更保守，但手机边界缺失时无法生成稳定区间。补齐版本化边界后，对照同一原始包验证排序、间隔、跨午夜和日汇总资格；原始字节与旧冻结包不改写 |
| 研究端设备索引 | 原版导出可按原始session和设备文件核对；独立manifest已保存戒指地址、设备session及记录锚 | 当前导入保留原始manifest，但`reference.csv`和summary没有直接展开戒指、device session和记录锚 | **应恢复**：属于研究端可追溯性，不改变原始数据 | 补充稳定索引列并保持旧CSV兼容；用同一戒指ID复用、换戒指和重复导入验证，避免研究者仅凭文件名或时间猜测归属 |
| 普通上传失败重试 | 0.5.3把普通上传异常重新置为queued，交给持久JobScheduler指数退避，最多自动尝试5次；永久错误或达到上限后才进入failed | 当前[RealUploadQueue](../../android/app/src/main/java/com/nexthci/ringfitness/RealUploadQueue.kt)已恢复最多5次持久自动重试；永久配置／本地校验错误隔离，SAVE_LATER等待用户首次手动触发；任务v2持久区分已取得发布权与已开始HTTP，联网前取消或进程退出可释放claim | **应恢复，软件已实现** | 定向队列及565项Android全量测试覆盖传输层首次联网前取消、冻结包时进程退出、旧v1任务保守恢复、五次上限、手动续试、回执恢复和任务隔离；手机重启及真实网络中断仍待验证 |
| 上传完成后的本地副本 | 原版上传成功后删除临时archive，保留采集目录 | 当前保留raw、evidence、冻结ZIP及snapshot，完成后至少多留一份冻结副本 | 追溯性增强与长期空间成本并存，分类为**证据不足** | 冻结ZIP承担回执和重导核对，暂不删除。依据长时存储实测确定保留期、导出确认和按session清理规则；空间不足时必须保护未上传及未验证记录 |
| 采集中重连与断线审计 | 原版[RingBleClient:544、557](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingBleClient.kt)连接丢失后约每3秒持续重连，[RingCaptureService:1517](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)记录断线区间 | 当前活跃采集只进行有限重连，断线区间尚未完整进入session质量证据 | **应恢复** | 长时离线后可能停留在不可恢复状态，研究者也无法区分BLE离线与信号缺失。恢复持续重连和断线开始／结束审计，真机验证远离、返回和后台运行 |
| Flash记录延迟出现 | 原版[RingCaptureService:1145、2142–2143](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)每约2秒查询记录，最多30次等待固件生成可读条目 | 当前工作区已恢复每约2秒查询、最多30次的软件顺序 | **应恢复，软件已实现** | 自动测试覆盖立即出现、迟到和始终缺失；真实较慢固件、查询中断、进程／整机恢复仍待真机验证 |
| BLE无响应写回调 | 原版[RingBleClient:135、464](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingBleClient.kt)对WRITE_NO_RESPONSE按固定节奏推进，不依赖每次平台写完成回调 | 当前[RingGattCommandQueue:79](../../android/app/src/main/java/com/nexthci/ringfitness/RingGattCommandQueue.kt)等待本地写回调后推进，超时会关闭通道 | **证据不足** | 当前策略可增强串行性，也可能在不回调的Android实现上卡住。保持命令幂等边界，用不同手机真机核对回调、超时和重复发送后再决定保留或恢复原节奏 |
| BLE写回调非零 | 原版[RingBleClient:190–210](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingBleClient.kt)在WRITE回调非零且特征支持NO_RESPONSE时切换写类型重试；其他非零结果记录错误后继续队列 | 当前[RingGattCommandQueue:72–84](../../android/app/src/main/java/com/nexthci/ringfitness/RingGattCommandQueue.kt)遇任意非零回调即关闭连接，由上层重连和核对 | **证据不足** | 直接降级可提高兼容性，也可能重复已生效命令；关闭连接更保守但会扩大中断。华为、三星分别记录特征属性、callback status、设备回复与命令是否生效后决定 |
| MTU就绪门控 | 原版[RingBleClient:160–175](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingBleClient.kt)在CCCD成功并请求MTU 247后宣布ready，不等待`onMtuChanged` | 当前[RingBleClient:165–183](../../android/app/src/main/java/com/nexthci/ringfitness/RingBleClient.kt)与[RingGattCommandQueue:37–69](../../android/app/src/main/java/com/nexthci/ringfitness/RingGattCommandQueue.kt)要求收到MTU回调，约5秒无回调即关闭连接 | **证据不足** | 当前门控能明确协商结果，也可能让不回调的平台无法进入就绪。华为API31与三星API34分别记录请求、回调、实际MTU和失败行为后决定 |
| GATT回调线程与PHY | 原版`connectGatt`使用系统默认回调线程和默认PHY | 当前显式使用主线程Handler与`PHY_LE_1M` | **证据不足** | 主线程串行化便于状态一致，但可能改变厂商栈调度与无线兼容。用两种手机、两枚戒指覆盖连接、重连、长时与切后台，并保存连接参数证据 |
| INFO回复兼容 | 原版[RingProtocol:232–241](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingProtocol.kt)收到9字节基本头即可返回固件信息 | 当前[RingProtocol:288–313](../../android/app/src/main/java/com/nexthci/ringfitness/RingProtocol.kt)依据SDK要求完整`9 + 5 × count`组件；长度不足会丢弃整包 | **证据不足** | 严格解析可防截断，也可能让原版可识别的旧回复卡住准备。保存原始长度和声明数量，用现有设备覆盖0组件、完整组件与截断回复；基础固件字段可用时不应静默丢失 |
| SDK补充字段解析 | Python SDK的INFO解析要求`hw_rev==1`，TIME的`synced`把任意非零值视为true | 当前Android INFO接受任意`hw_rev`，TIME只接受0或1 | **证据不足** | 现有戒指回复尚未覆盖边界值。增加原始协议夹具并保存未知值；在固件证据明确前不静默改写或拒绝整条可用回复 |
| 长时后台与系统限制 | 原版包含前台采集服务及后台任务；目标是长时运行不被系统静默终止 | 当前尚未形成通知权限、电池优化豁免及长时上传前台保护的完整流程 | **应恢复** | 系统回收可能中断重连或上传。补权限／设置引导、可观察通知和恢复记录；验证拒绝、锁屏、重启、断网恢复与数小时运行 |
| 卸载与清除数据 | 现有独立版session、参考、原始文件和任务保存在应用私有目录 | 原位升级可保留；卸载或清除数据会删除尚未外部保全的内容，当前没有卸载前保全机制 | **应恢复** | 正式交付前提供可验证的导出／保全路径；未完成保全时不得建议被试通过卸载重装恢复。验证导出完整性、重导和其他记录隔离 |
| 验收采集次数 | 历史方案曾用至少5次走路和5次跑步作为数量要求 | 总纲3.2.0起改为场景覆盖及短时到长时逐级验证，不预设正式人数、周期或固定次数 | **已确认改变**：负责人要求软件完成后直接投入使用，研究安排独立决定 | 交付仍须覆盖走路、跑步、零步、误混、同日多段、恢复及长时场景；数量变化不得降低每个场景的数据完整性证据 |

当前实施顺序由上述分类约束：先以本轮模拟器证据和真机STOP复测收口现有工作区，再补手机边界入包及陌生旧记录非阻塞开始；正式交付前隔离旧采集／上传服务。随后修复自助恢复入口、设备详情、持续重连、通用下载记录迟到、8 KiB补缺、研究端设备索引和卸载前保全。START次数、`-16`兼容、陌生正在采集任务、查询超时、WRITE_NO_RESPONSE及非零回调、MTU门控、GATT线程／PHY、INFO/TIME解析和复杂记录归属保留原始数据后做两手机真机对照；冻结ZIP保留期依据长时空间证据确定。每次改变原版行为时，同步更新本表、requirements验收项和validation证据。
