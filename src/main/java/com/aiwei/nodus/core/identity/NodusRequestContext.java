package com.aiwei.nodus.core.identity;

public record NodusRequestContext(
        String tenantId,
        String userId,
        String householdId,
        String deviceId,
        String sessionId,
        String requestId,
        String sourceClient) {

    public static final String ATTRIBUTE_NAME = NodusRequestContext.class.getName();
}
