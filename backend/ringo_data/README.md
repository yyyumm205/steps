# 活动采集数据导入

本工具接收 Android 独立采集版本冻结的 ZIP，校验并保留原始数据，生成每个 session 的参考记录及 IMU/PPG CSV。运行仅使用 Python 3.10+ 标准库；测试使用 pytest。实验语义遵循[项目总纲](../../CONSTITUTION.md)及[功能规格](../../specs/independent-step-collection/requirements.md)。

当前导入器版本为 **0.2.5**，可用 `python -m backend.ringo_data --version` 查询。跨session的原始文件归属以回执绑定、实际SHA-256匹配的冻结`source.zip`为准；先创建有界临时快照，再从同一快照读取清单，结束后清理。落地清单或派生缓存损坏时，仍能识别共享raw并隔离后到包，避免重复进入主索引。冻结源或回执无法验证时报告可重试的研究库完整性错误，保留原件，新包不进入坏包拒收缓存；修复后同一扫描进程可重试。既有研究目录拒绝符号链接和非普通文件，manifest、质量报告及导入回执统一有界读取；异常不会覆盖上一份完整索引。云端回执长度／SHA到导入快照的绑定及既有配额继续生效。每次新导入需核验既有冻结包，耗时随库总量增长，大规模长段须另做性能验证。

本轮证据与剩余阻断见[恢复与故障隔离验证E31](../../specs/independent-step-collection/validation.md#e31发布核对与在途恢复2026-09-21)；既有契约审计见[后端复查记录](../../specs/independent-step-collection/validation.md#后端契约与导入防护复查2026-09-21)。

Android 0.8.3的新登记直接使用规范化用户名作为`participant_id`，兼容字段`participant_name`与其相同，沿用现有契约。`reference.csv`、信号CSV与session索引按该字段区分被试；研究者为不同被试分配不同用户名（如`p001`、`p002`），同一人换手机继续填写原用户名。离线登记无法检查全局重名，同名会视作同一人。已有测试包保持原文，不做身份迁移；重复导入继续按session及文件哈希处理。

## 运行与复现

以下命令均从仓库根目录执行，使 Python 能找到 `backend.ringo_data` 模块。先安装 Python 3.10+，确认 `python --version` 返回所选解释器的版本，再建立独立测试环境：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend/requirements-test.txt
.\.venv\Scripts\python.exe -m pytest backend/ringo_data/tests -q
```

[requirements-test.txt](../requirements-test.txt)固定测试框架为 pytest 9.1.1；业务运行继续只使用标准库。命令直接调用虚拟环境解释器，无需激活环境。macOS/Linux 对应解释器为 `.venv/bin/python`。历史验证使用的本机 `PYTHONPATH` 和 `.local/python-test-deps` 是本地便捷配置，团队复现使用上述虚拟环境，无需复制个人路径或依赖目录。

输入、输出路径由研究者指定。实验文件放在受控本地目录，例如已被 Git 忽略的 `research-data/`。下列 `python` 表示已选定的 Python 3.10+ 解释器；使用上述 Windows 虚拟环境时，替换为 `.\.venv\Scripts\python.exe`：

```powershell
python -m backend.ringo_data import --output research-data/imported research-data/incoming/session.zip
python -m backend.ringo_data summary --root research-data/imported --output research-data/session-index.csv
python -m backend.ringo_data sync --incoming research-data/incoming --output research-data/imported --once
python -m backend.ringo_data sync --incoming research-data/incoming --output research-data/imported
python -m backend.ringo_data cloud-sync --config research-data/cloud-sync.local.json --once
```

`import` 支持一次传入多份 ZIP，同批副本保持幂等。标准输出逐包报告 `imported`、`already_imported` 或 `conflict`；任何冲突或拒收使退出码为 2，其他包继续处理。

`sync` 监测清华云盘客户端已同步到本机的输入目录，只读取该目录直属的最终 `.zip` 文件。输入目录保持只读；研究产物写入独立输出目录，两者不得重合或互相嵌套。云盘客户端负责下载及同步，本命令负责导入；客户端账号和目录映射保存在本地配置。

默认每 5 秒检查一次，文件的大小、修改时间等属性在两次观察之间保持稳定至少 10 秒后，再复制快照并导入。临时名称、空文件及尚无完整 ZIP 目录的文件继续等待，复制时仍在变化的文件留待下一轮。`--interval-seconds` 和 `--stable-seconds` 调整检查及稳定间隔。`--once` 执行一次观察、稳定等待和批次处理，适合定时任务与复现检查；结果中的 `pending` 表示仍待完成的文件。进程重开后重新检查输入，已导入包保持幂等；单包拒收或冲突不阻塞其他包。每轮在输出目录原子更新 `session-index.csv`，索引失败时保留上一份完整结果。

导入与索引共用 `.import.lock` 操作系统文件锁，活跃并发明确拒绝；进程正常退出或被终止后，系统自动释放锁。锁文件会持续存在，以维持同一个锁对象，请勿在新版运行期间删除。升级前先停止旧版导入器；若遇到只写有 PID 的旧锁文件，确认旧进程已结束后再人工移除该旧锁，随后启动新版。程序不会凭锁文件年龄解锁。`.staging` 和 `.sync-staging` 中因进程终止留下的未发布文件保留为排查依据，原 ZIP 可重新导入。

`cloud-sync` 可使用已登录 Seafile 桌面客户端的本地凭据，读取明确配置的单一资料库目录。复制 [cloud-sync.example.json](cloud-sync.example.json) 到被 Git 忽略的本地配置目录，填写资料库 ID、目录和客户端 `accounts.db` 路径；相对路径以配置所在目录为基准。配置只保存位置与配额，凭据在运行时从账号库只读取得。匹配清华 HTTPS 服务器的登录账号必须恰好一个；多账号时先确认选用账号。上传链接与读取目录需要由研究者核对对应关系。

该命令仅发送 GET，列出指定目录直属文件并下载完整 ZIP；禁止 HTTP、跨域下载与重定向，账号凭据只随同源 API 请求发送，短期下载地址与凭据不进入日志或导入材料。首次使用要求专用空 `local_inbox`，在读取云端前原子保存作用域标记；已有文件却缺少标记时保留原件并暂停，避免将其他实验的文件认领到当前配置。下载使用独立临时文件，按列表长度检查完整性，计算 SHA-256 后原子发布；以内容哈希命名，同名异内容分别保留。既有文件、远端内容和原输入均保持原样。下载回执只保留作用域摘要、文件身份摘要、长度与内容哈希；重跑复核本地内容后跳过已下载文件。自动导入仅接收已登记且长度、哈希复核一致的包，混入的其他文件保持原样。进程在发布 ZIP 后、保存回执前终止时，该包等待同一云端文件重新读回登记，再进入导入。后续导入继续校验 ZIP、rfbin、清单和研究字段。

`cloud-sync --once` 完成一轮下载、稳定等待和导入后退出；省略 `--once` 默认每 60 秒持续检查。一次失败保留其他文件的处理结果，网络恢复后继续重试。`max_files`、`max_response_bytes` 与 `max_archive_bytes` 分别限制单目录列表数量、元数据响应和单包大小；网络请求有等待及按文件大小计算的时限。轮询频次、目录映射及启动方式保存在本机配置；实际云端读取仍须用原包哈希和导入结果验收。

默认限制为 ZIP 512 MiB、总解压 1 GiB、单文件 512 MiB、JSON 1 MiB、256 个条目、压缩比 1000、派生 CSV 总量 2 GiB。CLI 可调整 ZIP、解压及派生总量；Python `Limits` 可逐项配置。这些是资源保护配额，设备支持时长仍依赖实测。

## 输入与输出

新采集按走路、跑步分别建立 session，每次清零计步器并保存该次总数。新云端包分别命名为`ringfitness-session-walking-<session_id>.zip`和`ringfitness-session-running-<session_id>.zip`；名称便于人工辨认，导入器仍以包内经校验的`manifest.activity_code`为权威。历史无活动前缀的`ringfitness-session-<session_id>.zip`继续兼容且不改名。当前新冻结包采用 `version=7`：v6在v4/v5的活动和恢复语义上补齐原版的佩戴拆分字段、App版本和包创建时间，v7继续增加停止来源和停止观察时间。走跑包使用 `activity_schema=daily_activity_v3`，`activity_code` 为 `walking` 或 `running`，`activity_selection_source=participant` 表示被试在开始前选择的活动任务。每个 session 保留独立 UUID、原始文件和参考总数。活动选择在整个 session 内固定。

历史 `version=2/3` 包继续采用 `daily_activity_v2/free_living`，v4保留走跑任务声明，v5保留未知日期兼容证据；这些已冻结包保持原有字段与含义。所有版本均要求 `step_schema_version=1`、`rfbin_version=2`、`simulated=false`。根目录包含 `manifest.json` 和 `files` 列出的平铺文件；`raw` 为 `.rfbin`，`evidence` 为对应 `.raw-evidence.json`。manifest 保留 Android 账本的身份、位置、请求/确认、真实边界及设备证据，文件清单保留字节数和 SHA-256。

历史自由活动的普通记录使用版本 2，其开始基线必须为空闲且 `error_code=0`。历史版本 3 专门保存充电错误兼容路径：开始基线保留真实的空闲状态和 `error_code=-16`，并要求 `start_baseline.charging_recovery_evidence` 同时证明原因是 `charging`、电量接口报告未充电、两条回复来自同一次连接且在检查时均不超过 5 秒。证据保存原因码、电量充电状态、两条回复的接收时间与连接代次、检查时间；STATUS 接收时间必须等于基线观察时间。缺失、过期或相互矛盾的证据拒收。版本 2 不接受此兼容字段；所有版本的成功开始、停止与下载记录证据仍要求 `error_code=0`。兼容证据随原清单保留，冻结旧包保持原字节内容。

版本 4 同时支持普通开始与充电错误兼容路径。普通开始要求空闲且 `error_code=0`，省略 `charging_recovery_evidence` 字段；兼容路径要求空闲且 `error_code=-16`，保留与版本 3 同样完整、有效的证据对象。兼容路径缺少证据或证据显式为 null 时拒收；普通开始携带该字段、或其他状态与证据冲突时也拒收。版本 2/3 保持原有校验，版本 4/5 支持新增活动选择字段。

版本5用于开始基线中存在一条设备日期未知的旧记录。`unknown_time_start_evidence`保存完整重读备份的身份及哈希、连接owner/代次、保全时间和手机校时请求/回复。旧记录仍为`unix_ms=0`；新记录必须符合本次TIME锚及时间窗口，数值ID复用时uptime也须改变。v5支持走路/跑步或历史自由活动，以及已有充电兼容证据；缺失、冲突或过期的证据拒收。v6沿用相同证据组合，并要求`ring_placement_schema/hand/finger`与组合位置一致，`app_version`非空，`created_at`为UTC时间。v7在v6基础上要求`stop_origin`与停止请求／确认组合一致，并保存`stop_observed_at_ms`；用户请求停止、设备自行停止和旧来源不明分别表达。这里的时钟证据用于记录归属检查，样本精确时间仍依原有质量规则。冻结v2–v6包原文保持。

手机的“稍后上传”和放弃审计仅控制本地工作流。放弃段不产生研究上传包；暂缓段手动上传时沿用同一session及冻结内容。后台接收后仍按session与ZIP哈希去重。

校验包括严格整数类型、有效零步/缺失/不可靠参考、明确停止、记录指纹与开始基线、原始头部、记录数、载荷 CRC32、整文件 SHA-256、sidecar 对应关系，以及 ZIP 路径、重复条目和资源配额。参考原因保存在原 manifest 中；每个 session 的 `reference.csv` 只有一行，整段总数关联所有原始文件。软件可导入同一 session 的多个片段文件并标记重叠待核对；设备多文件能力仍待实测。

嵌套设备证据按对应版本精确校验字段；两类恢复证据同时存在时要求连接代次一致。时区采用 Android `ZoneId` 语法，固定偏移须在 ±18 小时内且与保存的秒数一致；地区时区保留采集时的偏移，不依赖研究电脑的时区数据库推翻历史记录。已有合法 v2–v6 包保持格式与原文；v7按停止来源新增字段严格校验，历史导入产物保持原样。异常包保留到拒收目录，供复核。

```text
输出目录/
  sessions/<session_id>/
    source.zip         原始冻结上传包
    manifest.json      包内清单原文
    raw/               原始 rfbin 和设备证据
    derived/           IMU 与 PPG CSV
    reference.csv      唯一 session 参考记录
    quality.json       时钟、信号及分析限制
    import.json        导入版本、包哈希及产物哈希
  conflicts/<session_id>/<zip_sha256>/
  rejected/<zip_sha256>/
```

校验与派生在同卷暂存目录执行，文件同步后一次重命名发布。相同 ID、相同 ZIP 返回既有结果，并复核原包及派生产物；相同 ID、不同 ZIP 保留双方并报告冲突。不同 session 若与 canonical session 共享原始文件 SHA-256，后到包完整保留在冲突目录，回执以 `duplicate_of` 指向 canonical session，且不进入 session 索引。拒收包保存在独立目录，原输入保持不变。Windows 使用文件同步与同卷目录重命名；整机断电后的目录持久性仍需系统级验证。

## 时间、活动标签与质量边界

导入器0.2.0为单文件session增加手机时间估算：所有通道共用`phone_capture_window_v1`平移，手机开始请求与停止确认约束样本时间轴，取可行区间中点。新增`phone_estimated_unix_ms/iso`及`phone_earliest_unix_ms/phone_latest_unix_ms/phone_time_source`；quality的`phone_time_alignment`记录区间与假设。手机时钟稳定、设备标称速率和原session关联是前提，该范围不等于实测校时精度。时序异常、回卷、过长信号或多文件时保留`unavailable`原因；原有时间、数据和参考不改写，分析资格仍待核对。已导入同包继续返回既有结果；需要新版派生列时，用相同`import`命令指定新的独立`--output`目录，原输入与旧研究产物保留。

- `timestamp_unix_ms`、`timestamp_iso` 当前留空：尚无验证通过的样本绝对时间标定。真实起止未知时 manifest 使用 null，rfbin 头使用 0；解码保持未知。
- `packet_uptime_ms` 保留包内端点；`ring_uptime_ms` 按包内样本位置及标称周期展开。IMU 为 20 ms，PPG 为 40 ms。`relative_sample_offset_ms` 相对当前文件该通道首样本，`relative_to_device_anchor_ms` 相对原始设备锚点；回退和环绕保持原差值。
- `device_anchor_estimated_unix_ms/iso` 单列设备锚推算值，仅用于诊断，不能作为真实采集边界。质量报告保留缺口、重叠及 uptime 回退统计，不插值、补点、重采样或拼成连续时间。
- 加速度换算沿用原解码器的 `raw / 2048 × 9.80665`。每行 `activity_code` 保留 session 的 `walking`、`running` 或历史 `free_living`。走路、跑步选择声明整次采集任务；逐样本 `activity_truth` 保持空值，标签状态/来源固定为 `unlabelled/none`。逐样本分类评价仍需独立的活动时间标注。
- `quality.json` 当前将分析资格保持 `pending_review`，分别报告未知边界、未校准样本时钟、参考异常、信号间隔和多文件风险。CRC/SHA 通过证明传输与保存一致性；信号覆盖、计步器时间对应和设备时钟仍需另行验证。
- `reference.csv` 与 `summary` 的逐 session 索引均包含 `activity_code`，可据此筛选走路和跑步记录。`summary` 从已验证的清单重建索引，可覆盖旧版缺少活动列的索引；已归档的 ZIP、清单及派生产物保持原样。跨活动同样检查重复原始文件和已知时间区间重叠。当前不生成每日总数；跨午夜、未知边界或未评估覆盖均保留为整段记录。日汇总的有效覆盖与排除规则将在时间质量验收后接入。

原始 `daily_activity_v1`、CSV v1 及睡眠相关包继续使用原交接后端，其含义保持原契约。它们不进入这个独立活动入口。云盘下载负责将同一测试文件夹中的冻结 ZIP 交给本 CLI；凭据及真实位置保存在本地配置。
