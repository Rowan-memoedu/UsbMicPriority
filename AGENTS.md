# USB Mic Priority

- 本项目是通过 ADB 激活的 Android 本地辅助服务：USB 输入优先，保留蓝牙输出，USB 拔出时恢复。
- 技术栈为 Java 8 源码、Android 系统 API 和 PowerShell 7；没有 APK、网络服务或远程后端。
- 构建：`pwsh -NoProfile -File .\Build.ps1`。路由逻辑或恢复记录变更必须通过主机测试。
- 激活/停用：`Manage-UsbMicPriority.ps1 -Action Start|Stop -Serial '<当前设备>'`。
- 状态：`-Action Status`；实际设备和偏好：`-Action Inspect`；异常退出后的恢复：`-Action Recover`。
- `src` 是源码，`tests` 是主机和设备验收；产物只写 `D:\Artifacts\UsbMicPriority`。
- 不固定蓝牙地址、USB 声卡号或设备名称；读取当前设备枚举。
- 只能暂时屏蔽蓝牙输入端，不修改输出端或关闭 HFP 配置。
- 平台修改前持久保存恢复记录；停用必须恢复，不与另一服务实例并发运行。
- 部署新 DEX 前，先停用旧服务并确认恢复；不得截断仍运行进程映射的 DEX。
- 不读取录音内容、用户任务或输入法文字；设备原始数据、日志及恢复记录不得入 Git。
- 不根据 API 返回成功宣称实际收音成功；必须关联录音会话与输入端口，必要时检查并行输出。
- 已验证 Android 17 / HyperOS 3 的 USB 接收器和经典蓝牙 TWS；其他系统、LE 耳机、真实电话和微信通话不能自动沿用验收结论。
- 服务可在无线调试关闭后继续运行；保留 USB 调试开关，手机重启后重新激活。
- 机制和限制以 README 为准；新增通话行为或系统权限变更先说明影响并讨论。
