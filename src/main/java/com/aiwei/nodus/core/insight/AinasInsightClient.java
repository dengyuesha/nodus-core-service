package com.aiwei.nodus.core.insight;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.aiwei.nodus.core.config.NodusCoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 调用 AINAS 的受鉴权、无状态洞察生成入口。 */
@Component
public class AinasInsightClient {
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(5);
    private final NodusCoreProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final AtomicLong retryAfterEpochMillis = new AtomicLong();

    public AinasInsightClient(NodusCoreProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(properties.insightAinasTimeout()).build();
    }

    public boolean enabled() {
        return properties.insightAinasEnabled();
    }

    public InsightGenerationResult generate(InsightGenerationCommand command) throws Exception {
        if (!enabled()) throw new IllegalStateException("AINAS insight generation is disabled");
        if (properties.insightAinasApiKey().isBlank()) throw new IllegalStateException("AINAS API key is missing");
        long now = System.currentTimeMillis();
        if (retryAfterEpochMillis.get() > now) {
            throw new IllegalStateException("AINAS insight circuit is cooling down");
        }
        try {
            String base = properties.insightAinasBaseUrl().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/gateway/insights/generate"))
                    .timeout(properties.insightAinasTimeout())
                    .header("Authorization", "Bearer " + properties.insightAinasApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(command)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("AINAS insight endpoint returned HTTP " + response.statusCode());
            }
            InsightGenerationResult result = mapper.readValue(response.body(), InsightGenerationResult.class);
            if (result.summary() == null || result.summary().isBlank()) {
                throw new IllegalStateException("AINAS insight response has no summary");
            }
            retryAfterEpochMillis.set(0L);
            return result;
        } catch (Exception error) {
            // 本地模型故障时打开短期熔断，避免页面连续操作堆积多个长时间推理请求。
            retryAfterEpochMillis.set(System.currentTimeMillis() + FAILURE_COOLDOWN.toMillis());
            throw error;
        }
    }
}
