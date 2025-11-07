# 报警通知小程序

仓库现在只保留一个页面：`pages/alerts/index`。该页面支持输入 WebSocket 地址、建立连接、心跳保活、自动重连、实时弹窗提示以及本地模拟报警，专注于接收后端推送的报警通知。

## 目录结构

- `miniprogram/app.js|app.json|app.wxss`：全局配置与基础样式
- `miniprogram/pages/alerts/*`：报警通知页面的视图与逻辑

## 使用方法

1. 使用微信开发者工具打开本项目。
2. 在体验版或真机上打开「报警通知」页面。
3. 输入后端提供的 `ws://` 或 `wss://` 地址后点击「连接服务」，即可收到实时告警。

## 接入 Java Spring Boot 后端需要准备什么

1. **可访问的 WebSocket 服务**
   - 为 Spring Boot 项目引入 `spring-boot-starter-websocket`（或 STOMP 方案：`spring-boot-starter-websocket` + `spring-messaging`）。
   - 暴露一个端点（如 `/ws/alerts`）处理 `ws://`/`wss://` 连接。

2. **合法的服务器域名**
   - 在小程序后台 → 开发 → 开发设置中把 WebSocket 服务器域名加入「合法域名」。
   - 生产环境需要 `wss://`，并在服务器上部署可信的 TLS 证书。

3. **鉴权策略**
   - 小程序 WebSocket 连接无法自定义 Header；通常通过 URL 查询参数（例：`wss://host/ws/alerts?token=jwt`）传递身份令牌。
   - 后端在握手阶段验证 token，拒绝未授权连接。

4. **统一的消息格式**
   - 小程序按 JSON 解析文本消息，并映射到告警模型：
     ```json
     {
       "id": "uuid-1",
       "level": "WARN",
       "message": "磁盘使用率超过 85%",
       "source": "order-service",
       "timestamp": 1715731200000,
       "details": "可选的扩展字段"
     }
     ```
   - `timestamp` 使用毫秒值；缺失字段将由前端自动补全。

5. **心跳/保活支持**
   - 小程序每 45 秒发送 `{"type":"PING","timestamp":...}`；后端可忽略或回 `{"type":"PONG"}`，但不要因此断开连接。
   - 连接关闭时请清理会话状态，防止重复推送。

6. **告警推送逻辑**
   - 当监控或业务触发报警后，将告警对象序列化为上述 JSON，通过活跃的 WebSocket Session 广播/单播给目标用户。
   - 可在 Spring Boot 中维护 Session 列表，结合业务标签做到按需推送。

准备好以上条件后，把 WebSocket 地址粘贴到小程序页面即可在手机端收到实时报警通知。
