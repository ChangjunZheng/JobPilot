# JobPilot 开发路线图与进度

| 项 | 内容 |
|---|---|
| 最后更新 | 2026-10-01 |
| 当前迭代 | **I-1（未开始）** |
| 已完成 | I-0 |

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
| **I-1** | 账号 + 租户隔离 + 导入异步化 | ⬜ **未开始** | 新用户可注册并完成导入→问答；跨租户越权用例全通过 |
| **I-2** | Agent 最小闭环 | ⬜ 未开始 | 自研 ReAct runner + `knowledge_search` + JD 分析 + trace + HITL |
| **I-3** | 长期记忆与投递管理 | ⬜ 未开始 | Memory + 投递 CRUD + 用量计量，全部通过隔离用例 |
| **I-4** | 产品化外壳 | ⬜ 未开始 | 四个页面可用；10~20 个真实 JD 端到端演练通过 |
| **I-5** | 商业化与合规收口 | ⬜ 未开始 | 配额、订阅计费、数据导出自助化、SSE |
| **对外发布** | — | ⬜ **锁定中** | **仅在 I-1 完成后开放注册** |

> **I-1 是发布前置**：在租户隔离落地前不对外开放注册，也不接受真实用户的私密材料。理由见 [ARCHITECTURE §1.7](./ARCHITECTURE.md)。

---

## 2. 开工前（当前阻塞）

这几件事不做完，I-1 无法开始或无法验证。

- [ ] **提交文档改动** —— 工作区现有约 1277 行未提交（BRD/PRD/ARCHITECTURE/AGENTS + 一处 `ChromaVectorStoreAdapter` 注释）。先单独提交，让 I-1 的代码 diff 干净
- [ ] **启动 Redis** —— I-1 的硬依赖（jti 撤销 + 配额计数）。当前 :6379 未监听
- [ ] **补 `application-local.yml` 的 `spring.data.redis` 配置块** —— `application-local.yml.example` 里有、实际文件里没有。Redis 无密码且跑默认端口时能连上，一旦有密码就连不上
- [ ] **确认 I-0 数据库有无真实数据** —— 有则需决定租户键如何回填；只有测试数据则直接清库
- [ ] （可选）确认 Chroma（:8000）与 Ollama（:11434）是否需启动 —— I-1 编码与单测不依赖，但端到端验证需要

---

## 3. 待决策（会卡住 I-1 编码）

- [ ] **密码哈希算法**：bcrypt 还是 Argon2
- [ ] **JWT 参数**：HS256（对称、简单）还是 RS256（非对称、便于将来扩展）；access / refresh 的 TTL 取值
- [ ] **存量数据迁移策略**（取决于上面第 4 项确认结果）

> 其余待决项见 [PRD §12](./PRD.md)。这两条不定也能先写别的部分，但会在建 `Credential` 表时卡住。

---

## 4. I-1 · 账号 + 租户隔离 + 导入异步化

> 完整出口条件见 [PRD §10](./PRD.md) 与 [ARCHITECTURE §9](./ARCHITECTURE.md)。

### 4.1 I-1a · 租户隔离骨架（优先，单独就有价值）

**做完这一步，现有接口就安全了**——它单独消除了「`userId` 由请求体传入」这个安全缺口。

- [ ] `User` / `Credential` 表 —— **credential 用 `(provider, identifier)` 唯一键，不用 email 唯一键**（理由见 [ARCHITECTURE §14.2](./ARCHITECTURE.md)）
- [ ] `security` 包 + `UserContext`（ThreadLocal，`afterCompletion` 必须 `remove()`）
- [ ] `TenantLineInnerInterceptor` 注册到 `MybatisPlusInterceptor`，配 `PaginationInnerInterceptor`
- [ ] `KnowledgeController` 移除入参 `userId`，改为凭证解析
- [ ] 越权集成测试（六个业务对象 × 读/改/删/检索）
- [ ] `ThreadLocalCleanupTest` 形式的跨请求污染回归

### 4.2 I-1b · 账号接口

- [ ] 注册（含隐私政策与数据用途明示）
- [ ] 登录 / 登出
- [ ] JWT 签发与校验；**登出后原凭证立即失效**（Redis jti 黑名单）
- [ ] 未认证访问业务接口返回 401；跨租户返回 404/403 并写安全日志

### 4.3 I-1c · 导入异步化

- [ ] 导入改为「落 `PENDING` 即返回 `202` + `documentId`」，索引移出请求线程
- [ ] DB 队列 worker：`SELECT ... FOR UPDATE SKIP LOCKED` 认领
- [ ] 重试状态存行内（`retry_count` / `next_retry_at`）
- [ ] **启动时接管僵死任务**：超时仍为 `PROCESSING` 的行重置为 `PENDING`
- [ ] 每租户并发上限，防单租户批量导入饿死其他租户
- [ ] **不使用 `@Async`**，不引入消息队列（理由见 [ARCHITECTURE §4.1](./ARCHITECTURE.md)）

### 4.4 I-1 · 顺带清理

- [ ] 20 条评测集（JSONL，含失败归因分类；格式与归因口径见 [PRD §9.2](./PRD.md)）
- [ ] `ApiResponse` 补 `requestId` 字段
- [ ] Testcontainers 集成测试基类（顺带解决 `mvn test` 必须依赖本机 MySQL）
- [ ] 中文关键词 2 字窗口
- [ ] 向量路径候选池
- [ ] reindex / 孤儿向量清理

---

## 5. 已知缺陷（I-1 需一并修复）

| 缺陷 | 影响 | 位置 |
|---|---|---|
| `userId` 由请求体传入 | **安全缺陷**——客户端可任意指定身份 | `KnowledgeController` |
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
