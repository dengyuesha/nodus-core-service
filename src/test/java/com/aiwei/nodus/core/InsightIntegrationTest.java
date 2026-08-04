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

/** 验证周期洞察固定证据、复用、重生成、反馈和追问的完整闭环。 */
@SpringBootTest
@AutoConfigureMockMvc
class InsightIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void generatesTraceableHealthInsightAndReusesSameEvidence() throws Exception {
        String user = "insight-health-" + UUID.randomUUID();
        String measuredAt = Instant.now().minusSeconds(120).toString();
        perform(post("/api/v1/health/records/import"), user, """
                {"sourceSystem":"im-structured","records":[
                  {"sourceRecordId":"hr-1","metricType":"resting_heart_rate","value":58,
                   "unit":"bpm","measuredAt":"%s"},
                  {"sourceRecordId":"spo2-1","metricType":"spo2","value":98,
                   "unit":"%%","measuredAt":"%s"}
                ]}
                """.formatted(measuredAt, measuredAt)).andExpect(status().isOk());

        String request = "{\"domain\":\"HEALTH\",\"periodType\":\"MONTH\"}";
        MvcResult first = perform(post("/api/v1/insights/generate"), user, request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("HEALTH"))
                .andExpect(jsonPath("$.periodType").value("MONTH"))
                .andExpect(jsonPath("$.provider").value("DETERMINISTIC"))
                .andExpect(jsonPath("$.generationMode").value("FALLBACK"))
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.cautions.length()").value(2))
                .andReturn();
        String firstId = json(first).get("insightId").asText();
        MvcResult replay = perform(post("/api/v1/insights/generate"), user, request)
                .andExpect(status().isOk()).andReturn();
        assertThat(json(replay).get("insightId").asText()).isEqualTo(firstId);
        assertThat(jdbc.queryForObject("select count(*) from insight_report where user_id = ?",
                Integer.class, user)).isEqualTo(1);

        perform(post("/api/v1/insights/" + firstId + "/feedback"), user,
                "{\"rating\":\"HELPFUL\",\"comment\":\"证据清晰\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.rating").value("HELPFUL"));
        perform(post("/api/v1/insights/" + firstId + "/questions"), user,
                "{\"question\":\"这些结论用了多少条记录？\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("DETERMINISTIC"))
                .andExpect(jsonPath("$.answer").isNotEmpty());
        MvcResult regenerated = perform(post("/api/v1/insights/" + firstId + "/regenerate"), user, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supersedesInsightId").value(firstId))
                .andReturn();
        assertThat(json(regenerated).get("insightId").asText()).isNotEqualTo(firstId);
    }

    @Test
    void generatesFinanceInsightAndIsolatesUsers() throws Exception {
        String user = "insight-finance-" + UUID.randomUUID();
        String at = Instant.now().minusSeconds(60).toString();
        perform(post("/api/v1/finance/records/import"), user, """
                {"sourceSystem":"im-structured","records":[
                  {"sourceRecordId":"income-1","recordType":"INCOME","amount":5000,"currency":"CNY",
                   "category":"工资","occurredAt":"%s"},
                  {"sourceRecordId":"expense-1","recordType":"EXPENSE","amount":1200,"currency":"CNY",
                   "category":"餐饮","occurredAt":"%s"}
                ]}
                """.formatted(at, at)).andExpect(status().isOk());
        perform(post("/api/v1/insights/generate"), user,
                "{\"domain\":\"FINANCE\",\"periodType\":\"WEEK\",\"currency\":\"CNY\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("3800")))
                .andExpect(jsonPath("$.evidence.length()").value(2));
        perform(get("/api/v1/insights?domain=FINANCE"), user, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        perform(get("/api/v1/insights?domain=FINANCE"), "different-user", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsInsightWithoutEvidence() throws Exception {
        perform(post("/api/v1/insights/generate"), "empty-" + UUID.randomUUID(),
                "{\"domain\":\"HEALTH\",\"periodType\":\"QUARTER\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INSIGHT_NO_EVIDENCE"));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            MockHttpServletRequestBuilder builder, String userId, String body) throws Exception {
        builder.header("X-Nodus-Api-Key", "test-api-key")
                .header("X-Tenant-Id", "test")
                .header("X-User-Id", userId)
                .header("X-Device-Id", "test-device")
                .header("X-Session-Id", userId)
                .header("X-Source-Client", "insight-integration-test");
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(builder);
    }
}
