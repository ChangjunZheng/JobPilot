package com.jobpilot.knowledge;

import com.jobpilot.security.JwtService;
import com.jobpilot.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步导入的 API 契约（I-1c）：提交即 202 + documentId + status=PENDING，
 * 索引进度由 GET /documents/{id} 跟踪。worker 在测试中关闭（enabled=false），
 * 任务停留 PENDING 由断言确定；worker 自身的索引行为由单测与认领集成测试覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = "jobpilot.ingest.enabled=false")
@Transactional
class IngestAsyncIntegrationTest extends MySqlIntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @AfterEach
    void clearContext() {
        com.jobpilot.security.UserContext.clear();
    }

    private String bearer() {
        return "Bearer " + jwtService.issue("ingest-test-tenant");
    }

    @Test
    void ingestReturns202WithPendingTask() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/documents")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"简历.md\",\"content\":\"# 技能\\n\\nJava / Spring Boot\\n\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.documentId").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.data.chunkCount").value(0));
    }

    @Test
    void submittedTaskIsTrackableViaStatusEndpoint() throws Exception {
        String response = mockMvc.perform(post("/api/v1/knowledge/documents")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"JD.txt\",\"content\":\"岗位职责：Java 开发\\n\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String documentId = com.jayway.jsonpath.JsonPath.read(response, "$.data.documentId");

        mockMvc.perform(get("/api/v1/knowledge/documents/{id}", documentId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        assertThat(documentId).isNotBlank();
    }
}
