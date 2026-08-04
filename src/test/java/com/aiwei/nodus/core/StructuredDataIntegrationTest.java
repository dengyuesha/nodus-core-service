package com.aiwei.nodus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 验证 IM 只提交结构化字段时，健康和财务可以逐条幂等写入并被设备查询。 */
@SpringBootTest
@AutoConfigureMockMvc
class StructuredDataIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void importsHealthRecordsWithReplayConflictAndSummary() throws Exception {
        String user = "health-user-" + UUID.randomUUID();
        String at = Instant.now().minusSeconds(60).toString();
        String body = """
                {"sourceSystem":"im-structured","records":[
                  {"sourceRecordId":"health-1","metricType":"resting_heart_rate","value":58,
                   "unit":"bpm","measuredAt":"%s","metadata":{"documentType":"health-report"}},
                  {"sourceRecordId":"bad-health","metricType":"spo2","unit":"%%","measuredAt":"%s"}
                ]}
                """.formatted(at, at);
        perform(post("/api/v1/health/records/import"), user, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.results[1].errorCode").value("HEALTH_RECORD_INVALID"));
        perform(post("/api/v1/health/records/import"), user, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(1));
        String conflict = body.replace("\"value\":58", "\"value\":59");
        perform(post("/api/v1/health/records/import"), user, conflict)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].errorCode").value("SOURCE_RECORD_CONFLICT"));
        perform(get("/api/v1/health/summary"), user, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.resting_heart_rate.latest").value(58))
                .andExpect(jsonPath("$.metrics.resting_heart_rate.unit").value("bpm"));
        assertThat(jdbc.queryForObject("select count(*) from health_record where user_id = ?",
                Integer.class, user)).isEqualTo(1);
    }

    @Test
    void importsFinanceAndUsesLatestBalancePerAccount() throws Exception {
        String user = "finance-user-" + UUID.randomUUID();
        Instant now = Instant.now();
        String body = """
                {"sourceSystem":"im-structured","records":[
                  {"sourceRecordId":"income-1","recordType":"INCOME","amount":10000,"currency":"CNY",
                   "category":"工资","occurredAt":"%s"},
                  {"sourceRecordId":"expense-1","recordType":"EXPENSE","amount":2500,"currency":"CNY",
                   "category":"餐饮","occurredAt":"%s"},
                  {"sourceRecordId":"asset-old","recordType":"ASSET_BALANCE","amount":80000,"currency":"CNY",
                   "account":"银行卡","occurredAt":"%s"},
                  {"sourceRecordId":"asset-new","recordType":"ASSET_BALANCE","amount":90000,"currency":"CNY",
                   "account":"银行卡","occurredAt":"%s"},
                  {"sourceRecordId":"liability-1","recordType":"LIABILITY_BALANCE","amount":20000,"currency":"CNY",
                   "account":"信用卡","occurredAt":"%s"}
                ]}
                """.formatted(now.minusSeconds(300), now.minusSeconds(200), now.minusSeconds(400),
                        now.minusSeconds(100), now.minusSeconds(50));
        perform(post("/api/v1/finance/records/import"), user, body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(5));
        String normalizedReplay = body.replace("\"INCOME\"", "\"income\"")
                .replace("\"CNY\"", "\"cny\"");
        perform(post("/api/v1/finance/records/import"), user, normalizedReplay)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(5));
        perform(get("/api/v1/finance/summary"), user, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income").value(10000))
                .andExpect(jsonPath("$.expense").value(2500))
                .andExpect(jsonPath("$.savingsRate").value(75.0))
                .andExpect(jsonPath("$.assets").value(90000))
                .andExpect(jsonPath("$.liabilities").value(20000))
                .andExpect(jsonPath("$.netWorth").value(70000))
                .andExpect(jsonPath("$.expenseByCategory.餐饮").value(2500));
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            MockHttpServletRequestBuilder builder, String userId, String body) throws Exception {
        builder.header("X-Nodus-Api-Key", "test-api-key")
                .header("X-Tenant-Id", "test")
                .header("X-User-Id", userId)
                .header("X-Device-Id", "test-device")
                .header("X-Session-Id", userId)
                .header("X-Source-Client", "structured-data-integration-test");
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(builder);
    }
}
