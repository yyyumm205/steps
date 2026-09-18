# 自由活动需求修订：差异与代码影响

日期：2026-09-18。状态：供审阅。修订前基准为 `814d7c6`；文档分支为 `codex/free-living-session-spec`。本轮只改文档，原始交接资料、历史验证记录、Android/Python 业务代码与本地上传配置保持原状。

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

总纲由1.1.0修订为2.0.0草稿：采集单位内的活动语义、参考值有效性和分析契约发生变化，按既有修订规则提升主版本。项目总纲继续唯一维护使命、技术栈和总体路线；三份规格引用它。

## 2. 已实现代码的影响（只读核对）

以下行号对应本轮原版0.5.3工作副本/归档源码。它们是静态事实，运行可靠性仍需验收。

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

Android app/src 与后端源码目前未发现 ground_truth 实现。原 manifest 原样留存或主观评价 recorded_at_ms 均不等于参考数字契约已完成。

## 3. 可推进事项、审阅选择和缺少的证据

**可按本轮需求推进的方向：** 自由活动统一入口、每次清零、自主开始结束、编号/位置记忆、整段一个非负总数、异常/缺失区分、旧依赖退出、跨文件归属、未标注语义、已采集时段日汇总、恢复与长时验证设计。文档审阅后按T1起逐切片实施。

**重要交互仍供审阅：** requirements 的Q01（编号更换）、Q02（确认后纠错）、Q03（无法读数时保全并上传异常记录）。建议分别采用“仅新记录改编号”“保留原值并附研究者修订”“保存缺失及原因继续保全信号”，理由与备选已列明；本轮未视为批准。

**待补设备或运行证据：** 手机/固件/计步器型号及佩戴说明、真实开始/停止和计步器延迟对应、电量/容量/满后行为、长时信号连续性、长记录下载恢复、手机采集上传及云盘读回。连接断开能否继续采到信号按Flash实际内容判断。

后端读取所需资料库/目录已有[核对记录](../../docs/upload-target-verification-20260918.md)，读取凭据和本机路径待配置。正式人数/周期、可用时长与丢样/时间容忍阈值，在试运行与质量检查后决定。

## 4. 文档检查结果

| 检查 | 结果 |
| --- | --- |
| 版本与组织 | 总纲2.0.0修订草稿与三规格引用一致；历史资料保持原状 |
| S/T/B对应 | 12条需求、5个实施/验证切片、17条顶层验收；双向映射无缺项，T4明确为联合验证 |
| 文档/源码链接 | 38处相对链接均可定位，0失效 |
| 源码核对与独立审阅 | Android、Python分别只读核对；独立审阅发现的停止边界保全、冻结时点和映射遗漏已修正，复核无阻碍交付的新冲突 |
| 敏感配置 | 5份本轮文档均不含实际上传链接/口令；local.properties仍被Git忽略且未跟踪 |
| 格式与范围 | git diff --check通过；本地提交范围限定总纲、三规格和本说明，原有代码/归档/本地配置保持原状 |
| 业务运行 | 本轮未执行构建、自动功能测试、真机采集或上传；历史构建和电脑接口上传仅证明原记录中的能力 |

本次提交保存待审阅稿；Q01–Q03继续待确认，设备时长与数据质量继续待实测。下一步先审阅上述选择，再实施独立准备流程T1及对应基线补证。
