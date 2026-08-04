package com.aiwei.nodus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceRegistrationIntegrationTest {

    private static final String API_KEY = "test-api-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registersDeviceAndReplaysSameIdempotentResponse() throws Exception {
        String body = """
                {"deviceId":"device-001","householdId":"home-001","displayName":"客厅设备"}
                """;

        MvcResult first = register("tenant-001", "user-001", "register-key-001", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("device-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        MvcResult replay = register("tenant-001", "user-001", "register-key-001", body)
                .andExpect(status().isOk())
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(count("device_registration", "tenant-001", "user-001")).isEqualTo(1);
        assertThat(count("outbox_event", "tenant-001", "user-001")).isEqualTo(1);
        assertThat(count("audit_record", "tenant-001", "user-001")).isEqualTo(1);
    }

    @Test
    void rejectsReusingIdempotencyKeyForDifferentRequest() throws Exception {
        register("tenant-002", "user-002", "register-key-002",
                "{\"deviceId\":\"device-002\",\"displayName\":\"卧室设备\"}")
                .andExpect(status().isOk());

        register("tenant-002", "user-002", "register-key-002",
                "{\"deviceId\":\"device-003\",\"displayName\":\"厨房设备\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void protectsBusinessApiAndValidatesIdentityContext() throws Exception {
        mockMvc.perform(get("/api/v1/system/context")
                        .header("X-Tenant-Id", "tenant-003")
                        .header("X-User-Id", "user-003"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/system/context")
                        .header("X-Nodus-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_CONTEXT"));
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private org.springframework.test.web.servlet.ResultActions register(
            String tenantId,
            String userId,
            String idempotencyKey,
            String body) throws Exception {
        return mockMvc.perform(post("/api/v1/devices/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Nodus-Api-Key", API_KEY)
                .header("X-Tenant-Id", tenantId)
                .header("X-User-Id", userId)
                .header("X-Device-Id", extractDeviceId(body))
                .header("X-Source-Client", "integration-test")
                .header("Idempotency-Key", idempotencyKey)
                .content(body));
    }

    private String extractDeviceId(String body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("deviceId").asText();
    }

    private int count(String table, String tenantId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where tenant_id = ? and user_id = ?",
                Integer.class, tenantId, userId);
        return count == null ? 0 : count;
    }
}
