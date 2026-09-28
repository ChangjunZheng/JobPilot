# JobPilot

JobPilot 是面向求职流程的 Copilot。当前仓库包含 Spring Boot 后端骨架，业务能力按 BRD 中的 FP-1 → FP-2 → FP-3/4 顺序逐步实现。

## 技术栈

- Java 21
- Spring Boot 4.0
- Maven
- MyBatis-Plus + MySQL
- Redis
- Spring AI 2.0（仅 AI 适配边界）+ Ollama + Chroma

## 启动

需要先准备好 MySQL 并复制 `application-local.yml`（见下节）。没有数据库时应用无法启动：MyBatis-Plus 的 mapper 扫描依赖 `SqlSessionFactory`。

```bash
mvn spring-boot:run
```

或运行测试：

```bash
mvn test
```

如果本机默认 Java 版本不是 21，可使用项目根目录的 `run-java21.cmd`，它只为当前 Maven 进程临时指定 Java 21，不修改系统环境变量：

```cmd
run-java21.cmd test
run-java21.cmd spring-boot:run
```

脚本默认使用 `D:\\develop\\Java\\jdk-21`。如果本机安装路径不同，修改脚本中的 `JAVA_HOME` 即可。

## API

- `GET /api/v1/health`：业务 API 健康检查
- `GET /actuator/health`：Spring Boot 健康检查

## 本地基础设施配置

需要连接 MySQL/Redis 时，将 `src/main/resources/application-local.yml.example` 复制为 `application-local.yml`，再通过环境变量填写连接信息。`application-local.yml` 不应提交到仓库。

`application.yml` 默认 profile 为 `local`，数据库与 Redis 连接信息全部来自 `application-local.yml`，没有该文件时应用启动会失败。

## 目录约定

```text
src/main/java/com/jobpilot/
├── ai/          # Spring AI 门面与 AI 适配
├── common/      # 通用响应、异常和基础设施
├── config/      # Spring 配置
├── controller/  # HTTP API
├── domain/      # 领域模型
├── mapper/      # MyBatis-Plus Mapper
└── service/     # 应用服务
```

业务表、RAG Pipeline、Agent kernel、Chroma、Ollama、SSE 和 JWT 将在 PRD/架构设计完成后按里程碑逐步加入。
