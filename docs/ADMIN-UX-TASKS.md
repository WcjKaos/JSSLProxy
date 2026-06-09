# 管理 UI 与 CLI 任务计划

> 目标：在不扩展代理核心能力的前提下，把已有管理 API 做成更易用的运维入口。HTML 管理页优先；CLI 值得做，但定位为薄 admin API 客户端，放在 UI 之后。

## 范围结论

- 必做：一个轻量 HTML 管理页，用于查看运行状态、证书状态、指标摘要、拒绝证书记录、黑白名单，并执行少量受控管理操作。
- 值得做：一个 `jsslproxyctl` 风格 CLI，方便脚本化、远程服务器和 CI 环境操作。
- 不做：独立前端应用、React/Vue 构建链、用户系统、复杂 RBAC、配置编辑器、证书签发/生命周期平台、通用 API 网关能力。

## 共同安全原则

- 管理 JSON API 继续使用现有 `Authorization: Bearer <token>` 或 `X-Admin-Token`。
- token 不进入 URL、日志、HTML 源码、配置文件或持久化存储；浏览器端最多使用 `sessionStorage`，CLI 默认从环境变量读取。
- UI 静态壳页面不能内嵌任何运行时敏感数据；所有数据必须由受保护 API 拉取。
- UI 不展示私钥、keystore 字节、完整证书 DER、明文密码或内部异常细节。
- 所有写操作继续产生审计日志，UI/CLI 文档必须提示审计副作用。

## 里程碑 A：轻量 HTML 管理页

### T-31 · UI 范围与安全设计

**交付物**：`docs/ADMIN-UI.md` 或设计小节、路由/鉴权决策、端点映射表。

**实现要点**
- 明确 UI 入口：推荐 `GET /admin/ui` 返回静态壳页面；JSON API 仍走 `/admin/*` 鉴权。
- 若浏览器无法给初始页面导航附加 Bearer token，允许 UI 壳页面匿名访问，但页面不得包含任何服务状态或 secret。
- 设计紧凑的产品型管理界面：顶部状态栏、主体信息区、名单管理区、危险操作区；避免营销页和装饰性大屏。
- 明确自动刷新策略：默认 5 秒，支持暂停，标签页隐藏时降频或暂停。

**验收标准**
- 路由、鉴权、token 存储、CSP、缓存策略和错误状态有书面决策。
- 每个 UI 面板都映射到现有真实端点，不要求新增代理能力。
- 明确拒绝 query token、localStorage token、服务端 session 和后端 secret 持久化。

**测试标准**
- 设计评审通过：能说明未授权用户只能看到静态壳页面。
- 与 `docs/ADMIN-API.md` 端点逐项对齐，无虚构接口。

### T-32 · 静态 UI 资源服务

**建议文件**：`src/main/resources/admin-ui/index.html`、`src/main/java/com/sslproxy/management/handler/AdminUiHandler.java`、`ManagementInitializer.java`、相关测试。

**实现要点**
- 在管理服务内提供静态 HTML/CSS/JS，不引入 Node 构建链。
- `ManagementAuthHandler` 需明确放行 UI 静态壳路径，同时继续保护管理 JSON API。
- 响应头包含 `Content-Type`、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`、`Cache-Control` 和合理 CSP。
- UI 资源只从 classpath 读取，不依赖当前工作目录。

**验收标准**
- `GET /admin/ui` 返回 200 和 HTML。
- 未配置或错误 token 时，UI 页面能加载，但所有受保护数据请求返回 401/403 并显示可理解状态。
- `/admin/info`、`/admin/blacklist` 等 API 鉴权行为不被 UI 路由放行影响。
- 不加载外部 CDN、字体、脚本或图片。

**测试标准**
- 新增 handler/unit 测试覆盖 UI 入口、Content-Type、安全响应头、404。
- 新增鉴权回归测试：`/admin/ui` 可加载，`/admin/info` 无 token 仍拒绝。
- Docker 中运行 `./gradlew test --tests "*Management*"` 通过。

### T-33 · UI API 客户端与状态模型

**实现要点**
- 封装浏览器端 fetch：统一注入 Bearer token、超时、错误解析和 JSON/text 处理。
- UI 状态至少包含：未输入 token、连接中、加载中、401/403、部分接口失败、刷新暂停。
- token 输入后仅保存在内存或 `sessionStorage`；提供清除 token 按钮。
- API base 默认同源，允许手动覆盖管理地址仅用于本地调试，禁止把 token 拼入 URL。

**验收标准**
- token 缺失时不发起受保护请求，页面提示输入 token。
- token 错误时清楚展示 401/403，不重复刷屏请求。
- 单个接口失败不导致整页空白，其他面板仍可显示。

**测试标准**
- 使用浏览器或 Playwright 容器验证 token 输入、刷新、清除、401/403 状态。
- JS 逻辑保持可人工审查；如拆分 JS 文件，增加基础 DOM/API mock 测试或 smoke 测试。

### T-34 · 只读状态面板

**覆盖端点**：`/health`、`/ready`、`/metrics`、`/admin/info`、`/admin/stats`、`/admin/certs`、`/admin/rejected-certs?limit=50`。

**实现要点**
- 显示服务状态、ready 原因、版本、配置哈希、drain 状态、连接数、请求/错误指标摘要。
- 证书面板显示 subject、issuer、notAfter、fingerprint、reloadCount，并突出即将过期状态。
- rejected-certs 面板显示时间、subject、fingerprint、reason、clientIp，支持 limit 控制。
- Prometheus 文本指标只做关键摘要解析；不做完整图表系统。

**验收标准**
- 所有面板字段来自真实 API 响应。
- 指标解析失败时展示原始可复制链接或错误提示，不影响其他面板。
- 指纹可复制，长字段有换行或截断，不破坏移动端布局。

**测试标准**
- 使用 Docker smoke 环境验证页面能读取 health/ready/metrics 和 admin 信息。
- 增加至少一个测试或手工记录覆盖证书到期状态、rejected-certs 为空状态。

### T-35 · 黑白名单与拒绝证书操作

**覆盖端点**：`GET/POST/DELETE /admin/whitelist`、`GET/POST/DELETE /admin/blacklist`、`GET /admin/rejected-certs`。

**实现要点**
- 黑名单、白名单分区展示，支持手动输入 SHA-256 指纹添加/删除。
- 从 rejected-certs 记录一键加入黑名单或白名单，但必须二次确认。
- POST/DELETE 继续使用请求体传 fingerprint，不改成 query 参数。
- 输入支持冒号分隔和 64 位十六进制，并在前端做格式提示。

**验收标准**
- 添加/删除成功后列表自动刷新。
- 指纹格式错误时不发送请求，给出明确错误。
- 黑白名单冲突时按后端结果展示错误，不在前端伪造规则。
- 写操作按钮在请求进行中有 disabled/loading 状态，避免重复提交。

**测试标准**
- 管理 API 现有名单测试保持通过。
- UI smoke 覆盖添加黑名单、删除黑名单、查看空列表或已有列表。
- 审计日志中能看到名单写操作事件。

### T-36 · 危险操作面板

**覆盖端点**：`POST /admin/reload`、`POST/DELETE /admin/drain`、`GET/DELETE /admin/connections`。

**实现要点**
- reload 支持 `config`、`certs`、`all` 三种 target。
- drain 支持开始、取消和可选 timeout。
- 连接终止按 fingerprint 执行，优先从连接列表或证书记录复制。
- 危险操作必须有明确确认文案，按钮标签说明实际动作，例如“Reload certs”“Start drain”“Terminate connections”。

**验收标准**
- reload/drain/terminate 操作成功和失败都有明确反馈。
- drain 后 ready 面板能显示 503 或 drain 状态，取消后能恢复。
- 终止连接返回 terminated 数量并刷新连接统计。
- 不把危险操作放在默认高亮主按钮位置。

**测试标准**
- Docker smoke 或集成环境验证 reload 和 drain/cancel。
- 连接终止可用现有集成测试覆盖；UI 层至少验证请求参数和反馈。

### T-37 · UI 文档、可访问性与发布门禁

**实现要点**
- 更新 README、`docs/ADMIN-API.md`、`docs/DEPLOYMENT.md`：说明 UI 地址、token 输入方式、生产暴露建议。
- 增加 UI 使用截图或文字流程，但不引入真实 secret。
- 检查键盘可用、焦点状态、颜色对比、移动端布局。
- CI 不强制引入重型前端构建；如加入 Playwright，必须使用 Docker 可复现。

**验收标准**
- 新用户按文档能打开 UI、输入 token、查看状态、操作名单。
- 生产文档明确管理端口仍应绑定内网或 localhost，并配合网络访问控制。
- 页面在 1366px 桌面和 390px 手机宽度下无文本重叠或按钮溢出。

**测试标准**
- Docker JDK 17 中 `./gradlew test`、`./gradlew build` 通过。
- Docker smoke test 增加 UI HTML 可访问检查。
- 至少一次浏览器人工验收或 Playwright 截图记录覆盖桌面和移动宽度。

## 里程碑 B：`jsslproxyctl` CLI

### 是否值得做

值得做，但不应早于 HTML UI。UI 解决“看见当前状态、快速排障”的问题；CLI 解决“脚本化、远程服务器、CI/CD 和无浏览器环境”的问题。CLI 必须只是 admin API client，不复制代理业务逻辑。

### T-38 · CLI 方案评估与命令设计

**实现要点**
- 比较两种方案：Java `AdminCli` 主类（推荐，跨平台、复用 Gson/JDK HttpClient）与 shell/PowerShell curl wrapper（更轻但依赖 curl）。
- 明确命令树、输出格式、退出码、token 来源和错误处理。
- 推荐约定：`JSSLPROXY_ADMIN_URL`、`JSSLPROXY_ADMIN_TOKEN`，并允许 `--url`、`--token` 覆盖。
- 默认输出人类可读文本；提供 `--json` 供脚本使用。

**验收标准**
- 文档列出最终方案和不采用方案的原因。
- 命令覆盖状态查询、证书、拒绝证书、黑白名单、reload、drain、连接终止。
- 明确不保存 token、不读取浏览器 sessionStorage、不支持 query token。

**测试标准**
- 方案评审确认不会引入新的长期运行进程或服务端状态。
- 命令设计与 `docs/ADMIN-API.md` 参数位置一致。

### T-39 · CLI 基础框架

**建议文件**：`src/main/java/com/sslproxy/cli/AdminCli.java`、`src/main/java/com/sslproxy/cli/AdminHttpClient.java`、`build.gradle.kts`、CLI 测试。

**实现要点**
- 使用 JDK 17 `java.net.http.HttpClient` 和现有 Gson。
- 支持 `--url`、`--token`、`--timeout`、`--json`、`--help`。
- token 优先级：命令行参数 > `JSSLPROXY_ADMIN_TOKEN`；URL 优先级：命令行参数 > `JSSLPROXY_ADMIN_URL` > `http://localhost:9090`。
- HTTP 错误映射到稳定退出码：0 成功，2 参数错误，3 鉴权失败，4 服务不可达，5 服务端错误。

**验收标准**
- `jsslproxyctl --help` 可展示命令和示例。
- 所有请求默认使用 Bearer token header。
- 错误输出不包含 token。
- Gradle 能生成可执行入口或清晰的运行命令。

**测试标准**
- 单元测试覆盖参数解析、token 优先级、退出码、错误脱敏。
- Docker JDK 17 中 `./gradlew test --tests "*Cli*"` 通过。

### T-40 · CLI 只读命令

**命令建议**
- `status`：组合读取 health、ready、admin info。
- `stats`：读取 `/admin/stats`。
- `certs`：读取 `/admin/certs`。
- `rejected-certs --limit 50`：读取拒绝记录。
- `metrics`：拉取 `/metrics`，支持原样输出或摘要。

**验收标准**
- 每个命令支持 `--json` 输出原始或结构化 JSON。
- 服务不可达、401、403、非 JSON 响应都有明确错误。
- `status` 不因 `/metrics` 解析失败而掩盖 health/ready 结果。

**测试标准**
- 使用 mock HTTP server 单测每个命令的成功和失败路径。
- Docker smoke 环境运行至少 `status`、`certs`、`rejected-certs`。

### T-41 · CLI 写操作命令

**命令建议**
- `blacklist list|add|remove <fingerprint>`
- `whitelist list|add|remove <fingerprint>`
- `reload config|certs|all`
- `drain start [--drain-timeout 30]`
- `drain cancel`
- `connections list`
- `connections terminate <fingerprint>`

**实现要点**
- fingerprint 参数在 CLI 端做基础校验，但以后端为准。
- 默认对危险操作要求 `--yes`，非交互环境未带 `--yes` 直接失败。
- 写操作成功后显示后端返回结果和审计提示。

**验收标准**
- 黑白名单 add/remove 使用请求体发送 fingerprint。
- reload/drain/terminate 不会在未确认时执行。
- `--json` 模式不输出多余人类提示，便于脚本解析。

**测试标准**
- mock HTTP server 验证方法、路径、query、body 和 header。
- Docker smoke 环境至少覆盖 blacklist add/remove、reload certs、drain start/cancel。

### T-42 · CLI 文档与发布门禁

**实现要点**
- 更新 README、README_EN、`docs/ADMIN-API.md` 或新增 `docs/CLI.md`。
- 在 Docker test-runner 中增加 CLI smoke 脚本或复用现有 smoke 环境。
- 发布清单加入 CLI 命令帮助、退出码、token 脱敏检查。

**验收标准**
- 新用户能按文档通过 CLI 查看状态和维护名单。
- 文档区分 UI 适合人工排障，CLI 适合脚本和无浏览器环境。
- 发布包或运行方式在 Windows、Linux/macOS 上有明确说明。

**测试标准**
- Docker JDK 17 中 `./gradlew test`、`./gradlew build` 通过。
- Docker smoke test 覆盖至少 3 个 CLI 命令。
- Windows PowerShell 下验证帮助命令和一个只读命令。

## 执行顺序

1. 先做 T-31，确认 UI 安全边界。
2. T-32 至 T-36 按顺序实现，T-37 作为 UI 出关门禁。
3. T-38 完成后再决定是否进入 CLI 实现。
4. T-39 至 T-42 可在 UI 完成后独立推进，不阻塞代理核心发布。
5. 多代理并行推进时，按 `docs/PARALLEL-AGENT-TASKS.md` 的写入范围拆分，避免 UI、CLI、管理 API、smoke 和文档互相覆盖。

## 完成定义

- UI 和 CLI 都只消费现有管理 API，不引入范围外代理能力。
- Docker 化 `test`、`build`、smoke test 通过。
- token、安全头、审计日志、错误脱敏均有测试或明确验收记录。
- README 和运维文档能解释“什么时候用 UI、什么时候用 CLI、如何安全暴露管理端口”。
