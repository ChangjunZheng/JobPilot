# JobPilot 开发路线图与进度

| 项 | 内容 |
|---|---|
| 最后更新 | 2026-10-02 |
| 当前迭代 | **I-1（出口已达成：I-1a/I-1b/I-1c 完成，剩 §4.4 顺带清理）** |
| 已完成 | I-0 |
| 最近验证 | 67 个测试通过（含真实 MySQL 认领/上限/接管与 202 契约回归）；`check-arch.sh` 全部通过；真实 Redis 登出撤销端到端通过 |

> **本文件是「进度状态」的唯一事实来源。**
> BRD / PRD / ARCHITECTURE 只回答「要做什么」和「为什么这么做」，**不记录做到哪一步**。
> 更新进度只改本文件；迭代的完整产出与出口条件见 [BRD §9](./BRD-求职Copilot需求文档.md) 与 [PRD §10](./PRD.md)。
>
> **看图例**：`[x]` 已完成 · `[ ]` 未开始 · `[~]` 进行中（在行尾注明卡在哪）
---

## 1. 总览

| 迭代 | 主题 | 状态 | 出口（一句话） |
|---|---|---|---|
| **I-0** | 技术基线 + RAG 最小闭环 | ✅ **已完成** | 导入 → 嵌入 → 向量检索 → 引用问答的 API 闭环可用 |
| **I-1** | 账号 + 租户隔离 + 导入异步化 | [~] **I-1a/I-1b/I-1c 完成，§4.4 清理中** | 新用户可注册并完成导入→问答；跨租户越权用例全通过 |
| **I-2** | Agent 最小闭环 | ⬜ 未开始 | 自研 ReAct runner + `knowledge_search` + JD 分析 + trace + HITL |
| **I-3** | 长期记忆与投递管理 | ⬜ 未开始 | Memory + 投递 CRUD + 用量计量，全部通过隔离用例 |
| **I-4** | 产品化外壳 | ⬜ 未开始 | 四个页面可用；10~20 个真实 JD 端到端演练通过 |
| **I-5** | 商业化与合规收口 | ⬜ 未开始 | 配额、订阅计费、数据导出自助化、SSE |
| **对外发布** | — | ⬜ **锁定中** | **仅在 I-1 完成后开放注册** |

> **I-1 是发布前置**：在租户隔离落地前不对外开放注册，也不接受真实用户的私密材料。理由见 [ARCHITECTURE §1.7](./ARCHITECTURE.md)。

---

## 2. 开工前（当前阻塞）

- [x] **提交文档改动** —— 已拆为 4 个提交（`8ceaee5` 产品定位 / `f9e1b09` 架构 / `684a308` ROADMAP / `0058c23` 零散注释）
- [x] **验证 Redis 连通性** —— 本机用 `D:\Workspace\TechResources\Redis\Redis-8.6.2-Windows-x64-msys2-with-Service`，8.6.2 / 6379 / `bind 127.0.0.1` / **无 `requirepass`**。已用 `redis-cli client list` 确证应用侧 Lettuce（6.8.2.RELEASE）连接真实建立，**配置正确性已验证**
- [x] **Redis 常驻** —— 已注册为 **Windows 服务**：`RedisService.exe install`，`START_TYPE: AUTO_START`（开机自启），当前 `RUNNING`。服务二进制路径与 `redis.conf`、`--dir` 均为绝对路径（避免服务工作目录不同导致 `dir ./` 落到 `C:\Windows\System32`）。管理命令：`net start Redis` / `net stop Redis`，卸载 `RedisService.exe uninstall`
- [x] **补 `application-local.yml` 的 `spring.data.redis` 配置块** —— 已补（含 `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` 占位符）；`.example` 同步补齐 `spring.autoconfigure.exclude` 与 `chroma-collection-id`，两边不再漂移
- [x] **确认 I-0 数据库有无真实数据** —— 2026-10-02 查实：仅 `u-demo` 演示数据（6 文档 / 9 chunk / 1 测试账号，2026-09-26 产生），按预登记规则**直接清库**（TRUNCATE 四表）。Chroma 中 u-demo 的孤儿向量归 §4.4 清理项处理
- [ ] （可选）确认 Chroma（:8000）与 Ollama（:11434）是否需启动 —— I-1 编码与单测不依赖，但端到端验证需要

> **关于 Redis 的持久方式**：`D:\Workspace\TechResources\Redis\Redis-8.6.2-Windows-x64-msys2-with-Service\RedisService.exe` 提供三种形态——
> - `RedisService.exe install -c redis.conf`：注册为 **Windows 服务**，默认 `--start-mode auto`（开机自启），随系统常驻，用 `uninstall` 卸载；
> - `start.bat` / `redis-server.exe redis.conf`：前台运行，**关掉窗口即停止**；
> - 随 Claude Code 会话后台启动：**会话或后台任务超时后即被杀**，不适合作为开发环境的常规做法。
>
> 前两种都可用，取决于是否希望它开机自启。**第三种已证明不可靠**（一次验证后就停了）。

## 2.1 工程基础设施（本会话新增）

- [x] **CI 门禁** —— `.github/workflows/ci.yml`：MySQL 8.4 service container + JDK 21 + `check-arch.sh` + `mvn test`。**已实测确认只需要 MySQL**，Chroma/Ollama 缺失属预期（向量库不可达 → 告警 + 保留关键词降级，不阻断启动）
- [x] **本地基础设施编排** —— `docker-compose.yml`：MySQL 8.4 + Redis 7，带 healthcheck 与命名卷
- [ ] **ADR 目录** —— 把 §1.4.1（LangGraph4j）、§8.1（降级边界）这类决策从架构文档中抽出为独立、只增不改的记录；当前它们混在 ARCHITECTURE 里，随主文档一起被改写
- [x] **测试覆盖 I-1 的新约束** —— 越权用例与 ThreadLocal 清理回归（`a57dbb4` + 本轮降级回归，见 §4.1）

---

## 3. 待决策（会卡住 I-1 编码）

> 2026-10-02：本节清空——密码哈希（BCrypt 强度 10）与 JWT 参数（HS256 + access TTL 2h）随 I-1a 落地；存量数据经查证仅为演示数据并已清库，无迁移需求。结论正式归档到 ARCHITECTURE 的动作与 §2.1 的 ADR 条目一并处理。其余待决项见 [PRD §12](./PRD.md)。

---

## 4. I-1 · 账号 + 租户隔离 + 导入异步化

> 完整出口条件见 [PRD §10](./PRD.md) 与 [ARCHITECTURE §9](./ARCHITECTURE.md)。

### 4.1 I-1a · 租户隔离骨架（优先，单独就有价值）

**状态：** `[~]` I-1a 全部完成；I-1b（登出撤销 / 隐私明示 / 安全日志）已完成；剩 I-1c 导入异步化。

**做完这一步，现有接口就安全了**——它单独消除了「`userId` 由请求体传入」这个安全缺口。

- [x] `User` / `Credential` 表 —— **credential 用 `(provider, identifier)` 唯一键，不用 email 唯一键**（理由见 [ARCHITECTURE §14.2](./ARCHITECTURE.md)）（`168232a`）
- [x] `security` 包 + `UserContext`（ThreadLocal，`afterCompletion` 必须 `remove()`）（`168232a`）
- [x] `TenantLineInnerInterceptor` 注册到 `MybatisPlusInterceptor`（`168232a`；分页拦截器按 `MybatisPlusConfig` 注释刻意未加，引分页时必须补在租户拦截器之后）
- [x] `KnowledgeController` 移除入参 `userId`，改为凭证解析（`168232a`）
- [~] 越权集成测试（六个业务对象 × 读/改/删/检索）—— 现有业务对象（文档读/检索/降级）已覆盖（`a57dbb4` + 本轮降级回归）；其余业务对象随 I-3 落地时补
- [x] `ThreadLocalCleanupTest` 形式的跨请求污染回归（`a57dbb4`）

### 4.1.1 I-1a 已完成项（本轮）

- [x] **认证与租户隔离核心代码** —— `UserContext`、JWT/BCrypt、`AuthInterceptor`、账号接口、`TenantLineInnerInterceptor`、Controller 移除入参 `userId`、Chroma fail-closed、关键词降级移除手工 SQL
- [x] **I-1a 核心单测** —— 41 个测试全通过（新增 JWT / BCrypt / UserContext / AuthInterceptor / TenantLineHandler、真实 MySQL + MockMvc 租户隔离与异常响应回归）
- [x] **I-1a 代码提交** —— 从「剩余项」移出，本条随该提交一并入库
- [x] **越权与隔离集成测试提交** —— `a57dbb4`：A/B 双账号 × 文档读/检索越权断言、租户拦截器 SQL 实证、无上下文 fail-closed、无效令牌不残留上下文；含 `GlobalExceptionHandler` 405/安全文案修复及单测
- [x] **空知识库降级集成回归** —— `KeywordFallbackDegradationIntegrationTest` 3 例：与 §4.1.2 的单元级覆盖（`emptyReadyDocumentSetSkipsChunkQuery…`）互补，在真实 MySQL 上锁定「空集合不拼 `IN ()`」「非 READY 文档不进降级检索」「纯符号 query 空返回」「降级路径同样不泄跨租户数据、请求体 `userId` 不生效」

### 4.1.2 I-1a 剩余项（下一步）

- [x] 集成越权测试：A/B 两账号 × 文档读 / 检索，断言不能跨租户
- [x] `ThreadLocalCleanupTest` 形式的跨请求污染回归（当前已有纯单元 `UserContextTest`）
- [x] 租户拦截器 SQL 实证测试：`selectById` / `selectByIds` / `selectList` 均不跨租户
- [x] 测试空知识库关键词降级（避免 `IN ()` 类问题）—— 单元级 `emptyReadyDocumentSetSkipsChunkQuery…` + 集成级 `KeywordFallbackDegradationIntegrationTest` 双层锁定
- [x] 修复 `GlobalExceptionHandler` 的通用异常响应：未知 HTTP 方法返回 405，未知异常不暴露异常类名
- [x] 登出与 Redis jti 撤销 —— `POST /api/v1/auth/logout` + `TokenBlacklistService`（条目 TTL = 令牌剩余寿命；Redis 不可用 fail-open，取舍见类注释）；端到端回归 `LogoutIntegrationTest`，Redis 不可用的 CI 环境整体跳过（拦截器层有 mock 兜底用例）
- [x] 注册接口（`168232a`）
- [x] 登录 / 登出接口（登录 `168232a`，登出 I-1b）
- [x] 注册流程的隐私政策与数据用途明示 —— `GET /api/v1/auth/privacy-notice` + 注册强制 `privacyConsent=true`（文案在 `application.yml`；同意版本持久化留痕挂 §4.4）
- [x] JWT 签发与校验 + **登出后原凭证立即失效**（`168232a` + I-1b）
- [x] 未认证返回 401；跨租户返回 404（测试锁定）；两者均写 `SECURITY` 前缀安全日志（`GlobalExceptionHandler`，I-1b）

### 4.3 I-1c · 导入异步化

**状态：** `[x]` 全部完成（2026-10-02，67 个测试通过）。

- [x] 导入改为「落 `PENDING` 即返回 `202` + `documentId`」，索引移出请求线程 —— `KnowledgeController` 202 + `DocumentIngestService.enqueue`；原文落 `kb_document.content`（worker 的事实来源，reindex 前提）
- [x] DB 队列 worker：`SELECT ... FOR UPDATE SKIP LOCKED` 认领 —— `IngestWorker` + `KbDocumentMapper.selectClaimCandidates`（`@InterceptorIgnore` 的四个跨租户面，论证见 mapper 注释）
- [x] 重试状态存行内（`retry_count` / `next_retry_at`）—— V3 迁移；确定性校验失败（空白内容等）直接 FAILED 不消耗重试，暂态失败指数退避（`retryBackoff` × 2^n）
- [x] **启动时接管僵死任务**：超时仍为 `PROCESSING` 的行重置为 `PENDING` —— `resetStaleProcessing`（按 `updated_at` 判僵死）
- [x] 每租户并发上限，防单租户批量导入饿死其他租户 —— 认领时比对在途计数（`maxPerTenant`，默认 2）
- [x] **不使用 `@Async`**，不引入消息队列（理由见 [ARCHITECTURE §4.1](./ARCHITECTURE.md)）—— 调度用自建 `ScheduledExecutorService`（守护线程池），行即消息

### 4.4 I-1 · 顺带清理

- [ ] 20 条评测集（JSONL，含失败归因分类；格式与归因口径见 [PRD §9.2](./PRD.md)）
- [ ] consent 版本持久化留痕（合规）：注册当前只校验同意，未存「同意的版本号 + 时间」，正式对外开放前补
- [ ] `ApiResponse` 补 `requestId` 字段
- [ ] Testcontainers 集成测试基类（顺带解决 `mvn test` 必须依赖本机 MySQL）
- [ ] 中文关键词 2 字窗口
- [ ] 向量路径候选池
- [ ] reindex / 孤儿向量清理

---

## 5. 已知缺陷（I-1 需一并修复）

| 缺陷 | 影响 | 位置 |
|---|---|---|
| `userId` 由请求体传入 **✅ 已修复（`168232a`）** | 安全缺陷——客户端可任意指定身份 | `KnowledgeController` |
| 索引同步执行 | 单个请求占用线程数十秒到数分钟；被反代默认超时切断 | `DocumentIngestService` |
| `PROCESSING` 僵死行无接管 | JVM 重启后该文档永久停留在中间态 | 同上 |
| `ApiResponse` 缺 `requestId` | 用户报障无法关联服务端日志 | `common` |
| 文档类型分发散在三处 | 新增格式时 `ChunkSplitter` 会静默按纯文本切 | `KnowledgeController` / `DocumentIngestService` / `ChunkSplitter` |
| `mvn test` 全量上下文依赖本机 MySQL | CI 与协作成本 | 测试基础设施 |

---

## 6. 更新约定

1. **只在本文件勾选进度**。BRD / PRD / ARCHITECTURE 不记录状态，避免多处漂移；
2. 迭代**完成时**才更新第 1 节总览表的「状态」列，进行中不改；
3. 遇到阻塞在条目行尾写 `[~] 卡在：___`，不要只在私下记；
4. 新增迭代时，出口条件先写入 BRD §9 与 PRD §10，再回到本文件拆任务；
5. **待决策项做出决定后，从第 3 节删除并在 ARCHITECTURE / PRD 对应章节记录结论**——不要让已决事项留在待决列表里。
