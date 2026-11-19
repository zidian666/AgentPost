# AgentPost 邮件协议 v1

每台主机有一个桥接邮箱。为每个手机和主机组合配置一把不同的 32 字节随机密钥。配对密钥以无填充 Base64URL 传递，设备 ID 是原始密钥 SHA-256 摘要的前 24 个小写十六进制字符。通过可信渠道交换密钥。

邮件主题为 `AgentPost v1 request <设备ID>` 或 `AgentPost v1 response <设备ID>`。纯文本 MIME 正文为无填充 Base64URL，字节布局为 12 字节随机 nonce、AES-256-GCM 密文和 16 字节认证标签。附加认证数据是完整主题的 UTF-8 字节，明文是 UTF-8 JSON。外层 MIME 允许按邮件传输要求编码。邮箱服务商仍可看到邮件地址、主题和发送时间。

请求使用 UUID `id`、Unix 秒 `sentAt` 和 `op`。可选字段未使用时为 null 或省略。

| op | 参数 | done.data |
| --- | --- | --- |
| list | 可选 `cursor` | `{sessions, nextCursor}`，最近更新的会话优先 |
| history | `threadId`、可选 `cursor` | `{messages, nextCursor}`，一页内按时间从早到晚 |
| send | `text`、可选 `threadId` | `{thread, history}`。没有 ID 则在主机配置的目录创建会话 |

会话有 `id`、`title`、`preview`、`cwd`、`updatedAt`（Unix 秒）、`status`。消息有 `id`、`role`（user/assistant）和 `text`。游标不透明，只能用于相同操作和会话。超长正文会截断并标记。

响应通过原 `id` 关联。`accepted` 表示已落入主机队列。`done` 携带 `data`。`error` 携带用于界面展示的 `error`，可含 `uncertain: true` 表示必须先核对历史。邮件可重复或乱序，终态优先于回执。收件地址、加密设备 ID 和请求 ID 均需校验，不能单独信任邮件发件人。

主机接受前后 30 分钟内的请求。SQLite 以设备 ID 和请求 ID 去重，并记录发件队列。邮箱投递失败时重试发件。桥接重启不会重跑已开始但未确认结果的任务。Android 在用户明确查询时仅继续收件，不再发送原消息。SMTP 传输走 TLS 465，IMAP 走 TLS 993。桥接数据库位于主机本地，限制为主机用户可读。移除配置中的设备密钥并重启桥接可以撤销配对。
