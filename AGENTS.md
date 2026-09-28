# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## 项目

JobPilot 是面向求职流程的个人 Copilot 后端（Java 21 / Spring Boot 4.0 / Maven / MyBatis-Plus + MySQL / Redis / Spring AI 边界 / Ollama + Chroma）。

**当前进度：M-1（RAG 最小闭环）已实现并提交，M-2（Agent runner + 工具 + HITL + trace）未开始。** 仓库根目录 `README.md` 仍停留在骨架阶段的描述，与代码不符；设计与实施计划以 `docs/ARCHITECTURE.md` 为准，实际能力以 `src/main/java` 为准。

## 常用命令

```bash
mvn test                                  # 全部测试
mvn test -Dtest=ChunkSplitterTest         # 单个测试类
mvn test -Dtest=ChunkSplitterTest#plainTextSplitsBySizeWithOverlapOnLongLines   # 单个用例
mvn spring-boot:run                       # 启动
```

本机默认 JDK 不是 21 时用根目录包装脚本（只为当前 Maven 进程设置 `JAVA_HOME`，默认 `D:\develop\Java\jdk-21`，不修改系统环境变量）：

```cmd
run-java21.cmd test
run-java21.cmd spring-boot:run
```

测试全部基于 Mockito mock（mapper/port 都 mock 掉），不依赖 MySQL、Chroma 或 Ollama，可直接跑。

## 运行时的基础设施开关（重要）

`application.yml` 默认 profile 为 `local`，MySQL/Redis 连接信息全部放在 `application-local.yml`（gitignored，需从 `application-local.yml.example` 复制）。**没有 local profile 时应用起不来**：`@MapperScan` 需要 `SqlSessionFactory`，没有 DataSource 就报 `Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required`。所以「不接数据库也能启动」不成立，不要依赖。

RAG 闭环需要同时具备：MySQL（Flyway 建表）、Ollama（`bge-m3` 嵌入 + `qwen2.5:3b` 生成）、本地 Chroma（`chroma/` 目录，`.gitignore` 已忽略，非 Docker）。

**Boot 4 的坑：** 自动配置类被拆到独立 artifact 和包名。`spring-boot-autoconfigure` 现在只剩 core，DataSource/Flyway/Redis 分别在 `spring-boot-jdbc` / `spring-boot-flyway` / `spring-boot-data-redis` 里，包名是 `org.springframework.boot.<tech>.autoconfigure.*`。所以引 Flyway 必须用 `spring-boot-starter-flyway`——只引裸 `flyway-core` 时自动配置根本不在 classpath 上，Flyway 会静默不执行。同理 `spring.autoconfigure.exclude` 里写旧包名会被静默忽略（日志 conditions report 的 `Exclusions: None` 是唯一线索），别照抄 Boot 3 的 FQCN。

配置全部集中在 `jobpilot.rag.*`（`RagProperties`），端口/适配层只读这里，业务层不感知 Ollama/Chroma。

## 架构要点

### 端口/适配器边界（`com.jobpilot.ai`）

业务层只依赖 `ChatPort`、`EmbeddingPort`、`VectorStorePort` 和 JobPilot 自定义的 record（`Citation`、`RetrievalQuery/Result`、`RetrievedChunk`、`SearchMode`）。**Spring AI 与供应商 HTTP/SDK 类型只能出现在 `ai.adapter`**，实现细节（如 Chroma 的 `1 - distance` 换算、Ollama 请求体构造）不得泄漏到 service 层。

`ai.adapter` 下的三个适配器都通过 `HttpClientConfig` 提供的 `ClientHttpRequestFactory` 构造 RestClient：连接超时 3 秒（为了快速触发降级），读超时 120 秒（容忍本地模型冷启动）。

### 导入链路（`knowledge.DocumentIngestService`）

同步执行的状态机：`PROCESSING` → 切分 → 逐 Chunk `embed` → Chroma `upsert` → MySQL `insert` → `READY`；任一步异常则 `FAILED` 并写入 `error_message`，**半成品不得进入检索**。

- **向量 ID = `docId#seq#indexVersion`**，同时也是 `kb_chunk` 表主键。重导同一文档靠 Chroma upsert 幂等，但 `kb_chunk` 是 `insert`，重复执行会撞主键——当前用「整文档重导」代替部分重试。
- `chunk_id` 的语义就是 `vector_id`（`KbChunkEntity.vectorId` 即是它），引用与检索都以此为准。
- 切分在 `ChunkSplitter`：Markdown 按标题分节并维护章节路径（`H1/H2`，分隔符 `/`），纯文本整篇一节；超长单行走字符滑窗 + overlap。`charStart/charEnd` 是**原文中的绝对码点偏移**，所有子串/取长操作必须用 `offsetByCodePoints`，不能按 `char` 或 `String.length()` 直接算。定位失败时 `charStart` 为 `-1`，属于弱化引用的兜底而非错误。

### 检索链路（`knowledge.KnowledgeRetrievalService`）

query 嵌入 → Chroma top-K（where 过滤 `user_id` / 可选 `doc_type`）→ 按 `similarityThreshold` 截断 → MySQL 回捞 Chunk 原文，**并二次过滤只保留 `status='READY'` 的文档**（`loadReadyChunks`，防止被删/失败文档经陈旧向量命中）→ 组装 `Citation`。

**降级是硬约束，不是可选优化**：`search()` 捕获向量路径的任何异常，改用 MySQL `LIKE` 关键词检索，返回值必须带 `searchMode=KEYWORD_FALLBACK` 和 `degraded=true`，且这个标记要一路透传到 Controller 响应。关键词检索有 `keywordMinHits` 闸门（默认 2），命中数太少不算证据，避免降级模式返回噪声。降级路径里的 `document_id` 子查询用 `sqlLiteral()` 手工转义拼 SQL——改这段时注意别引入注入。

### 问答（`knowledge.RagAskService`）

证据为空时**直接拒答，不调用 LLM**（有无降级两种不同话术）。有证据时：引用列表由服务端从命中的 Chunk 组装，模型只负责正文，prompt 里带编号的 `[n]` 证据块——目的是杜绝模型虚构文档名。

### 其他约定

- `user_id` 从 M-1 day1 起贯穿导入、Chunk、检索、删除全链路。当前接口无鉴权，`userId` 由请求体传入（`KnowledgeController` 注释已标注 M-2/M-4 要由服务端覆盖而非信任入参）。
- Chroma 0.6.x 的 REST 无法可靠地按名获取已有集合，409（集合已存在）时拿不到 id。因此 **`jobpilot.rag.chroma-collection-id` 必须固定配置**（`application-local.yml` 里的固定 UUID），否则重启后集合 id 变化会导致写不进/查不到。
- 统一响应为 `ApiResponse<T>`（`success/data/error`），失败走 `GlobalExceptionHandler`；可预期错误用 `ApiException(code, message)`，不要向外泄漏堆栈。
- 领域/接口注释是中文，保持这个风格。

## 架构约束（来自 `docs/ARCHITECTURE.md`，改动前先读）

- 不把参考项目 `paicli` / `PaiSmart` 加为 Maven、submodule 或源码依赖，也不复制其 `Agent` / `ToolRegistry` / `AgentOrchestrator`。
- 不引入 Elasticsearch 替代 Chroma；M-2 前不实现 SSE、WebSocket、JWT、完整前端。
- 不提前创建空 port/adapter 类；只在真正实现某能力时创建对应类型。
- 首版 Agent 是本项目内的小型同步 ReAct runner（默认最多 5 轮、工具调用上限 8 次/run、LLM 超时 30 秒重试 1 次），不提前抽取独立 `agent-kernel`。
- 会写入长期记忆或知识库的工具必须走 HITL：返回 `PENDING_APPROVAL` 草稿，审批幂等，模型不得有绕过审批的备用工具。

## 测试约定

JUnit 5 + Mockito + AssertJ（`spring-boot-starter-test`）。`DocumentIngestServiceTest` 用 `doAnswer` 模拟 MyBatis-Plus 的 `ASSIGN_UUID` 补主键——新增依赖主键生成的用例需保持这个 stub。构造 `RagProperties` 用全参构造器（11 个字段，参数顺序见 record 定义）。

## Git

不自动 commit / push。`docs/学习记录/` 与 `.workbuddy/` 是个人的本地笔记目录，已 gitignore，不要入库。
