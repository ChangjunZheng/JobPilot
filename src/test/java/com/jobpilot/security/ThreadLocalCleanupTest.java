package com.jobpilot.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ThreadLocalCleanupTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void businessExceptionStillClearsContextBeforeNextRequest() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/documents/not-found")
                        .header("Authorization", "Bearer " + jwtService.issue("tenant-a")))
                .andExpect(status().isNotFound());
        assertThat(UserContext.get()).isNull();

        mockMvc.perform(get("/api/v1/knowledge/documents/not-found")
                        .header("Authorization", "Bearer " + jwtService.issue("tenant-b")))
                .andExpect(status().isNotFound());
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void rejectedAuthenticationNeverLeavesAContext() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/documents/not-found"))
                .andExpect(status().isUnauthorized());
        assertThat(UserContext.get()).isNull();

        mockMvc.perform(get("/api/v1/knowledge/documents/not-found")
                        .header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized());
        assertThat(UserContext.get()).isNull();
    }
}
