package com.aiwei.nodus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aiwei.nodus.core.reminder.ReminderScheduler;

@SpringBootTest
@AutoConfigureMockMvc
class MemoReminderIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ReminderScheduler scheduler;

    @Test
    void persistsMemoAndReplaysCreate() throws Exception {
        String body = "{\"text\":\"购买牛奶\",\"rawText\":\"帮我记一下购买牛奶\"}";
        MvcResult first = perform(post("/api/v1/memos"), "memo-user", "memo-key", body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN")).andReturn();
        MvcResult replay = perform(post("/api/v1/memos"), "memo-user", "memo-key", body)
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        perform(get("/api/v1/memos"), "memo-user", null, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value("购买牛奶"));
    }

    @Test
    void schedulesClaimsAndAcknowledgesReminder() throws Exception {
        String reminderBody = objectMapper.writeValueAsString(java.util.Map.of(
                "text", "喝水", "kind", "reminder", "timezone", "Asia/Shanghai",
                "dueAt", Instant.now().minusSeconds(1).toString()));
        MvcResult created = perform(post("/api/v1/reminders"), "reminder-user", "reminder-key", reminderBody)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED")).andReturn();
        UUID reminderId = UUID.fromString(json(created).get("reminderId").asText());

        scheduler.enqueueDueReminders();
        MvcResult claimed = perform(post("/api/v1/reminder-deliveries/claim"),
                "reminder-user", null, "{\"limit\":10}")
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value("喝水"))
                .andExpect(jsonPath("$[0].deliveryAttempt").value(1)).andReturn();
        String eventId = json(claimed).get(0).get("eventId").asText();

        perform(post("/api/v1/reminder-deliveries/" + eventId + "/ack"),
                "reminder-user", null, "{\"source\":\"ainas-test\"}")
                .andExpect(status().isOk());
        String status = jdbcTemplate.queryForObject("select status from reminder where id = ?", String.class, reminderId);
        assertThat(status).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void tenantWorkerClaimsReminderForOriginalUser() throws Exception {
        String originalUser = "tenant-reminder-user-" + UUID.randomUUID();
        String reminderBody = objectMapper.writeValueAsString(java.util.Map.of(
                "text", "跨用户租户投递", "kind", "reminder", "timezone", "Asia/Shanghai",
                "dueAt", Instant.now().minusSeconds(1).toString()));
        MvcResult created = perform(post("/api/v1/reminders"), originalUser,
                "tenant-reminder-key-" + UUID.randomUUID(), reminderBody)
                .andExpect(status().isOk()).andReturn();
        UUID reminderId = UUID.fromString(json(created).get("reminderId").asText());

        scheduler.enqueueDueReminders();
        MvcResult claimed = perform(post("/api/v1/reminder-deliveries/claim-tenant"),
                "ainas-delivery-worker", null, "{\"limit\":10}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value("test"))
                .andExpect(jsonPath("$[0].userId").value(originalUser))
                .andExpect(jsonPath("$[0].text").value("跨用户租户投递"))
                .andReturn();
        String eventId = json(claimed).get(0).get("eventId").asText();

        perform(post("/api/v1/reminder-deliveries/" + eventId + "/ack"),
                originalUser, null, "{\"source\":\"ainas-tenant-worker-test\"}")
                .andExpect(status().isOk());
        String status = jdbcTemplate.queryForObject(
                "select status from reminder where id = ?", String.class, reminderId);
        assertThat(status).isEqualTo("ACKNOWLEDGED");
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            MockHttpServletRequestBuilder builder, String userId, String idempotencyKey, String body) throws Exception {
        builder.header("X-Nodus-Api-Key", "test-api-key")
                .header("X-Tenant-Id", "test")
                .header("X-User-Id", userId)
                .header("X-Device-Id", "test-device")
                .header("X-Session-Id", userId)
                .header("X-Source-Client", "integration-test");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(builder);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
