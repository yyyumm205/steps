# RingFitness Steps 0.9.3

日期：2026-09-28。

按用户要求，记录列表中非计步运动省略“无需计步”一行及其专属间距，只显示日期、运动名称和保存／上传状态。走路、跑步继续显示实际数字，包括0步及历史缺失参考的原有提示。采集、保存、上传、心率和研究数据契约沿用0.9.2。

安装包：`android/dist/0.9.3/RingFitness-Steps-0.9.3.apk`；versionCode为64，大小13,542,218字节，SHA-256：

```text
526834814d94f128641576ae50a9e46b694ed2f9adb164a5f36d337cf579ca27
```

Debug、AndroidTest、Release及lintVitalRelease构建通过，沿用原发布证书，v3签名校验通过。模拟器实际记录页已检查，三项既有回归通过：七种非计步运动收尾、0步保存重开、历史异常参考显示。证据与手机更新状态见[E49](../specs/independent-step-collection/validation.md#e49记录列表文案精简0932026-09-28)。设备与长时验证范围继续沿用[0.9.2说明](release-0.9.2.md)。
