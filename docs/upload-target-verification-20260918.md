# 日常活动测试上传配置与验证

执行日期：2026-09-18。此次用户授权包括修改本地活动上传目标、重新构建、执行一次测试上传并核对目标。完整上传链接只存在于被忽略的本地配置及派生构建产物中，本记录不保存链接或上传口令。

## 1. 结果

| 项目 | 状态 | 实际证据及边界 |
| --- | --- | --- |
| 配置完成 | 通过 | `android/local.properties` 已设置 `ringfitness.activityUploadLink`；`git check-ignore -v` 命中 `android/.gitignore:3`；`git ls-files` 确认配置未被跟踪 |
| 构建通过 | 通过 | 用 Microsoft JDK 21.0.12.1 执行 `android/gradlew.bat assembleDebug --console=plain`，退出码 0，输出 `BUILD SUCCESSFUL in 32s` |
| 构建配置生效 | 通过 | 生成的 BuildConfig 与本地目标一致，APK 内 DEX 含同一目标；检查只输出匹配结果 |
| 实际上传验证：云盘接口 | 通过服务端接收验证 | 从配置读取上传入口，页面名称、资料库 ID 和路径核对后，以 Android 上传器相同接口和 multipart 字段上传一个纯文本探针；返回 HTTP 200、匹配文件名及文件大小 |
| 实际上传验证：App 真机 | 待执行 | `adb devices -l` 无设备；本次探针由电脑发送。未验证手机登录、采集、Flash 下载及 App 上传任务 |
| 上传后下载复核 | 待执行 | 尚无该资料库的账号读取凭据；本次依据上传页面目标信息及服务端回执核对接收位置，未从账号目录重新下载比对 |

测试过程只实际成功写入一个标明测试用途的 `.txt` 文件，包含测试说明，不含被试或传感器数据。它不属于训练 session，也不作为 Python 数据导入验收材料。

## 2. 产物与构建环境

- APK：[app-debug.apk](../android/app/build/outputs/apk/debug/app-debug.apk)。
- 现有源码仍为 RingFitness 0.5.3，versionCode 25，applicationId `com.nexthci.ringfitness`；独立计步版本的业务改造尚未执行。
- APK 大小：15,806,649 字节。
- SHA-256：`B0D6E5D257662C957019DBAEA15883F84AFC310BC046FB848A08E8B630DFA679`。
- JDK 路径：`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`；本次仅在构建进程设置 JAVA_HOME。
- Android SDK 使用本机 `sdk.dir`，Gradle wrapper 8.13。
- 最初用 Android Studio 自带 JDK 25.0.3 时构建失败；改用此前 Gradle 日志中记录的 JDK 21 后通过。未修改 Gradle 或业务代码。
- 构建有 SDK 元数据版本及已弃用 API 警告，未阻止构建。
- 本次重建替换了原输出路径中的 APK；历史规格 E02 的哈希描述的是此前产物，当前产物以本记录为准。

本次配置变更未重跑业务单元测试。历史 25 项单元测试通过记录仍属于原先执行结果。

## 3. 测试文件与回执

- 页面显示的目标：`RingFitness_Test_mm`。
- 资料库 ID：`a459d7dd-f1ea-43e4-bcf5-ecc4145a3eb3`。
- 资料库内目录：`/`。目标名称对应该资料库根目录，后端读取路径应采用 `/`。
- 文件：`ringfitness-upload-probe-20260918-190321-2d3fa0b9.txt`。
- 服务端文件 ID：`4d5537ab405babd62384b3cf018ceb2073f29cb4`。
- 本地内容大小与服务端返回大小：均为 184 字节。
- 本地内容 SHA-256：`E1EF183E079155E36A84A0BD0BF13F7B92C5A8D3DDC5DAAD033F0AE5C73C2C49`；尚无下载后的远端哈希对比。
- 回执时间：2026-09-18T11:03:21.5725808Z（北京时间 19:03:21）。

首次使用通用 multipart 生成器的请求返回 HTTP 400，未获得写入成功回执。随后按现有 `UploadWorker.kt` 手工构造相同的 multipart 边界与字段格式，上传成功；这一调整仅在被忽略的本地验证脚本中完成。未认定该次 HTTP 400 是 App 的业务缺陷。

本地脚本位于 `android/app/build/verification/upload-target-probe.ps1`，只读取本地配置，输出经过筛选的回执。脚本与整个 build 目录均被忽略。再次执行会上传新探针，普通文档检查不执行它。

## 4. Python 后端配置与待补信息

| 配置 | 已知值或待补内容 |
| --- | --- |
| `RINGFITNESS_SEAFILE_SERVER` | 清华云盘服务器，源码默认值可沿用 |
| `RINGFITNESS_ACTIVITY_SEAFILE_REPOSITORY_ID` | `a459d7dd-f1ea-43e4-bcf5-ecc4145a3eb3`，已从上传页面核对 |
| `RINGFITNESS_ACTIVITY_SEAFILE_REMOTE_DIRECTORY` | `/`，已从上传页面核对 |
| `RINGFITNESS_SEAFILE_TOKEN` 或 `RINGFITNESS_SEAFILE_TOKEN_FILE` | 待提供对该资料库具有列目录及下载权限的账号 API token，或保存该 token 的本地文件路径；上传口令不能代替读取凭据 |
| `RINGFITNESS_ACTIVITY_CLOUD_INBOX` | 待确定本机下载 ZIP 的收件目录 |
| `RINGFITNESS_ACTIVITY_RAW_DATA_ROOT`、`RINGFITNESS_ACTIVITY_PROCESSED_DATA_ROOT`、`RINGFITNESS_ACTIVITY_CLOUD_HISTORY` | 后续在后端工作副本中统一配置解码数据、处理结果与去重账本位置 |

凭据只写入本地受控配置。实际脚本不会自动寻找 `.seafile_token`，如选择文件需显式设置 `RINGFITNESS_SEAFILE_TOKEN_FILE`。

现有完整同步主入口还加载 Junction/Oura 且会访问睡眠和活动两种来源。后续按独立版规格提供活动专用入口，或使用桌面客户端同步到本机收件目录后运行独立导入器；这些后端路径本次均未执行。

## 5. 后续真机检查

连接并授权 Android 测试手机后，记录实际安装 APK 哈希；确认测试 session 创建时使用新的活动上传目标，完成采集并核对云盘 ZIP 与同一 session。旧活动 session 的上传地址在创建时已保存，不会因本次配置变更自动迁移。

当前构建仍采用原版身份流程；新独立版本的本地编号与采集改造按已确认方向继续开发。真实 App 闭环验证需明确被测版本，不以本次电脑探针代替。
