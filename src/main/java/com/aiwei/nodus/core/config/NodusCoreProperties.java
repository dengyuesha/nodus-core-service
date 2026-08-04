package com.aiwei.nodus.core.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nodus.core")
public record NodusCoreProperties(
        String apiKey,
        Duration idempotencyTtl,
        Duration reminderScanInterval,
        Duration deliveryRetryDelay,
        Duration deliveryLease,
        boolean insightAinasEnabled,
        String insightAinasBaseUrl,
        String insightAinasApiKey,
        Duration insightAinasTimeout) {

    public NodusCoreProperties {
        apiKey = apiKey == null ? "" : apiKey;
        idempotencyTtl = idempotencyTtl == null ? Duration.ofHours(24) : idempotencyTtl;
        reminderScanInterval = reminderScanInterval == null ? Duration.ofSeconds(1) : reminderScanInterval;
        deliveryRetryDelay = deliveryRetryDelay == null ? Duration.ofSeconds(15) : deliveryRetryDelay;
        deliveryLease = deliveryLease == null ? Duration.ofSeconds(30) : deliveryLease;
        insightAinasBaseUrl = insightAinasBaseUrl == null ? "http://127.0.0.1:8083" : insightAinasBaseUrl;
        insightAinasApiKey = insightAinasApiKey == null ? "" : insightAinasApiKey;
        insightAinasTimeout = insightAinasTimeout == null ? Duration.ofSeconds(90) : insightAinasTimeout;
    }
}
