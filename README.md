# AgentPost

在 Android 上打开一台主机的 Codex 会话，读取历史、新建会话和继续对话。手机与配对主机通过邮箱交换加密请求，主机上的 Codex 使用原有账号运行。

## 试用 Android

需要 JDK 17 或更高版本、Android SDK 36。设备需要 Android 8.0 或更新版本。

```sh
./gradlew :android:assembleDebug :client-core:test
```

Windows 使用 `gradlew.bat`。APK 位于 `android/build/outputs/apk/debug/android-debug.apk`。打开应用后可点「体验示例会话」，无需邮箱即可试用会话列表、搜索、历史、发送和代码复制。

## 连接真实主机

1. 准备两个启用了 IMAP/SMTP 的邮箱，分别用于手机和主机。Android 首版使用 QQ 邮箱的服务器地址。
2. 在 Linux 或 macOS 主机上使用运行 Codex 的同一个用户安装桥接程序。确认现有 Codex App Server Unix socket 可连接。
3. 为手机生成专用配对密钥。把主机邮箱授权码写入权限为 `0600` 的单独文件。在本地复制并填写配置，配置文件权限同样设为 `0600`。
4. 在手机上填写个人邮箱及其授权码、主机名称、主机邮箱和配对密钥。连接时应用会验证邮箱登录，并用经过认证的加密请求拉取主机会话。

```sh
cd bridge
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp config.example.toml config.toml
chmod 600 config.toml
python -m agentpost.service --generate-key
python -m agentpost.service --config config.toml
```

需要 Python 3.11 或更高版本。长期运行时，用主机用户自己的服务管理器启动，并把工作目录设为 `bridge/`。使用专用主机邮箱可以和已有的普通邮件问答服务并行运行。

每次连接只显示当前主机的会话。会话列表和最近读过的历史加密保存在手机上，重启后也可直接浏览。点击右上角刷新才会重新获取已有缓存的页面。首次打开某条会话或加载更早的消息时才按需发出邮件请求。断开主机时清除该主机的本地缓存。

邮件链路提供发送状态、主机接收回执和完成回复。暂停等待只暂停手机查询，Codex 仍可能在运行。超时后可查询同一请求，避免重复提交。应用被系统结束后可重新打开会话并刷新历史，旧请求的等待界面不会恢复。首版支持文本与 Markdown，不含即时流式输出、远程审批或附件。

- [交互与架构](docs/design.md)
- [邮件协议](docs/protocol.md)
- [Codex App Server 接口](https://learn.chatgpt.com/docs/app-server)

## 检查桥接服务

```sh
cd bridge
python -m unittest discover -s tests -v
```
