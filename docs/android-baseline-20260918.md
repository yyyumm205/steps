# Android 源码基线与构建复现

日期：2026-09-18。输入文档版本为 `29486e6`，沿用分支 `codex/free-living-session-spec`。本记录先于 T1 独立入口实现。

## 输入与版本管理

- 当前已有完整 Android 0.5.3 工程，applicationId 为 `com.nexthci.ringfitness`。
- 纳入版本管理前，将 `original/RingFitness-0.5.3-Android-source/` 的36个文件与 `android/` 按相对路径逐个比较 SHA-256：0缺失、0不同。其他窗口没有已跟踪的未提交修改。
- 仅清理工作副本 README、配置示例及 Gradle 中的原上传目标。实际配置继续来自忽略的 local.properties；本机已配置的目标不变。原始资料留在忽略的 original/。
- 纳入可复现所需的源码、测试、构建脚本、wrapper和已随交接提供的Polar依赖；源码文本脱敏检查未发现其他实际凭据。二进制依赖未进行完整字节码安全审计。
- 根忽略规则补充原始交接、实验数据、本地私密文件及发布产物；Android的local.properties和build目录仍被忽略。

## 实际执行

| 检查 | 结果与范围 |
| --- | --- |
| JDK | Microsoft OpenJDK 21.0.12.1，Gradle 8.13；源码/字节码目标Java 11 |
| 初始单测 | 两次执行 testDebugUnitTest 在worker启动阶段失败：GradleWorkerMain类加载失败；未执行到业务断言 |
| 原因定位 | worker JAR包含目标类；直接classpath可加载，Java @参数文件加载失败。UTF-8参数文件中的中文缓存路径被当前Windows Java启动器以GBK解析 |
| 兼容复跑 | 仅当前命令覆盖daemon编码后，testDebugUnitTest退出0，13秒；25测试、0失败、0错误、0跳过，XML UTC时间为2026-09-18T12:06:42附近 |
| Debug构建 | assembleDebug --no-daemon --console=plain 退出0，18秒，36项任务均UP-TO-DATE；验证现有产物仍可构建 |
| 脱敏后复核 | testDebugUnitTest assembleDebug 带同一编码参数退出0，7秒，42项UP-TO-DATE；未把缓存命中计为再次执行25项测试 |
| 设备条件 | adb devices -l无连接设备；SDK没有emulator/system-images，未执行模拟器或真机测试 |

25项测试构成：CapturePurposeTest 5、HealthTimeAnchorTest 5、ParticipantProfileStoreTest 3、RingProtocolTest 12。报告位于 Android build/reports/tests/testDebugUnitTest/ 和 build/test-results/testDebugUnitTest/，属于原版能力证据。

本机单测命令（JAVA_HOME指向已安装JDK 21）：

```powershell
.\gradlew.bat testDebugUnitTest '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK' --console=plain
.\gradlew.bat assembleDebug --console=plain
```

该参数仅用于这台Windows电脑的worker路径兼容，项目gradle.properties继续保留UTF-8；不删除共享缓存或把工程编码改成GBK。最初排查曾停止两个Gradle daemon，之后诊断与复跑不再使用该操作。

原版APK SHA-256：`B0D6E5D257662C957019DBAEA15883F84AFC310BC046FB848A08E8B630DFA679`。后续T1产物以自己的版本/哈希记录为准，不能沿用此原版结论。

## 本轮边界

蓝牙协议、原版采集/下载与恢复代码已存在；自由活动入口、本地独立编号、位置持久化及参考步数字段尚待后续切片实现。Python、设备文件测试、真实BLE/时间对应/长时采集仍待验证。基线复现没有证明完整实验系统已可交付。
