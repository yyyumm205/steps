# 自由活动数据导入

本工具接收 Android 独立采集版本冻结的 ZIP，校验并保留原始数据，生成每个 session 的参考记录及 IMU/PPG CSV。运行仅使用 Python 3.10+ 标准库；测试使用 pytest。实验语义遵循[项目总纲](../../CONSTITUTION.md)及[功能规格](../../specs/independent-step-collection/requirements.md)。

## 运行与复现

从仓库根目录执行；输入、输出路径由研究者指定。实验文件放在受控本地目录，例如已被 Git 忽略的 `research-data/`。

```powershell
python -m backend.ringo_data import --output research-data/imported research-data/incoming/session.zip
python -m backend.ringo_data summary --root research-data/imported --output research-data/session-index.csv
python -m backend.ringo_data sync --incoming research-data/incoming --output research-data/imported --once
python -m backend.ringo_data sync --incoming research-data/incoming --output research-data/imported
python -m pytest backend/ringo_data/tests -q
```

`import` 支持一次传入多份 ZIP，同批副本保持幂等。标准输出逐包报告 `imported`、`already_imported` 或 `conflict`；任何冲突或拒收使退出码为 2，其他包继续处理。

`sync` 监测清华云盘客户端已同步到本机的输入目录，只读取该目录直属的最终 `.zip` 文件。输入目录保持只读；研究产物写入独立输出目录，两者不得重合或互相嵌套。云盘客户端负责下载及同步，本命令负责导入；客户端账号和目录映射保存在本地配置。

默认每 5 秒检查一次，文件的大小、修改时间等属性在两次观察之间保持稳定至少 10 秒后，再复制快照并导入。临时名称、空文件及尚无完整 ZIP 目录的文件继续等待，复制时仍在变化的文件留待下一轮。`--interval-seconds` 和 `--stable-seconds` 调整检查及稳定间隔。`--once` 执行一次观察、稳定等待和批次处理，适合定时任务与复现检查；结果中的 `pending` 表示仍待完成的文件。进程重开后重新检查输入，已导入包保持幂等；单包拒收或冲突不阻塞其他包。每轮在输出目录原子更新 `session-index.csv`，索引失败时保留上一份完整结果。

导入与索引共用 `.import.lock` 操作系统文件锁，活跃并发明确拒绝；进程正常退出或被终止后，系统自动释放锁。锁文件会持续存在，以维持同一个锁对象，请勿在新版运行期间删除。升级前先停止旧版导入器；若遇到只写有 PID 的旧锁文件，确认旧进程已结束后再人工移除该旧锁，随后启动新版。程序不会凭锁文件年龄解锁。`.staging` 和 `.sync-staging` 中因进程终止留下的未发布文件保留为排查依据，原 ZIP 可重新导入。

默认限制为 ZIP 512 MiB、总解压 1 GiB、单文件 512 MiB、JSON 1 MiB、256 个条目、压缩比 1000、派生 CSV 总量 2 GiB。CLI 可调整 ZIP、解压及派生总量；Python `Limits` 可逐项配置。这些是资源保护配额，设备支持时长仍依赖实测。

## 输入与输出

新入口接收 `version=2`、`step_schema_version=1`、`rfbin_version=2`、`daily_activity_v2/free_living`、`simulated=false`。根目录包含 `manifest.json` 和 `files` 列出的平铺文件；`raw` 为 `.rfbin`，`evidence` 为对应 `.raw-evidence.json`。manifest 保留 Android 账本的身份、位置、请求/确认、真实边界及设备证据，文件清单保留字节数和 SHA-256。

校验包括严格整数类型、有效零步/缺失/不可靠参考、明确停止、记录指纹与开始基线、原始头部、记录数、载荷 CRC32、整文件 SHA-256、sidecar 对应关系，以及 ZIP 路径、重复条目和资源配额。参考原因保存在原 manifest 中；每个 session 的 `reference.csv` 只有一行，整段总数关联所有原始文件。软件可导入同一 session 的多个片段文件并标记重叠待核对；设备多文件能力仍待实测。

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

校验与派生在同卷暂存目录执行，文件同步后一次重命名发布。相同 ID、相同 ZIP 返回既有结果，并复核原包及派生产物；相同 ID、不同 ZIP 保留双方并报告冲突。拒收包保存在独立目录，原输入保持不变。Windows 使用文件同步与同卷目录重命名；整机断电后的目录持久性仍需系统级验证。

## 时间、活动标签与质量边界

- `timestamp_unix_ms`、`timestamp_iso` 当前留空：尚无验证通过的样本绝对时间标定。真实起止未知时 manifest 使用 null，rfbin 头使用 0；解码保持未知。
- `packet_uptime_ms` 保留包内端点；`ring_uptime_ms` 按包内样本位置及标称周期展开。IMU 为 20 ms，PPG 为 40 ms。`relative_sample_offset_ms` 相对当前文件该通道首样本，`relative_to_device_anchor_ms` 相对原始设备锚点；回退和环绕保持原差值。
- `device_anchor_estimated_unix_ms/iso` 单列设备锚推算值，仅用于诊断，不能作为真实采集边界。质量报告保留缺口、重叠及 uptime 回退统计，不插值、补点、重采样或拼成连续时间。
- 加速度换算沿用原解码器的 `raw / 2048 × 9.80665`。场景为 `free_living`，逐样本 `activity_truth` 为空，标签状态/来源固定为 `unlabelled/none`。
- `quality.json` 当前将分析资格保持 `pending_review`，分别报告未知边界、未校准样本时钟、参考异常、信号间隔和多文件风险。CRC/SHA 通过证明传输与保存一致性；信号覆盖、计步器时间对应和设备时钟仍需另行验证。
- `summary` 输出逐 session 索引并标记跨 session 重复原始文件和已知时间区间重叠。当前不生成每日总数；跨午夜、未知边界或未评估覆盖均保留为整段记录。日汇总的有效覆盖与排除规则将在时间质量验收后接入。

原始 `daily_activity_v1`、CSV v1 及睡眠相关包继续使用原交接后端，其含义保持原契约。它们不进入这个独立活动入口。云盘下载负责将同一测试文件夹中的冻结 ZIP 交给本 CLI；凭据及真实位置保存在本地配置。
