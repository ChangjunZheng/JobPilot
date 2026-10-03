# JobPilot 开发路线图与进度

| 项 | 内容 |
|---|---|
| 最后更新 | 2026-10-03 |
| 当前迭代 | **I-2 已完成（Agent 最小闭环）；下一个里程碑 I-3（长期记忆与投递管理）** |
| 已完成 | I-0、I-1、I-2 |
| 最近验证 | 110 个测试通过（含真实 MySQL 审批幂等/租户隔离、真机 ReAct 闭环）；`check-arch.sh` 全部通过；真实 Redis 登出撤销端到端通过 |

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
| **I-1** | 账号 + 租户隔离 + 导入异步化 | ✅ **已完成** | 新用户可注册并完成导入→问答；跨租户越权用例全通过 |
| **I-2** | Agent 最小闭环 | ✅ **已完成** | 自研 ReAct runner + `knowledge_search` + JD 分析 + trace + HITL |
| **I-3** | 长期记忆与投递管理 | ⬜ 未开始 | Memory + 投递 CRUD + 用量计量，全部通过隔离用例 |
| **I-4** | 产品化外壳 | ⬜ 未开始 | 四个页面可用；10~20 个真实 JD 端到端演练通过 |
| **I-5** | 商业化与合规收口 | ⬜ 未开始 | 配额、订阅计费、数据导出自助化、SSE |
| **对外发布** | — | ⬜ **前置已满足，待决策** | 原定「I-1 完成后开放注册」——I-1 已于 2026-10-03 完成，是否解锁由产品决定，不由开发窗口自行放开 |

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

**状态：** `[x]` 七项全部完成（2026-10-03，80 个测试通过）。

- [x] 20 条评测集（JSONL，含失败归因分类；格式与归因口径见 [PRD §9.2](./PRD.md)）—— 语料 `eval-corpus.md`（P01~P20，含 5 个**有意留的干扰项**）+ `eval-set.jsonl`（8 事实 / 5 比较 / 4 综合 / 3 无答案）+ `RetrievalEvalRunner`（`EVAL_RUN=true` 手动跑，需真实 Ollama/Chroma）+ `EvalSetStructureTest`（**不依赖基础设施**，随日常 `mvn test` 校验格式、配比与期望段落是否存在）
- [x] consent 版本持久化留痕（合规）—— V4 迁移给 `user_account` 加 `privacy_version` / `privacy_consented_at`；版本号取 `jobpilot.security.privacy-notice-version`，控制器传入、服务落库
- [x] `ApiResponse` 补 `requestId` 字段 —— `RequestIdFilter`（`HIGHEST_PRECEDENCE`）写 MDC + `X-Request-Id` 响应头，`ApiResponse` 工厂方法直接读 MDC；用 Filter 而非拦截器是因为要覆盖 `/error` 与 401 等全部路径
- [x] Testcontainers 集成测试基类 —— `MySqlIntegrationTestBase`：**默认用本机 MySQL，完全不连接 Docker**；仅当环境变量 `JOBPILOT_TEST_DOCKER=true` 时才起 `mysql:8.4` 容器（`ci.yml` 显式设置）。**两条路径均已真实验证**（容器路径 80 测试通过、耗时 3 分 52 秒；本机路径 17.8 秒）。本机启动 Docker 非常卡，因此默认不开启
- [x] 中文关键词 2 字窗口 —— `extractKeywords` 按码点滑窗（汉字 2 字、拉丁 3 字），窗口不会切进代理对
- [x] 向量路径候选池 —— `CANDIDATE_POOL_FACTOR = 3`：放大取候选，阈值截断与 READY 过滤后再 `limit(topK)`
- [x] reindex / 孤儿向量清理 —— 端口加 `deleteByDocumentId`；`process` 开头幂等清场升级为「Chunk + 旧向量」双清，终态清场失败降级为尽力而为（READY 过滤兜底）；`POST /documents/{id}/reindex` 条件更新（`status IN (READY, FAILED)`）兜住「查询后被认领」的竞态

---

## 5. I-2 · Agent 最小闭环

**状态：** `[x]` 全部完成（2026-10-03，110 个测试通过 + 真机闭环验证）。

### 5.1 已完成项

- [x] **协议与端口** —— `com.jobpilot.ai` 新增 `AgentMessage`（sealed，四类角色）/`ChatRequest`/`ChatCompletion`/`ToolCall`/`ToolDefinition`/`ToolExecutionContext`/`ToolExecutionResult`；`ChatPort` 加 `chat(ChatRequest)`，`complete` 保留不动。**类型名刻意避开 Spring AI 的类名**，见 ARCHITECTURE §5.1 记录
- [x] **自研 ReAct 循环** —— `AgentRunner`：最多 5 轮、工具上限 8 次/run、LLM 30s 超时重试 1 次；工具异常与未知工具名都转成结构化失败回填给模型，**绝不外抛到 Controller**；上下文超长时截断并记 trace
- [x] **`knowledge_search`** —— 租户只来自 `ToolExecutionContext`；模型参数里的 `userId` 一律忽略（有回归测试锁定）
- [x] **`job_description_analyze`** —— 只做「解析 JD 字段 + 检索个人材料并附引用」；PRD-FP-2.3 的八项分析字段由**外层模型**产出，工具内不嵌套 LLM 调用（避免延迟翻倍与双层预算）
- [x] **HITL** —— `save_jd_analysis_to_kb` 只落 `agent_approval_draft` 草稿并返回 `PENDING_APPROVAL`，本轮 run 立即结束；用户经 `POST /api/v1/agent/approvals/{id}/approve|reject` 审批
- [x] **幂等两道防线** —— ① 审批 `SELECT ... FOR UPDATE` 锁行 + 状态检查；② `UNIQUE (user_id, idempotency_key)`，捕获 `DuplicateKeyException` 后按租户重读并返回同一草稿
- [x] **trace** —— `agent_trace` + `agent_trace_step` 两张独立表。**不参与检索是结构保证**（检索只读 `kb_document`/`kb_chunk`），不靠过滤条件
- [x] **配置** —— `AgentProperties`（`jobpilot.agent.*`）；`provider-path` 声明但不消费，是本地/云端双路径的接缝
- [x] **API** —— `POST /api/v1/agent/run`、`/approvals/{id}/approve|reject`、`GET /approvals/{id}`；请求体一律不含 `userId`

### 5.2 验证证据（2026-10-03）

| 层次 | 证据 |
|---|---|
| 单元 | `AgentRunnerTest` 11 例（预算截断、异常不外抛、重试次数、HITL 短路、未认证 401）；`KnowledgeSearchToolTest` 5 例（**伪造 `userId` 被忽略**）；`ApprovalDraftServiceTest` 9 例 |
| 真实 MySQL | `ApprovalIntegrationTest` 5 例：唯一索引**真抛** `DuplicateKeyException`、`FOR UPDATE` **真串行化**并发审批、租户拦截器**真覆盖**三张新表、重复审批只建一份文档 |
| 真机闭环 | `AgentE2EIT`（`AGENT_E2E=true` 手动触发）：真实 qwen2.5:3b 调用 `knowledge_search` → 回填证据 → 给出答案 |

全量 `mvn test` **110 通过**；`check-arch.sh` 六条全过。

### 5.3 真机验证挖出的 4 个缺陷（均已修，见 `62e3aa4` / `833d8e5`）

1. **`spring.ai.ollama.*` 从未配置** —— `jobpilot.rag.*` 与 `spring.ai.ollama.*` 是同一批模型的**两个入口**，只有后者能到达注入的 `ChatModel`/`EmbeddingModel`。此前只配了前者，于是嵌入落到 Spring AI 默认的 `mxbai-embed-large`、对话落到 `mistral`，**这两个模型本机都没装**。属**先前就存在的缺口**，被真机验证首次暴露。
2. **`ToolCallingChatOptions` 不回落默认模型** —— 不设 model 时把 `null` 一路传给 Ollama，报 `model cannot be null or empty`。
3. **`ToolCallingChatOptions.builder()` 类型不对** —— Ollama 的 chat model 内部把 options 强转成 `OllamaChatOptions`，必须用 `OllamaChatOptions.builder()`（它本身即实现 `ToolCallingChatOptions`）。
4. **`AgentRunner` 原先没有 system prompt** —— 实测 qwen2.5:3b 在弱提示下**反问用户而不调工具**；把「回答涉及用户经历前必须先调 `knowledge_search`」写死后才稳定触发。**这个 prompt 是闭环能跑起来的前提，不是可选调优。**

### 5.4 明确不做（留给后续）

- [ ] **本地/云端双路径的云端适配器** —— 接缝已留（`ChatPort` 无路径分支、`provider-path` 只允许出现在 Bean 装配处）；云端 provider 与用量计量属 I-3/I-5
- [ ] **跨 run 会话记忆** —— `conversationId` 本轮仅作关联标识，ReAct 历史是 per-run 的；随 I-3 的 Memory 一起做（用无淘汰的内存 Map 更糟，不如明说限制）
- [ ] **`application_*` 工具与投递 CRUD** —— 表还不存在，不造空表
- [ ] **`EXPIRED` 审批状态** —— PRD 列了它，但需要调度器；`PENDING` 长期堆积是已知的小风险
- [ ] 多 Agent、并行工具、SSE

### 5.5 已知风险

- **`AgentE2EIT` 会写 Chroma**。它用 `@Transactional` 回滚 + `process` 的幂等清理，通常不留残留（已核实集合为空）。但 2026-10-03 有一次全量跑出 1 个失败、随后**连续 5 次复现不出**，怀疑是事务回滚与 `ChromaStartupCheck` 启动自检的时序竞争。**未定位到根因，如实记为已知风险。**

---

## 6. 已知缺陷（I-1 需一并修复）

| 缺陷 | 影响 | 位置 |
|---|---|---|
| `userId` 由请求体传入 **✅ 已修复（`168232a`）** | 安全缺陷——客户端可任意指定身份 | `KnowledgeController` |
| 索引同步执行 **✅ 已修复（I-1c）** | 单个请求占用线程数十秒到数分钟；被反代默认超时切断 | `DocumentIngestService`（改为 202 + DB 队列 worker） |
| `PROCESSING` 僵死行无接管 **✅ 已修复（I-1c）** | JVM 重启后该文档永久停留在中间态 | `IngestWorker.resetStaleProcessing` |
| `ApiResponse` 缺 `requestId` **✅ 已修复** | 用户报障无法关联服务端日志 | `RequestIdFilter` / `common` |
| 文档类型分发散在三处 | 新增格式时 `ChunkSplitter` 会静默按纯文本切 | `KnowledgeController:150` / `DocumentIngestService:260` / `ChunkSplitter:50` —— **仍未修**，属 §14.3 ③ 的接缝 |
| `mvn test` 全量上下文依赖本机 MySQL **✅ 已解除（回退式）** | CI 与协作成本 | `MySqlIntegrationTestBase`：有 Docker 走容器，无 Docker 回退本机库 |

---

## 7. 更新约定

1. **只在本文件勾选进度**。BRD / PRD / ARCHITECTURE 不记录状态，避免多处漂移；
2. 迭代**完成时**才更新第 1 节总览表的「状态」列，进行中不改；
3. 遇到阻塞在条目行尾写 `[~] 卡在：___`，不要只在私下记；
4. 新增迭代时，出口条件先写入 BRD §9 与 PRD §10，再回到本文件拆任务；
5. **待决策项做出决定后，从第 3 节删除并在 ARCHITECTURE / PRD 对应章节记录结论**——不要让已决事项留在待决列表里。
