# 采集需求修订：差异与代码影响

当前增量（2026-09-20，E26）：按已确认走跑分段及三种收尾更新总纲3.1.0。新建session明确选择走路/跑步；停止确认后提供保存上传、保存稍后上传与二次确认放弃。页面复用CollectionFlow，持久策略与放弃审计由SessionStore统一维护，后台恢复和队列执行都检查策略。旧自由活动及冻结包原文保持。

SDK及原Android复核：旧Unix未知记录须先独立完整保全；停止后沿用5秒Flash收尾等待；已核对的HEALTH协议未提供单段物理删除，原版discard只删手机任务。本轮放弃限定本段手机文件和传输，并保存排除标记。无当前采集任务的恢复页可直接返回设备页；时间异常但可证明本次START产生的采集增加一次保护停止与独立保全出口，不生成研究成功状态。实际测试、审查、跨连接限制及设备结果统一记录E26，详细历史由Git保留。

当前增量（2026-09-19）：在[总纲](../../CONSTITUTION.md)2.1.0下统一既有全流程页面与系统控件主题，版本0.6.7-t2p（33），见第12节；实际验证结果及限制记入validation的E17。完整演示与入口恢复的历史证据分别保留于E15/E16，设备确认、数据内容和回执仍为模拟。Q01/Q02状态保持原义，后续交付继续为真实采集与本地数据保全。

更新日期：2026-09-19。分支：`codex/free-living-session-spec`。工作流文档检查与各版本运行证据见[validation](validation.md)，当前进度见[plan](plan.md)。第1–4节保留 `29486e6` 当时的文档审阅依据；第5节起为逐次变更。历史段落按当时版本理解，当前决策以requirements第6节为准。原始资料和本地配置保留。

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
| 固定5次走路＋5次跑步验收 | 短时、混合、同日多次、零步、恢复及逐级长时矩阵 | 验收围绕新流程和数据质量；正式人数/周期在试运行后确定 |
| 无明确长时设备验证方案 | 量化电量、Flash/手机空间、时间/信号连续性和下载/解码耗时 | 时长自主，支持边界由实测给出；接近一天仍待验证 |
| 当前规格仍称云盘配置及构建待落实 | 引用原版重新构建、配置生效和电脑接口上传记录 | 保留已有证据；手机采集上传、读取和独立版本仍待验证 |

旧规格和本轮核对的源码中未见“必须整日/固定时长”的要求；本轮补上灵活时长和实测边界，避免把示例时长或讨论的“两天/七天”变为限制。旧代码的48小时时间修正窗口也不代表硬件能力。

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

**重要交互仍供审阅：** requirements的Q01（编号更换）、Q02（确认后纠错），建议分别采用“仅新记录改编号”“保留原值并附研究者修订”。Q03已由用户确认：无法读数时保存缺失及原因，继续保全数据；T2-P已验证软件保存路径，真实上传后续验收。

**待补设备或运行证据：** 手机/固件/计步器型号及佩戴说明、真实开始/停止和计步器延迟对应、电量/容量/满后行为、长时信号连续性、长记录下载恢复、手机采集上传及云盘读回。连接断开能否继续采到信号按Flash实际内容判断。

后端读取所需资料库/目录已有[核对记录](../../docs/upload-target-verification-20260918.md)，读取凭据和本机路径待配置。正式人数/周期、可用时长与丢样/时间容忍阈值，在试运行与质量检查后决定。

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

依据总纲3.2.0，对照原始 Android 0.5.3、配套后端和 Python SDK `50549cc3`，复核当前0.8.1-local-recovery（43）的用户流程、数据归属与恢复出口。下表记录源码依据；本轮软件、页面和设备检查结果集中见[validation 的 E27](validation.md)，后续顺序见[plan](plan.md)。源码中的等待与校验条件分别说明软件行为，实际固件响应、Flash尾部稳定和可用时长继续按设备证据验收。

| 范围 | 原版／SDK依据 | 当前变化与复用边界 |
| --- | --- | --- |
| 原生界面与登记 | 原版[MainActivity:109、212、244](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/MainActivity.kt)以原生控件呈现页面，注册／登录调用远端用户服务 | 沿用原生界面；[独立安装身份](../../android/app/build.gradle.kts)和[PreparationStore.register:33](../../android/app/src/main/java/com/nexthci/ringfitness/PreparationStore.kt)支持本地编号、位置及戒指记忆，启动入口为StepPreparationActivity。旧平台账号、睡眠与外部设备流程退出当前被试入口 |
| 活动与三种收尾 | 原版[MainActivity:793](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/MainActivity.kt)在STOP前选择立即上传、暂存戒指或删除；后续收集主观评价 | 当前每段显式选择走路／跑步并冻结；[StepCollectionActivity:394](../../android/app/src/main/java/com/nexthci/ringfitness/StepCollectionActivity.kt)在停止确认后提供保存上传、保存稍后上传和二次确认放弃。两种保存均先持久化整段参考，再下载至手机；SAVE_LATER跨重开保持暂缓，手机完整保存后可开始下一段 |
| 放弃范围 | 原版[RingCaptureService.discardStoppedHealthCapture:1336](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)清理手机任务并明确保留戒指Flash；原版[HEALTH命令表:119](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingProtocol.kt)与SDK[命令表:460](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/core/constants.py)均只提供已核对的开始、停止、状态、列表及读取命令 | [FreeLivingSessionStore:225、240](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingSessionStore.kt)保存放弃标记、清理本段手机文件与任务，并排除重取和上传。戒指单条物理删除缺少协议依据，当前放弃按手机清理与排除语义执行 |
| 本地账本与参考 | 原版UploadSessionStore保存上传元数据及传输状态；原版停止／下载路径还承担采集结束信息整理 | [FreeLivingSessionStore:478、494、598](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingSessionStore.kt)以原子账本保存身份、活动、请求／确认、设备证据、参考、原始文件及传输策略；参考确认先于READ。当前账本v9兼容历史版本，研究manifest v2–v5和既有冻结包分别维护；有效零值、缺失与不可靠参考保留独立含义 |
| BLE串行与START等待 | 原版[RingCaptureService:813、2123](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)等待500ms发送START，1000ms后首查、后续间隔1500ms，最多两次START；SDK[control.py:492](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/ble/control.py)通过无响应GATT写入发送HEALTH命令 | 当前[RingGattCommandQueue:79](../../android/app/src/main/java/com/nexthci/ringfitness/RingGattCommandQueue.kt)等待本地写回调并串行执行；[Coordinator:554、581](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)保留500／1000ms等待和有限STATUS/LIST复查，以单次START及可靠保存的完整归属证据确认开始。命令受理、设备确认和研究边界分别记录 |
| STOP与Flash收尾 | 原版[RingCaptureService:1122、956](../../original/RingFitness-0.5.3-Android-source/app/src/main/java/com/nexthci/ringfitness/RingCaptureService.kt)收到stopped状态后进入FINALIZING，再等待5000ms查LIST | 当前[Coordinator:166、611](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)发送一次STOP，5000ms后首查完整STATUS/LIST；仍在采集时最多再查两轮、间隔1500ms。两版等待起点有差异，见下表D，当前尚缺停止后尾部稳定的完整保证 |
| TIME与恢复 | SDK[time_sync.py:28](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/core/time_sync.py)定义SET／GET／STATUS；[connection.py:145、184、324](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/session/connection.py)在连接／重连路径校时 | 当前真实服务启用开始前校时；[Controller:124、471](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionController.kt)保存TIME回复侧文件并绑定session，再复查空闲记录。旧Unix为0的基线通过UnknownTimeStartEvidence核验新记录的Unix／uptime锚；普通基线的新ID分支仍有缺口B。结束及重连只读TIME、统一开始锚核验与版本化上传证据继续按plan推进 |
| 下载与上传 | 原版HealthFlashDownload与UploadWorker提供断点文件、打包和云盘任务；SDK[sensors.py:624](../../original/ring-python-sdk-50549cc3/src/ring_python_sdk/session/sensors.py)提供分窗读取接口 | [RealSessionDownload:116、138、145](../../android/app/src/main/java/com/nexthci/ringfitness/RealSessionDownload.kt)核对偏移、重放片段、完整长度／packet、CRC及SHA；[FreeLivingSessionPackage.freeze:54](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingSessionPackage.kt)冻结上传包。[RealUploadQueue:44、57、100](../../android/app/src/main/java/com/nexthci/ringfitness/RealUploadQueue.kt)各执行阶段检查持久策略，[RealUploadService:69、108](../../android/app/src/main/java/com/nexthci/ringfitness/RealUploadService.kt)通过网络任务独立上传。下载完成判据与手动入口分别待补C、E |
| 后端数据含义与校验 | 原版[app.py:81](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/app/app.py)接受六类daily_activity_v1；[label_daily_activity.py:128](../../original/RingFitness-0.5.3-Backend-source/backend/ringo_data/scripts/label_daily_activity.py)将整段活动赋给样本，综合同步入口加载Oura | 当前[schema.py:208](../../backend/ringo_data/schema.py)显式校验v2–v5、走跑声明、唯一参考与边界证据；[importer.py](../../backend/ringo_data/importer.py)独立导入原包、保留session关联并输出原始信号CSV和质量结果，逐样本活动保持未标注。START≤STOP≤最终记录的字节数／记录数约束已由`ce03677`实现；本轮[test_status_counters.py](../../backend/ringo_data/tests/test_status_counters.py)补跨版本及兼容分支的回退拒绝、原包保留与合法非递减回归 |

本轮已修的恢复问题集中在两条路径。[Controller.pendingReferencePage:1071](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionController.kt)依据已持久化的停止确认保留收尾和填数页，断连期间可保存参考；[StepCollectionActivity.render:120](../../android/app/src/main/java/com/nexthci/ringfitness/StepCollectionActivity.kt)在仅连接、重试或提示变化时更新提示，保持输入控件与焦点。[RealCollectionService:346](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionService.kt)在后台任务进展后重新检查页面占用及待处理任务，满足已请求释放且无页面占用、无待保存任务时释放采集owner，准备页可继续接管。实际回归范围以E27为准。

以下为本次静态核对仍成立的缺口。影响限定于可触发的软件分支；实际设备是否发生记录变化及其信号影响，须结合命令日志、设备记录和原始文件判断。

| 项 | 证据与影响 | 待实现／待验方案 |
| --- | --- | --- |
| A．采集中意外停止的收尾出口 | [Coordinator:279、733、166](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)：无查询轮且账本为COLLECTING时，即使STATUS同ID、error=0、计数未回退，`collecting=false`仍调用rejectAssociation并持久失效。后续requestStop要求有效归属，普通停止／参考收尾出口因此不可达 | 为可核验的意外停止保留记录归属、完整STATUS/LIST复核和异常收尾出口；区分用户STOP与设备自行停止的边界证据。回归同ID正常stopped、错误码、计数回退、重连和重启 |
| B．TIME锚核验覆盖不一致 | [Controller.beginCapture:482](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionController.kt)仅为旧Unix0基线构造强锚证据；[Store.isDistinctStartRecord:749](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingSessionStore.kt)在普通基线遇新ID可直接接受。[Coordinator:589、696](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)允许Unix0、uptime非零的新记录进入COLLECTING，跨连接恢复又要求Unix非零，造成已开始任务的恢复缺口。TIME侧文件已普遍保存，缺口位于统一使用这些证据的确认规则 | 统一新开始的TIME与记录锚核验；时间矛盾或Unix0时保留受控停止与原始保全出口。覆盖空基线、普通旧记录、旧Unix0、新ID／复用ID、时间矛盾及跨连接恢复；同步核对上传证据 |
| C．正式下载期间缺少记录复核 | [Controller.onHealth:297](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionController.kt)的正式下载分支忽略STATUS等其他消息；最终READ_END通过冻结长度及文件校验后直接completeLocalData，缺少末尾新STATUS/LIST。文件校验只能证明与下载前冻结记录一致，设备侧后续变化尚未纳入完成判定 | 下载中跟踪状态变化；完成前重新取得完整STATUS/LIST并比对身份、计数及停止状态，成功后再标记本地完整。回归增长、替换、计数回退、额外记录及最后一窗断连 |
| D．5000ms等待起点不同 | [Coordinator:177、181、639](../../android/app/src/main/java/com/nexthci/ringfitness/FreeLivingCaptureCoordinator.kt)从STOP请求入队后计时，首次匹配stopped且STATUS/LIST计数相等即确认并冻结；原版从收到stopped后再等5000ms。若设备较晚停止，当前可能在Flash尾部仍增长时冻结较早计数，之后的下载预检可能因变化进入恢复 | 以首次可核验stopped为收尾等待起点，结合完整记录观察确认尾部稳定；保持一次STOP。记录STOP请求、首次stopped、后续计数和下载时间，回归延迟停止及停止后继续增长 |
| E．手动上传入口仍经过BLE准备条件 | [StepPreparationActivity:343、352](../../android/app/src/main/java/com/nexthci/ringfitness/StepPreparationActivity.kt)先检查权限、蓝牙和适用系统的定位，再显示历史记录入口；[RealCollectionActivity:27](../../android/app/src/main/java/com/nexthci/ringfitness/RealCollectionActivity.kt)也要求蓝牙权限。后台上传已独立，用户从启动页主动上传本地完整记录仍受这些条件影响 | 提供从本地记录直达手动上传的入口；采集和下载继续在各自操作边界检查BLE。验证蓝牙关闭、权限撤回、仅网络可用时的查看、暂缓与手动上传 |
| F．历史卡缺少日期 | [StepCollectionActivity:267](../../android/app/src/main/java/com/nexthci/ringfitness/StepCollectionActivity.kt)卡片显示活动、步数及状态，未显示日期／时间；同日多段或不同日期相同步数的记录难以区分 | 按session已保存的时区展示采集日期与时间，未知边界保持对应状态；检查同日多段、跨日及手机时区变化后的辨认与重试对象 |

以上待验方案沿用已确认的分段、清零、唯一参考及三种收尾规则。下一步优先补齐异常停止、统一TIME确认和Flash收尾／下载复核，再完成设备故障恢复、逐级长时及独立操作验收。
