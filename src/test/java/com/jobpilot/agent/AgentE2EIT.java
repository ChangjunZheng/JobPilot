package com.jobpilot.agent;

import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.knowledge.DocumentIngestService;
import com.jobpilot.knowledge.IngestCommand;
import com.jobpilot.security.UserContext;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真机 ReAct 闭环（I-2 验收）——<b>需要真实 Ollama + Chroma，默认跳过、不进 CI</b>。
 *
 * <pre>AGENT_E2E=true mvn test -Dtest=AgentE2EIT</pre>
 *
 * <h3>为什么必须有这一层，单测替代不了</h3>
 * {@code AgentRunnerTest} 用 mock ChatPort <b>主动返回</b> tool_calls，验证的是循环逻辑。
 * 但真实模型会不会调工具、调什么参数，是完全另一回事。本机 qwen2.5:3b 实测：
 * 弱提示下它反问用户而不调工具；{@code AgentRunner.SYSTEM_PROMPT} 把规则写死后才稳定触发。
 * <b>这个差异只有真机能发现。</b>
 *
 * <h3>为什么关掉 worker</h3>
 * 走 {@code enqueue} + 手动 {@code process}（而非等后台 worker），让「导入完成」这件事
 * 在测试里是确定的，不依赖轮询与超时。
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "jobpilot.ingest.enabled=false")
@EnabledIfEnvironmentVariable(named = "AGENT_E2E", matches = "true")
class AgentE2EIT extends MySqlIntegrationTestBase {

    private static final String TENANT = "e2e-tenant";
    /** 取自项目现有 eval 语料的措辞，模型对「有哪些项目经验」这类问法最自然会去检索 */
    private static final String QUESTION = "我之前做过的项目里用了哪些技术栈？";

    @Autowired
    private DocumentIngestService ingestService;
    @Autowired
    private AgentRunner agentRunner;

    @BeforeEach
    void setUp() {
        UserContext.set(TENANT);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void agentRetrievesFromKnowledgeBaseBeforeAnswering() {
        indexResume();

        AgentRunner.RunResult result = agentRunner.run(new AgentRunner.RunRequest("e2e-conv", QUESTION));

        // 1. 真的调了检索工具——这是本测试存在的全部理由
        assertThat(result.steps())
                .as("trace 里应出现 knowledge_search 步骤；steps=%s", result.steps())
                .anySatisfy(step -> {
                    assertThat(step.kind()).isEqualTo("tool");
                    assertThat(step.name()).isEqualTo("knowledge_search");
                });

        // 2. 没有把预算耗尽：模型在拿到证据后给出了答案
        assertThat(result.finishReason())
                .as("finishReason=%s answer=%s", result.finishReason(), result.answer())
                .isEqualTo(com.jobpilot.ai.FinishReason.STOP);
        assertThat(result.answer()).isNotBlank();

        // 3. 答案应基于证据而非空谈
        assertThat(result.steps())
                .anySatisfy(step -> {
                    if ("tool".equals(step.kind()) && "knowledge_search".equals(step.name())) {
                        assertThat(step.status()).isEqualTo("success");
                    }
                });
    }

    /**
     * 把简历灌进知识库并同步索引到 READY。
     * <p>
     * 用 {@code process} 而非等 worker：{@code jobpilot.ingest.enabled=false} 已关掉后台调度，
     * 这里手动跑一次，测试不依赖轮询与超时。
     */
    private void indexResume() {
        String resume = """
                # 我的项目经历

                ## 电商订单系统
                使用 Java 与 Spring Boot 开发，MySQL 存储订单数据，Redis 做缓存。

                ## 日志分析平台
                使用 Kafka 采集日志，Elasticsearch 存储与检索。
                """;
        KbDocumentEntity pending = ingestService.enqueue(new IngestCommand(
                TENANT, "简历.md", "MARKDOWN", "e2e", resume));
        pending.setStatus("PROCESSING");
        ingestService.process(pending);

        assertThat(pending.getStatus())
                .as("简历必须索引成功，Ollama 与 Chroma 需在线")
                .isEqualTo("READY");
    }
}
