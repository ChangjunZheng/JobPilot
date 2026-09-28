# JobPilot 架构设计

| 项 | 内容 |
|---|---|
| 文档版本 | v0.4 |
| 日期 | 2026-09-28 |
| 上游文档 | [BRD v0.5](./BRD-求职Copilot需求文档.md)、[PRD v0.4](./PRD.md) |
| 当前阶段 | M-1 RAG 最小闭环已完成；Spring AI Ollama 基础模型适配已接入；M-2 Agent 规划中 |
| 技术基线 | Java 21、Spring Boot 4.0.7、MyBatis-Plus 3.5.17、MySQL、Redis、Spring AI 2.0.1、Ollama、Chroma |

> 本文描述 JobPilot 的**当前实现与后续目标架构**。当前 M-1 已用 Spring AI 的 Ollama `ChatModel` / `EmbeddingModel` 加自定义 `RestClient` Chroma 适配完成最小 RAG 闭环；M-2 的自研同步 ReAct runner 尚未开始。`paicli` 与 `PaiSmart` 是参考案例，不是本项目的代码依赖。

## 1. 架构决策摘要

### 1.1 最终决策

1. **JobPilot 保持独立项目**，不与 `paicli` 或 `PaiSmart` 合并，不直接引用它们的 Maven artifact 或源代码。
2. 参考 `paicli` 的 ReAct loop、tool call 协议、预算控制和 trace 思路；参考 `PaiSmart` 的 Spring 业务工具注册、批量 embedding、检索降级和流式生成状态管理；参考 `JobClaw` 的 provider/channel/agent/plugin 解耦边界、参考 `PaiAgent` 的 Spring AI + 执行引擎分层、参考 `MemoArk` 的策略接口与用户隔离建模。
3. 当前不直接提取完整 `paicli.Agent`、`ToolRegistry` 或 `PaiSmart` 的业务服务。它们分别绑定 CLI 运行时和既有业务基础设施，直接复用会把不需要的复杂度带入 JobPilot。
4. JobPilot 首版 Agent 在本项目内实现一个小型同步 ReAct runner。至少完成一次真实闭环后，再依据实际重复代码决定是否提取独立 `agent-kernel`。
5. **AI 框架分层决策**：基础模型/Embedding/Tool Calling 协议由 Spring AI 承担（M-1 已落地）；Agent 的 ReAct 控制流、预算、HITL、trace 和领域工具执行由 JobPilot 自己控制。高层框架 Agent 不得接管这些产品策略。
6. 当前 M-1 已使用 Spring AI 2.0.1 的 Ollama `ChatModel` / `EmbeddingModel` 作为基础模型适配，业务层仍只依赖 JobPilot 自定义 Port；Chroma 保留自定义 `RestClient` 适配，以掌控 UUID、metadata、距离转换、启动自检和降级策略。`pom.xml` 不再保留 LangChain4j 依赖。
7. **不引入 LangChain4j 作为当前运行时依赖**。只有在 Spring AI 无法满足适配需求或未来出现明确的多 provider/复杂协议收益时，才重新评估；框架选择不能为了简历关键词而引入。
8. RAG 锁定 `Ollama bge-m3 + Chroma`：MySQL 保存可查询元数据、Chunk 原文和引用定位，Chroma 保存向量。
9. 同步 Agent 闭环优先于 SSE；JWT、WebSocket、多 Agent 编排和完整前端不作为 M-1/M-2 核心闭环的前置条件。

### 1.2 不采用的方案

| 方案 | 不采用原因 |
|---|---|
| 直接把两个项目合成一个大工程 | 边界、构建、配置和发布方式不同，且会引入无关能力 |
| JobPilot 直接依赖 `paicli` | `paicli` 是 Java 17 的 CLI 工程，Agent 强依赖 Renderer、Memory、Skill、LSP 和 CLI 工具安全策略 |
| 复制完整 `PaiSmart` RAG | 实际使用 Elasticsearch、组织权限和外部 embedding API，与 JobPilot 的 Chroma/Ollama/单用户约束不一致 |
| 一开始建设独立 `agent-kernel` 仓库 | 尚未验证 JobPilot 需要的最小公共能力，提前抽象会固化错误边界 |
| 一开始建设全量 `ai-port/adapter` 层级 | BRD 已明确收敛到 1~2 个 AI 门面，避免个人项目过度设计 |

### 1.3 求职项目时间优先级

本项目的直接目标是形成可在实习面试中演示、解释和追问的后端项目。时间紧张时按以下优先级执行：

#### 必须完成

1. M-1 RAG：导入、切分、Embedding、Chroma、MySQL 引用、向量检索、关键词降级；
2. 20 条固定评测集，记录 P@5、拒答正确性和至少一个失败案例；
3. M-2 最小 Agent：无工具回答、`knowledge_search`、一个 JD 分析工具；
4. ReAct 最大轮次/工具预算/超时/异常回填；
5. 一个 HITL 写入工具：`PENDING_APPROVAL`、审批状态和幂等执行；
6. 基础 trace：模型、轮次、工具顺序、耗时、状态；
7. README、架构决策记录和可复现启动命令。

#### 可以后置

- SSE、WebSocket、完整前端和 JWT；
- 多 Agent 计划/执行/审查；
- 多模型路由、复杂上下文摘要、完整长期记忆；
- RRF/rerank/BM25 混合检索、复杂权限和分布式向量库；
- 完整投递页面、移动端、自动投递、PDF OCR；
- 独立 `agent-kernel` 模块或公共仓库；只有出现第二个真实复用方时才重新评估。

> 判断标准：每个新增能力都要回答“能否增加可验证的面试素材，且不会阻塞 P0 闭环”。如果不能，进入 backlog。

### 1.4 AI 框架决策：Spring AI 与自研控制流

| 层次 | 决策 | 原因 |
|---|---|---|
| Chat/Embedding/Tool Calling 协议适配 | Spring AI 2.0.1 的 `ChatModel` / `EmbeddingModel` | 与 Spring Boot 生态衔接自然，减少供应商协议样板代码；业务层通过自定义 Port 隔离 |
| VectorStore / Chroma | JobPilot 自定义 `RestClient` 适配器 | Chroma UUID、过滤 metadata、距离换算、启动自检、降级和一致性是本项目的核心可讲点 |
| Agent ReAct 控制流 | JobPilot 自研 | 需要掌控轮次、预算、终止条件、工具异常和上下文边界 |
| HITL / trace / 领域工具 | JobPilot 自研 | 这是求职领域产品规则，通用框架不会替你定义 |
| LangChain4j | 当前不引入 | 当前代码没有实际使用；单 provider 场景下引入收益不足，不为简历关键词堆依赖 |

**重新评估条件**：

- 接入第二个真实 LLM/Embedding provider；
- 手写协议适配代码开始显著拖慢开发；
- Spring AI 的模型抽象、工具调用或流式能力能减少复杂度且不夺走 Agent 控制权；
- Spring AI 版本升级经过编译、上下文启动和集成测试验证；
- 有明确的框架使用场景和自动化测试，而不是为了技术栈名称。

### 1.5 参考项目借鉴矩阵

| 项目 | 借鉴点 | JobPilot 的取舍 |
|---|---|---|
| JobClaw | `provider / channel / agent / plugin` 可替换边界、模型供应商隔离 | 借鉴 provider 与 agent 边界；不引入 IM 渠道和多 Agent 复杂度 |
| PaiAgent | Spring AI 模型接入、执行引擎与节点执行分离、执行事件 | 借鉴 Spring AI 接入和事件思想；不引入 DAG + LangGraph 双引擎 |
| PaiFlow | 工作流、节点执行、状态和质量检查分离 | 借鉴执行边界；不引入 Python/Java 双引擎 |
| PaiSmart | 混合检索、批量 embedding、索引版本、引用校验、降级 | 先实现最小向量+关键词闭环，按评测结果增量演进 |
| PaiCLI | ReAct、工具协议、预算、工具结果边界、trace | 作为 M-2 自研 Agent runner 的主要控制流参考 |
| MemoArk | `RetrievalService`/`AiClient` 策略接口、用户数据隔离、可替换检索实现 | 借鉴接口隔离和 user_id 纵深防御；不复制完整产品壳 |

### 1.6 招聘目标与项目能力映射

| 求职方向 | 项目中必须出现的证据 |
|---|---|
| Java 后端开发 | Spring Boot、MySQL/SQL、REST API、MyBatis-Plus、状态机、异常处理、测试、Git、可复现启动 |
| AI 应用开发 | Spring AI、Ollama、Embedding、RAG、Chroma、引用溯源、降级检索、评测集、Prompt 组装 |
| Agent 开发 | ReAct runner、Tool Calling、工具注册、预算、超时、异常回填、HITL、幂等、trace |

**项目叙事**：不是“堆了很多 AI 框架”，而是“用 Spring AI 接入模型协议，自己控制求职领域 Agent 的执行策略，并通过 RAG 降级、引用和 HITL 解决可验证性与副作用问题”。



## 2. 参考项目评估

### 2.1 `paicli`

参考路径：

- `D:\Workspace\github_repos\paicli\src\main\java\com\paicli\agent\Agent.java`
- `D:\Workspace\github_repos\paicli\src\main\java\com\paicli\llm\LlmClient.java`
- `D:\Workspace\github_repos\paicli\src\main\java\com\paicli\tool\ToolRegistry.java`

新版 `Agent` 仍是成熟的 ReAct 实现：模型返回 tool calls，执行工具，将结果回填到对话历史，再继续下一轮；同时增加了 `ConversationLedger`、`AutoCompactionManager`、`TurnToolPolicy`、`ToolResultBoundary`、取消和工具暴露策略。

| 可借鉴点 | JobPilot 的使用方式 |
|---|---|
| ReAct 主循环 | LLM 返回 tool calls，执行工具，将结果回填，再进入下一轮 |
| `Message` / `ToolCall` / `Tool` 结构 | 转化为 JobPilot 的最小领域中立协议 |
| `AgentBudget` 思路 | 记录迭代、token、工具调用并限制资源消耗 |
| `ConversationLedger` | 后续参考对话事件的追加记录，不直接照搬 CLI ledger |
| `AutoCompactionManager` | 后续参考上下文压缩触发和统一协调方式 |
| `TurnToolPolicy` | 参考按本轮策略暴露工具，JobPilot 先采用固定领域工具白名单 |
| `ToolResultBoundary` | 参考限制工具结果进入模型上下文的边界和格式 |
| 工具 schema | 每个求职工具向模型声明名称、描述、参数 JSON schema |
| 工具结果卸载、命令沙箱与审计 | 仅借鉴“结果大小受控、危险操作可审计”的原则；不引入 CLI 命令工具 |
| 流式 listener 与取消 | M-4 接入 SSE 时参考事件拆分和取消语义，但不把终端 Renderer 带入后端 |

不可直接复用的部分：

- `Agent` 同时管理终端 renderer、skill、LSP、项目记忆、图片输入、CLI 取消上下文和会话 ledger；
- `ToolRegistry` 混合文件、Shell、浏览器、MCP、代码搜索、快照、命令沙箱和审计工具；
- 多 Agent `AgentOrchestrator` 是 CLI 的计划/执行/审查产品能力，不是 JobPilot P0；
- 其 Agent loop 的资源策略、上下文治理和工具安全策略都需要适配 HTTP/用户数据场景；
- Java 17、非 Spring Boot 的生命周期和配置方式不适合作为 JobPilot 基础。

**评估结论：** 新版 `paicli` 值得作为 Agent 控制流和运行时治理的参考，但其能力更完整也意味着更强的 CLI 耦合；当前不提取完整 Agent、ToolRegistry 或独立 `agent-kernel`。

### 2.2 `PaiSmart`

参考路径：

- `D:\Workspace\github_repos\PaiSmart\src\main\java\com\yizhaoqi\smartpai\service\AgentToolRegistry.java`
- `D:\Workspace\github_repos\PaiSmart\src\main\java\com\yizhaoqi\smartpai\service\HybridSearchService.java`
- `D:\Workspace\github_repos\PaiSmart\src\main\java\com\yizhaoqi\smartpai\service\VectorizationService.java`
- `D:\Workspace\github_repos\PaiSmart\src\main\java\com\yizhaoqi\smartpai\client\EmbeddingClient.java`
- `D:\Workspace\github_repos\PaiSmart\src\main\java\com\yizhaoqi\smartpai\service\ChatHandler.java`

新版 `PaiSmart` 的检索链路已发展为 BM25 + KNN 并行召回、RRF 融合、可选 rerank、分数阈值截断、权限过滤和 text-only fallback；向量化支持分页批处理、固定一次索引任务的 provider/model version 和 contextual chunk；聊天链路增加了 citation verification、父块上下文映射、取消和生成状态清理。

| 可借鉴点 | JobPilot 的使用方式 |
|---|---|
| 工具描述与 handler 分离 | JobPilot 的工具注册表只负责定义和分发，业务逻辑留在应用服务 |
| `userId` 进入工具执行 | 所有工具在入口校验 `user_id`，查询/写入均做隔离 |
| 结构化工具结果 | 同时提供模型可读摘要、状态、业务数据和 trace 信息 |
| Parent context 扩ansion | 检索命中小 Chunk，给模型补充父上下文，但引用仍指向实际命中的小 Chunk |
| BM25/KNN/RRF/rerank 分阶段 | 作为后续检索优化参考；M-1 不提前引入整套混合检索复杂度 |
| embedding 批处理 | 按批/分页处理文档，避免一次将全部 Chunk 放入内存 |
| 固定索引任务的模型版本 | 一次索引任务固定 embedding provider/model version，切换模型后通过新版本重建 |
| contextual chunk | 作为检索质量优化候选，先由评测集验证收益 |
| 向量检索失败后关键词降级 | Chroma 失败时进入明确标记的 MySQL 关键词检索 |
| citation verification | 回答落库/展示前校验引用确实由检索证据支持 |
| 异步生成状态、取消、流式事件 | M-4 SSE 设计参考；M-2 先使用同步响应 |

不可直接复用的部分：

- `HybridSearchService` 绑定 Elasticsearch KNN/BM25、RRF、rerank、组织标签和复杂权限模型；
- `EmbeddingClient` 绑定外部 OpenAI-compatible embedding、配额、计费和多 provider 体系；
- `VectorizationService` 绑定 ES 文档索引、Kafka/分页任务和既有 Chunk/文件实体；
- `ChatHandler` 绑定 WebSocket、JWT、Redis、线程池和具体 generation state；
- `AgentToolRegistry` 绑定 DeepSeek、反馈、文件上传、用户、组织和 Elasticsearch 业务；
- 这些实现的异常、状态和实体不能直接当作 JobPilot 的产品契约。

**评估结论：** 新版 `PaiSmart` 对 RAG 质量、索引一致性和引用可信度有较成熟的实践价值；JobPilot 采用其设计原则，但首版仍坚持 Chroma + Ollama 的最小闭环，不复制 Elasticsearch 业务代码。

## 3. JobPilot 总体架构

```text
┌──────────────────────────────────────────────┐
│ Interface Layer                              │
│ REST Controller / future SSE / future CLI    │
└──────────────────────┬───────────────────────┘
                       │ request/response DTO
┌──────────────────────▼───────────────────────┐
│ Application Layer                            │
│ KnowledgeService / ConversationService       │
│ InterviewService / ApplicationService        │
│ ApprovalService                               │
└──────────────┬─────────────────┬─────────────┘
               │                 │
┌──────────────▼─────────┐ ┌─────▼────────────────┐
│ AI Facade               │ │ Persistence/Domain   │
│ KnowledgeRetrieval /    │ │ Document / Chunk     │
│ RagAskService           │ │ Conversation/Memory │
│ AgentRunner             │ │ Application/Trace    │
└──────────────┬──────────┘ └─────┬────────────────┘
               │                  │
┌──────────────▼──────────────────▼──────────────┐
│ Ports / infrastructure adapters                │
│ Spring AI · Ollama · Chroma · MySQL · Redis    │
└─────────────────────────────────────────────────┘
```

### 3.1 包职责

| 包/边界 | 职责 | 不负责 |
|---|---|---|
| `controller` | HTTP 输入校验、身份上下文、DTO 转换 | Agent loop、数据库细节 |
| `service` / `application` | 编排用例、事务边界、用户可见业务结果 | Spring AI 类型、供应商 SDK 类型和底层 HTTP |
| `domain` | 业务对象、状态、规则和结果 | Spring/外部 SDK |
| `ai` | JobPilot 自定义端口、AI record、Agent runner、工具协议 | Controller 细节、数据库表实现 |
| `ai.port` | Chat/Embedding/VectorStore/Trace 的最小端口 | 具体供应商配置 |
| `ai.adapter` | Spring AI、Ollama、Chroma 等适配 | 求职领域决策 |
| `knowledge` | 文档导入、抽取、切分、索引和引用 | 通用 Agent 编排 |
| `mapper` / `persistence` | MySQL 元数据和 Chunk 持久化 | prompt 组装、模型调用 |
| `config` | Spring Bean、外部服务和 profile 配置 | 业务流程 |
| `common` | 响应、异常、时间和用户上下文等共享基础设施 | 领域特有逻辑 |

当前只在真正开始实现某个能力时创建对应包和类型，不提前填充空接口。

## 4. 核心数据流

### 4.1 文档导入与索引（M-1）

```text
Upload/Text Input
  → DocumentIngestService
  → ChunkSplitter (.md 按标题分节 / .txt 整篇，超长滑窗 + overlap)
  → Chunk metadata + original text → MySQL
  → EmbeddingPort → OllamaEmbeddingAdapter → Spring AI → Ollama bge-m3
  → VectorStorePort → ChromaVectorStoreAdapter → Chroma
  → Document status READY
```

> PDF 抽取尚未实现（BRD §13.3 列为待评估），当前只支持 Markdown 与纯文本。

约束：

- 先写 `PENDING/PROCESSING` 状态，只有 MySQL 元数据、Chunk 和 Chroma 向量都成功后才标记 `READY`；
- 任一阶段失败标记 `FAILED`，记录可重试原因，不把半成品参与检索；
- Chunk 具有稳定的 `chunk_id` 和索引版本，重建索引时通过版本或幂等 key 避免重复向量；
- 原始文件只存本地数据目录，路径不直接暴露给用户；
- `user_id` 贯穿导入、Chunk、查询和删除流程。

M-1 只实现 Chroma 向量检索的最小闭环。新参考项目中的以下能力暂列为后续优化：

- `ParentContextExpander`：命中小 Chunk，给模型补充父上下文，引用仍绑定命中的小 Chunk，以兼顾回答上下文和引用精度；
- `BM25 + KNN → RRF → rerank → 阈值截断`、contextual chunk 和 citation verification：必须先通过 20 条评测集验证收益，不作为最小闭环的前置依赖；
- 批处理和固定 embedding 模型版本：一次索引任务固定模型版本，模型切换通过新版本重建，不在同一文档中混用向量。

### 4.2 检索与引用问答（M-1）

```text
query + user_id
  → KnowledgeRetrievalService
  → EmbeddingPort → query embedding (Ollama bge-m3)
  → VectorStorePort → Chroma top-K (where 过滤 user_id/doc_type)
  → 相似度阈值截断 → MySQL 回捞 Chunk 原文（只保留 READY 文档）
  → RagAskService → ChatPort（bounded context，证据编号 [n]）
  → answer + Citation[] + searchMode/degraded
```

如果 Chroma 不可用，RAG pipeline 通过同一检索结果协议调用 MySQL 关键词降级；返回中必须标记 `searchMode=KEYWORD_FALLBACK`。无足够相关结果时直接返回证据不足，不强行调用模型生成确定性答案。

### 4.3 Agent 工具调用（M-2，规划）

```text
user message
  → AgentRunner
  → ChatModel.chat(messages, toolDefinitions)
  → no tool calls? return final answer
  → tool calls? ToolRegistry.execute(context, calls)
  → append ToolResult messages
  → repeat until final answer or budget exhausted
```

AgentRunner 只负责循环和预算，不知道 `Document`、`Application` 或 `Memory` 的表结构。具体工具由 JobPilot 应用服务提供，例如 `knowledge_search`、`job_description_analyze` 和 `application_query`。

### 4.4 HITL 副作用

```text
Agent tool requests write side effect
  → validate and create ApprovalDraft
  → return PENDING_APPROVAL ToolResult
  → user approves/rejects via application API
  → idempotent execute or discard
```

模型不得通过绕过审批的备用工具直接写入长期记忆或简历要点。审批请求带唯一 `approval_id`，重复确认不得重复产生副作用。

## 5. 最小 AI 协议

以下是架构边界，不要求本阶段立即创建所有 Java 类型。实际实现时优先使用 `record` 表达不可变请求/结果。

### 5.1 Chat model

> M-1 只实现 `ChatPort.complete(String systemPrompt, String userPrompt) → String`（非流式、无工具、无 usage）。下面的 shape 是 M-2 引入工具调用时的目标协议，当前代码里还没有对应类型。

```text
ChatRequest
- messages: List<ChatMessage>
- tools: List<ToolDefinition>
- modelOptions: ModelOptions
- timeout: Duration

ChatResponse
- content: String
- toolCalls: List<ToolCall>
- finishReason: FinishReason
- usage: TokenUsage?
- provider: String
- model: String
```

业务层不得接触 Spring AI、供应商 SDK 的 response 或 HTTP JSON；`ai.adapter` 下的适配器负责将其转换为 JobPilot 自己的 record（`ChatPort`/`EmbeddingPort`/`VectorStorePort` 及其入参出参）。M-1 已落地：`OllamaChatAdapter`、`OllamaEmbeddingAdapter` 走 Spring AI 的 `ChatModel`/`EmbeddingModel`，`ChromaVectorStoreAdapter` 保留自定义 `RestClient`。

### 5.2 Tool protocol

> M-2 目标协议，M-1 尚未实现任何工具类型。

```text
ToolDefinition
- name: String
- description: String
- inputSchema: JsonSchema

ToolCall
- id: String
- name: String
- arguments: JsonObject

ToolExecutionContext
- userId: String
- conversationId: String?
- traceId: String
- approvalMode: ApprovalMode

ToolExecutionResult
- callId: String
- name: String
- status: SUCCESS | FAILED | PENDING_APPROVAL
- modelText: String
- data: Object?
- citations: List<Citation>
- errorCode: String?
```

工具执行上下文必须由应用层创建，不能相信模型传入的 `userId`。工具定义的参数 schema 只负责模型输入约束，服务端仍需做类型、权限和业务规则校验。

### 5.3 Retrieval protocol

> M-1 已实现，record 定义见 `com.jobpilot.ai`。

```text
RetrievalQuery
- userId: String
- text: String
- topK: int          // <= 0 时回落 jobpilot.rag.top-k
- docType: String?   // 可选，作为向量库 metadata 过滤条件

RetrievalResult
- items: List<RetrievedChunk>
- searchMode: VECTOR | KEYWORD_FALLBACK
- degraded: boolean

RetrievedChunk
- documentId: String
- chunkId: String    // 即向量 ID，格式 docId#seq#indexVersion
- text: String       // 来自 MySQL，事实来源
- score: double      // 余弦相似度；降级模式为关键词命中数
- Citation citation
```

`Citation` 是用户可见契约，字段为 `documentId`、文档展示名 `documentName`、章节路径 `sectionPath`、`chunkId` 和 `charStart`/`charEnd`（原文绝对码点偏移，定位失败为 `-1`）。

### 5.4 Embedding protocol

> M-1 已实现（极简形态），见 `EmbeddingPort`。

```text
EmbeddingPort.embed(String text) → List<Double>
EmbeddingPort.dimension() → int
```

M-1 采用逐条调用的极简形态（见 `EmbeddingPort` javadoc）；批量接口、modelVersion 和 usage 回传等，只有评测集证明收益后才加。知识库写入和查询必须使用同一 embedding 模型版本。

## 6. Agent Runner 规则

首版实现小型同步 ReAct runner，参考 `paicli.Agent` 的控制流，但只保留 JobPilot 需要的行为。

| 项 | 首版规则 |
|---|---|
| 最大迭代 | 默认 5 轮，可配置 |
| 工具调用上限 | 默认 8 次/次运行；可按工具或业务再收紧 |
| LLM 超时 | 30 秒；超时重试 1 次，仍失败则结束运行 |
| 工具超时 | 按工具配置；异常转换为结构化失败结果回填模型 |
| 终止条件 | 模型不再请求工具、预算耗尽、取消或不可恢复错误 |
| 工具结果 | 产生模型可读摘要，同时保留结构化数据供应用层使用 |
| 副作用 | 写入类工具返回 `PENDING_APPROVAL` 或按 PRD 规则执行 |
| trace | 每轮记录模型、工具、耗时、状态和可用 token 信息 |
| 上下文 | M-2 先不做复杂摘要；超长时明确截断并记录事件 |

工具调用失败不应导致 Java 异常直接穿透 Controller；应转换成错误结果，让模型在预算内决定重试或给出说明。对于服务不可用类错误，应用层同时记录用户可见错误和 trace。

## 7. RAG 数据与一致性边界

### 7.1 MySQL 保存

- `Document`：名称、类型、原始文件元信息、状态、`user_id`、索引版本、时间；
- `Chunk`：`document_id`、`chunk_id`、原文、顺序、章节路径、引用定位、`user_id`、索引版本；
- `Conversation/Message`：对话、工具调用和引用关联；
- `Memory`：经确认的短小结构化条目；
- `Application`：投递记录；
- `TraceLog`：Agent 调用链路；
- `ApprovalDraft`：待确认副作用。

### 7.2 Chroma 保存

每个 VectorEntry 至少保存：

- 稳定的向量 ID（实现为 `docId#seq#indexVersion`，同时是 `kb_chunk` 表主键）；
- embedding；
- `user_id`、`document_id`、`chunk_id`、索引版本等 metadata。

向量内容不是唯一事实来源，引用原文和业务状态以 MySQL 为准。删除/重建必须能按文档和索引版本定位向量。

### 7.3 一致性策略

M-1 不引入分布式事务。使用可重试状态机和补偿操作：

1. 创建文档并标记 `PROCESSING`；
2. 生成 Chunk 并保存元数据；
3. 写入 Chroma；
4. 全部成功后标记 `READY`；
5. 失败标记 `FAILED`，保留错误和重试次数；
6. 删除时先禁止检索，再清理 Chroma/MySQL，失败留下可观测的不一致状态。

`READY` 是可检索的唯一状态；任何 `PENDING`、`PROCESSING`、`FAILED` 和 `DELETED` 文档都不应进入正常检索结果。

## 8. 外部依赖、降级与可观测性

| 依赖 | 正常用途 | 失败处理 | Trace 事件 |
|---|---|---|---|
| Ollama | 生成文档/查询 embedding | 新索引失败；提示嵌入服务离线；允许重试 | `EMBEDDING_UNAVAILABLE` |
| Chroma | 向量写入和检索 | 检索降级为关键词；写入任务失败并可重试 | `VECTOR_STORE_DEGRADED` |
| LLM API | 对话、JD 分析和生成 | 30 秒超时重试一次；友好错误 | `LLM_TIMEOUT` / `LLM_FAILED` |
| MySQL | 元数据和业务事实；Flyway 在此建表 | **启动即要求可连接**（MyBatis-Plus 的 mapper 扫描依赖 `SqlSessionFactory`，缺 DataSource 直接启动失败）；运行期写入失败不确认成功 | `PERSISTENCE_UNAVAILABLE` |
| Redis | 后续缓存/短状态 | 首版不作为核心闭环前置依赖 | `CACHE_UNAVAILABLE`（如启用） |

每个 Agent run 至少记录：`trace_id`、`user_id`、`conversation_id`、模型、开始/结束时间、迭代、工具顺序、耗时、错误和 token usage（供应商提供时）。输入/输出原文的脱敏规则和保留周期在实现前确定，API key 不进入日志。

**Spring Boot 4 的配置约束**：Boot 4 把自动配置类拆到独立 artifact 与包名（`spring-boot-jdbc` / `spring-boot-flyway` / `spring-boot-data-redis`，包名形如 `org.springframework.boot.<tech>.autoconfigure.*`），`spring-boot-autoconfigure` 只剩 core。两个后果：引某个基础设施必须引对应 starter（裸库依赖不会带来自动配置），以及在 `spring.autoconfigure.exclude` 写 Boot 3 时代的旧 FQCN 会被**静默忽略**——只有 conditions report 里的 `Exclusions: None` 能暴露这个问题。

## 9. 实施顺序

```text
M-1: RAG Pipeline
  DocumentIngestService → ChunkSplitter → EmbeddingPort → Chroma VectorStore → Citation

M-2: Agent 半区
  AgentRunner → ToolRegistry → knowledge_search / JD analysis / Application CRUD
  + trace → HITL → Memory
  + LLM 配置

M-4: 产品壳
  同步 Controller 稳定后 → SSE → 对话页/知识库页/投递体验/简历要点 HITL
```

### M-1 当前状态与最小验收

**当前状态：最小 RAG API 闭环已实现。** 已有能力包括：

- Markdown/plain text 导入、按章节/长度切分；
- Ollama `bge-m3` embedding；
- Chroma 0.6.x 向量写入与查询；
- MySQL Chunk 原文和 READY 状态回捞；
- 引用定位；
- Chroma 不可用时关键词降级与 `keywordMinHits` 闸门；
- Chroma 集合启动自检与向量维度校验；
- 相关单元测试和 Spring 上下文测试。

仍需补强：20 条评测集、中文关键词 2 字窗口、向量路径候选池、reindex/孤儿向量清理。

### M-2 最小验收

- Agent 可在无工具调用时直接回答；
- Agent 可调用 `knowledge_search`，将结果回填并继续一轮；
- 工具异常、超时和迭代上限可控；
- 至少一个 HITL 工具能生成待审批草稿且重复审批幂等；
- trace 能还原一次完整调用链；
- 一个投递 CRUD 工具可按 `user_id` 工作；
- Spring AI（已引入）只位于协议适配边界，业务层不依赖其类型。

## 10. 明确不做与后续决策

本阶段明确不做：

- 不把 `paicli`/`PaiSmart` 加为 Maven、Git submodule 或源码依赖；
- 不复制完整 `paicli.Agent`、`ToolRegistry`、`AgentOrchestrator`；
- 不实现多 Agent 计划/执行/审查架构；
- 不把 Spring AI 或其他框架的高层 Agent 当作 JobPilot 的控制流；
- 不把 LangChain4j 作为当前运行时依赖；
- 不实现 SSE、WebSocket、JWT、完整前端和业务 CRUD 作为 M-1/M-2 核心闭环前置；
- 不引入 Elasticsearch 替代 Chroma；
- 不创建独立 `agent-kernel` 项目；
- 不预先创建全部空 port/adapter 类。

> 这里的“自研”指自研 JobPilot 的领域边界与控制流，不指重新实现 Spring Boot、HTTP 客户端、MySQL、向量数据库或大模型。

### `agent-kernel` 再评估条件

满足以下条件后再考虑提取：

1. JobPilot 的同步 Agent loop 已有真实工具和自动化测试；
2. 新版 `paicli` 中的 ReAct、预算、tool result boundary 和取消协议，与 JobPilot 的实现出现至少两处稳定且不依赖 CLI 的相同控制流；
3. 公共部分可以不依赖 CLI renderer、Skill、LSP、图片输入、ConversationLedger、TurnToolPolicy、文件/Shell 工具和具体业务；
4. 公共协议的版本、测试和发布责任有明确归属；
5. 提取后不会增加 JobPilot 的启动、调试和发布复杂度。

在此之前，JobPilot 内部小型 runner 是更低风险的选择。

## 11. 文档版本记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v0.1 | 2026-09-26 | M-0 架构设计与参考项目评估 |
| v0.2 | 2026-09-28 | 根据 M-1 实际代码更新；移除 LangChain4j 既定依赖口径；增加 Spring AI 候选定位、自研 Agent 控制流和时间优先级边界 |
| v0.3 | 2026-09-28 | 引入 Spring AI 1.0.0 Ollama Chat/Embedding 适配；补充 JobClaw/PaiAgent/PaiFlow/MemoArk 借鉴矩阵与招聘能力映射 |
| v0.4 | 2026-09-28 | 技术基线升到 Spring Boot 4.0.7 / Spring AI 2.0.1 / MyBatis-Plus 3.5.17；Boot 4 拆分自动配置 artifact 导致裸 `flyway-core` 不再触发迁移，改用 `spring-boot-starter-flyway`；移除 `application.yml` 的自动配置排除列表（`@MapperScan` 硬依赖 DataSource，该机制无法实现「无基础设施可启动」）；MySQL 在 §8 标注为启动前置 |

## 12. 与 BRD/PRD 的对应关系
| 要求 | 本文落点 |
|---|---|
| FP-1 RAG Pipeline | §4.1、§4.2、§7、§9 |
| FP-2 Agent 与工具 | §3、§5、§6、§9 |
| FP-3 trace/HITL | §4.4、§6、§8 |
| FP-4 长期记忆 | §7.1、§9 |
| FP-5 LLM 配置 | §5.1、§6 |
| NFR-1 延迟 | §6、§8、M-1/M-2 验收 |
| NFR-3 降级 | §4.2、§8 |
| NFR-5 容量 | 采用单机 MySQL/Chroma，不预留分布式架构 |
| NFR-6 可观测性 | §6、§8 |
| `user_id` day1 | §4.1、§5.2、§7 |
| 同步优先、SSE 后置 | §1、§4、§9 |
