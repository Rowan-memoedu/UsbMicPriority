# USB 麦克风优先

协作开发、Agent 提交与合并规则见 [CONTRIBUTING.md](CONTRIBUTING.md)。

在已授权的 Android 设备上，通过 ADB 启动无需 Root 的本地音频路由辅助服务。

## 使用行为

- 接入 USB 麦克风后，将普通录音、摄像、语音识别、VoIP、无处理录音和语音表演的系统首选输入设为 USB。
- USB 在位时，仅从音频策略中暂时移除蓝牙麦克风输入端；蓝牙媒体输出、通话输出和 HFP 连接保持可用。这样可覆盖显式指定蓝牙麦克风的应用，不依赖逐个应用的设置。
- 拔下 USB 后，恢复当前仍连接的蓝牙麦克风输入端及之前的录音偏好。设备插拔采用约一秒的检测周期，切换时存在短暂过渡。
- 不读取麦克风样本、不创建录音文件、不监听网络端口、不安装 APK，也不修改系统分区。
- 激活后可关闭系统的无线调试；在已验证的设备上，后台服务和路由规则继续运行。USB 调试开关需保留开启。手机重启后须重新通过 ADB 激活。

## 激活、查询和恢复

电脑需要 PowerShell 7.4+、JDK、Android SDK Build Tools 和已授权的官方 ADB。默认路径见脚本参数。

手机连接并授权后，在项目目录运行：

```powershell
.\Manage-UsbMicPriority.ps1 -Action Start
.\Manage-UsbMicPriority.ps1 -Action Status
.\Manage-UsbMicPriority.ps1 -Action Inspect
```

使用无线 ADB 或同时连接多个设备时，指定手机当前的调试地址或设备标识：

```powershell
.\Manage-UsbMicPriority.ps1 -Action Start -Serial '<手机当前的 IP:调试端口>'
```

使用系统的加密无线调试，无需开启旧的 TCP 5555 模式。端口可能随无线调试重新开启而变化。关闭无线调试后，服务继续在手机本地工作；再次查询或停用时，需要重新建立 ADB 连接。

停用并恢复原设置：

```powershell
.\Manage-UsbMicPriority.ps1 -Action Stop -Serial '<当前设备>'
```

服务意外被系统终止时，查询会显示 `RECOVERY_REQUIRED`，而不是继续声称正在运行。重新连接 ADB 后可恢复原设置，或直接重新激活：

```powershell
.\Manage-UsbMicPriority.ps1 -Action Recover -Serial '<当前设备>'
.\Manage-UsbMicPriority.ps1 -Action Start -Serial '<当前设备>'
```

`Recover` 不会与仍在运行的服务并发修改设置。每次平台修改之前都保存恢复记录；同一次开机内可恢复异常退出前暂时隐藏的输入端。手机重启后，不会重建上一次开机的旧设备端口。停用时保留其他工具后来写入的不同首选设备。

## 机制与兼容范围

服务以 ADB shell 身份运行，调用 Android 的系统音频路由接口：

1. 按音频用途设置 USB 首选输入，覆盖来源 `MIC`、`CAMCORDER`、`VOICE_RECOGNITION`、`VOICE_COMMUNICATION`、`UNPROCESSED` 和 `VOICE_PERFORMANCE`。
2. 仅在 USB 麦克风存在时，将蓝牙音频输入端设为不可用；从不对输出端执行连接状态修改。
3. 使用当前设备枚举结果识别 USB 与蓝牙设备，不固定设备地址、声卡号或产品名称。
4. 检测 USB 移除或收到停用请求后，按恢复记录恢复输入端和系统偏好。

这是一条系统级规则，不是逐 App 配置。它作用于标准 Android 音频策略，不能为本身不支持外置麦克风的厂商私有录音链路增加支持，也不能证明任意应用和任意切换时序都兼容。

已在 Android 17 / HyperOS 3、USB 无线麦克风接收器和经典蓝牙 TWS 耳机上验证。蓝牙 LE 音频设备尚无实机验收。

已通过的设备检查包括：

- 滴答清单内使用豆包输入法，实际录音输入为 USB。
- 豆包录音时，媒体流仍经蓝牙 A2DP 输出且未静音。
- 本地 VoIP 音频会话同时使用 USB 输入和蓝牙 SCO 输出，没有拨打电话。
- USB 物理拔插后规则自动恢复和重新应用。
- 停用后，蓝牙麦克风和全部原始录音偏好恢复。
- 服务异常退出后的恢复记录读取和实际恢复。
- 关闭无线调试期间，服务存活且系统规则仍在。

真实电话、微信等应用的真实通话没有拨打，不能据本地 VoIP 模拟宣称这些通话链路已验收。

## 构建与验证

```powershell
.\Build.ps1
.\Test-DeviceAudio.ps1 -Serial '<当前设备>'
```

构建使用已安装的 JDK 和 Android SDK，不下载依赖。主机测试覆盖输入端与输出端分离、不同 USB 设备、拔插恢复、断开耳机时不创建虚假端口、部分失败回滚、异常恢复和保留后续外部修改。

设备测试创建本地语音通信音频会话，播放静音数据并检查实际输入输出设备；不读取录音样本、不连接真人、不修改已部署服务的首选输入规则。

源码位于当前 Git 仓库，构建与验收产物位于 `D:\Artifacts\UsbMicPriority`。手机代码、状态和恢复记录位于 `/data/local/tmp/usb-mic-priority`，目录权限为 shell 用户专用；原始设备状态、日志和恢复记录不得提交 Git。

## 代码入口

- `src/io/memoedu/usbmicpriority/RoutingController.java`：不依赖 Android 的状态机及恢复顺序。
- `src/io/memoedu/usbmicpriority/Main.java`：Android 接口、恢复记录和后台生命周期。
- `Build.ps1`：主机测试及 DEX 构建。
- `Manage-UsbMicPriority.ps1`：激活、查询、停用和恢复。
- `Test-DeviceAudio.ps1`：本地通话音频路由验收。

接口依据：[Android AudioManager 系统采集偏好](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioManager.java)、[AudioSystem 设备连接接口](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioSystem.java)。
