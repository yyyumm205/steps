# RingFitness Steps 0.9.1

日期：2026-09-27。

本版在 0.9.0 的九种运动和可选 Polar H10 基础上，加固连接重试、心率文件恢复与收尾。首页、运动选择和三种收尾操作保持不变。

## 修复内容

- 心率 SDK 启用、搜索或连接失败时，在心率区域保留提示和重试入口；关闭心率带后可继续戒指采集。
- 搜索异常及时结束；连接与 HR 服务准备共用 20 秒期限。同设备重试先处理旧断开通知，等待 1 秒仍未确认时安全重建 SDK，隔离旧实例回调。正常连接不增加这段恢复等待。
- 手机保存心率失败时，后续连接或样本通知不会覆盖存储提示；戒指采集及结束操作保持可用。
- 重开时按照 CSV 完整行恢复真实样本数及首末时间；发现已记账样本因文件缺失或完整尾行丢失而减少时，追加存储缺口。再次写入失败、重复恢复和手机时间回拨的边界纳入回归。
- 结束、放弃和关闭时，蓝牙流与文件分别清理；旧段迟到样本不能进入新段。下载或恢复戒指暂存前先封存心率，失败时保留参考值和原暂存选择。
- 心率单行数量及长度在落盘前校验，与研究端读取配额一致。

走路、跑步继续填写参考步数；另外七种运动不要求计步。H10 每段默认关闭。journal v14、manifest v8、Python 0.3.0 及旧冻结 ZIP 保持不变。

## 安装包

| 项目 | 值 |
| --- | --- |
| 应用名称 | 步数采集 |
| applicationId | `com.nexthci.ringfitness.steps` |
| versionName / versionCode | `0.9.1` / `62` |
| 最低系统 | Android 11（API 30） |
| APK | `android/dist/0.9.1/RingFitness-Steps-0.9.1.apk` |
| 大小 | 13,538,122 字节 |
| SHA-256 | `81050027eaea43ccf65dccd2a5cea686aaff9f6dabab81d30d58cf9c60ed2788` |

复用原发布证书，签名 v3 校验通过。API31 模拟器从 0.9.0 原位更新至 0.9.1，应用私有 `filesDir` 的全部相对路径和 SHA-256 保持一致，首次安装时间保持。Release 不可调试，没有 Demo Activity 或旧采集／上传服务。

正式交付使用上表 APK。模拟器演示使用同版本的独立 Debug 副本，演示与故障注入数据留在本地。APK、签名材料、配置、日志和测试数据不进入 Git。

## 验证与适用范围

- Android JVM：767 项通过，0 失败、0 错误、0 跳过。
- Python：728 项通过，0 失败、0 跳过。
- 模拟器完整回归：92 项执行通过，19 项按硬件或专用测试前置条件跳过，0 失败。首次打开／页面重建、多运动与可选心率用例均已执行通过。
- Polar SDK 运行时：2 项通过，覆盖初始化、幂等关闭及单例重建；范围为模拟器生命周期检查。
- 1.5 倍大字体：7 项多运动页面测试通过，检查后恢复默认字体。
- Debug、AndroidTest、Release 和 `lintVitalRelease` 构建通过。
- Release 升级前快照、升级后文件与组件校验各 1 项通过。

完整页面、SDK 生命周期与恢复证据统一见 [validation E47](../specs/independent-step-collection/validation.md#e47可选心率失败恢复与091交付2026-09-27)。本轮测试不访问实验云盘，未产生真实采集记录；跨端 18 包首次导入及去重证据沿用格式未变的 E46。

当前没有可用的真实 H10。本版尚未取得真实手机同时连接戒指与 H10、锁屏后台、心率断连返回及双设备长时采集的实物证据；10 小时仍是待实测的目标。既有华为戒指长时证据保持其原版本与设备范围。H10 当前支持手机实时接收 HR/RR，胸带离线回补不在本版范围。

用户操作见 [participant-guide.md](participant-guide.md)，实现契约见 [multisport-heart-rate.md](../specs/independent-step-collection/multisport-heart-rate.md)，原版差异见 [change-review.md](../specs/independent-step-collection/change-review.md)。
