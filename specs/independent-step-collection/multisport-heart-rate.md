# 多运动与可选心率带：实施契约

日期：2026-09-27。依据：项目负责人确认按原版 Polar H10 方案开展开发。

## 范围与用户流程

- 每段选择一种运动：走路、跑步、羽毛球、足球、篮球、网球、乒乓球、排球、力量训练。
- 走路、跑步沿用清零及填写本段参考步数；其余七种运动无需计步器，停止后直接选择保存方式。
- 所有运动均可选 Polar H10，每段默认关闭。沿用原版搜索、HR 服务就绪、实时 HR/RR 与断线重连；未选心率带可独立完成戒指采集。
- 心率带选中时，开始前须就绪；使用同一 session 固定设备身份。采集中断连不停止戒指，保留实际缺口，恢复后继续本段。
- 保留现有首页、采集、收尾、记录入口和三种收尾选择。暂存到戒指时，手机已经收到的心率文件立即保存，等待用户手动恢复戒指下载和上传。
- 一个 session 冻结一个 ZIP，文件名保留运动类型；放弃只清理本段手机文件及任务。旧 session 与已冻结 ZIP 不改写。
- 本段用户名、运动、戒指与可选 H10 身份在开始时固定。戒指数据完整保存到手机前保持设置锁定；更换用户名后仅新段使用新身份，旧段及其上传任务继续沿用采集时的身份。首页和记录页按当前用户名显示历史。

## v8 上传契约

沿用 v7 字段并增加必填 `heart_rate` 对象；新运动使用英文标识 `badminton/football/basketball/tennis/table_tennis/volleyball/strength_training`。走路、跑步及历史自由活动保持原步数含义。

七种非计步运动固定 `ground_truth_source=none`、`ground_truth_status=not_applicable`、`ground_truth_steps=null`、`ground_truth_recorded_at_ms=null`、`ground_truth_reason=null`。内部仍保存收尾确认时间。不得把“不适用”转成 0、缺失或异常。

`heart_rate` 的完整键集合：

- `enabled`：布尔值。
- `device_id`、`device_name`：开启时为非空字符串；关闭时为 null。
- `status`：`not_requested/recorded/partial/no_samples`。关闭时为 not_requested；开启且无样本为 no_samples；有样本且有缺口为 partial；其余为 recorded。
- `timestamp_source`：固定 `phone_receipt`，说明 CSV 时间为手机接收时间。
- `started_at_ms`、`ended_at_ms`、`first_sample_at_ms`、`last_sample_at_ms`：UTC Unix 毫秒；关闭时全部为 null；开启时开始/结束必须存在；无样本时首末样本为 null。
- `sample_count`：非负整数，关闭时为 0。
- `gaps`：数组，每项精确为 `{started_at_ms, ended_at_ms, reason}`。上传前所有缺口关闭；reason 取 `disconnected/process_restart/stream_error/storage_error/no_data`。关闭时数组为空。

心率 CSV 名称为 `<session_id>_polar_hr_rr.csv`，文件角色 `polar_hr_rr`；文件清单继续使用既有六个字段，其 `device_session_id=null`，其余大小、SHA-256 和 simulated 按原规则校验。开启时通常含一个 CSV（允许只有表头）；确实未建立文件且无样本时可无 CSV，但必须有 storage_error 缺口。关闭时禁止心率 CSV。原始 CSV 保留原版列：

`timestamp_iso,timestamp_unix_ms,sample_index,hr_bpm,corrected_hr_bpm,ppg_quality,rr_available,contact_supported,contact_status,rr_ms,rr_1_1024s`

一批通知可含多个样本，同批接收时间相同；不伪造逐心搏时间。研究端保留心率 CSV、输出质量/覆盖信息，戒指手机时间估计与 HR 接收时间分别注明来源。

## 实现边界和验证

- 复用现有 PolarH10Client 与原版 CSV 字段，接入当前前台服务及私有 session 存储；旧版整个服务、上传器和注册流程继续停用。
- 先持久化可选设备身份与开始意图，再发送戒指 START；结束/放弃/开始失败/服务重建均须关闭或恢复对应心率文件。
- 首次打包前冻结心率结果；重试复用完全相同的 ZIP。后端保持 session 幂等和冲突隔离。
- 校验九种运动、步数适用性、零步、可选开关、无样本、断连/进程重建缺口、暂存后继续、放弃、旧包兼容及混合设备身份。
- 本轮不包含 ECG、胸带离线回补、其他品牌设备或逐心搏精密同步；真实 H10 和手机后台验证须独立记录，代码测试不替代硬件证据。

状态：0.9.0软件实现、自动回归、18包跨端校验及API31模拟器的页面、SDK运行时和Release升级检查已完成，证据见[validation E46](validation.md#e46多运动与可选-polar-h102026-09-27)。真实H10与戒指双设备、锁屏后台、断连返回及长时能力继续按设备矩阵验收。
