# RingFitness Steps 0.8.18

## 发布信息

- 应用名称：步数采集
- applicationId：`com.nexthci.ringfitness.steps`
- versionName：`0.8.18`
- versionCode：`60`
- Git 标签：`v0.8.18`
- APK 文件名：`RingFitness-Steps-0.8.18.apk`
- APK SHA-256：`a60a28a836573172aa9751668a2ea551aa3fec61df214c139719864b21f231a3`

正式 APK 不提交到 Git 源码历史；交付时作为 GitHub Release 的附件，并同时附上 `SHA256SUMS.txt`。本地构建产物位于 `android/dist/0.8.18/`。

## 本版内容

下载阶段统一显示“正在从戒指保存到手机”，进度明确标记为蓝牙传输。只有原始文件在手机上完成保存和校验后，才进入云盘上传或暂存分支。走路与跑步仍按独立 session 生成数据包，原始文件、参考步数、账本、重复导入去重和上传契约保持不变。

## 验证证据

- Android JVM：677 项通过。
- Python 研究端：550 项通过。
- API 31 模拟器：102 项，83 项通过，19 项因真实硬件或恢复测试前置条件跳过，0 项失败。
- 华为 ELS-AN10 约 3 小时真实记录：222 步；原始文件 4,028,677 字节；蓝牙断线后约 4 秒自动恢复；云盘读回哈希与上传包一致；重复导入保持幂等。
- 详细日志、截图和研究端导入报告保存在本机 `.local/validation/2026-09-24-0.8.18-release/` 与 `.local/validation/2026-09-24-huawei-3h-download-readonly/`，这些目录不进入 Git。

## 安装与升级

使用同一签名的 APK 原位升级：

```powershell
adb install -r RingFitness-Steps-0.8.18.apk
```

原位升级会保留应用数据；卸载或清除应用数据会删除手机上尚未外部保全的记录，不作为升级步骤。安装后核对应用显示 `0.8.18`，再按被试说明完成连接、走路/跑步采集和收尾。

## 当前验证边界

已验证华为 ELS-AN10 与 Ringo5422 的约 3 小时链路。小米机型、10 小时连续采集、整机重启后的后台恢复和不同固件组合仍需按实际设备补充证据；这些边界不改变 APK 与源码版本标识。
