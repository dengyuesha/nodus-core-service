package com.aiwei.nodus.core.identity;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.aiwei.nodus.core.api.DomainException;
import com.aiwei.nodus.core.config.NodusCoreProperties;

@Component
public class RequestContextResolver {

    private final NodusCoreProperties properties;

    public RequestContextResolver(NodusCoreProperties properties) {
        this.properties = properties;
    }

    public NodusRequestContext resolve(HttpServletRequest request) {
        Object value = request.getAttribute(NodusRequestContext.ATTRIBUTE_NAME);
        if (value instanceof NodusRequestContext context) {
            return context;
        }
        throw new DomainException(HttpStatus.BAD_REQUEST, "REQUEST_CONTEXT_MISSING", "请求身份上下文缺失");
    }

    /** 健康和财务导入可统一归属到设备的本地默认用户。 */
    public NodusRequestContext resolveStructuredDataImport(HttpServletRequest request) {
        NodusRequestContext context = resolve(request);
        String configuredUserId = properties.structuredDataUserId();
        if (configuredUserId.isBlank() || configuredUserId.equals(context.userId())) {
            return context;
        }
        return new NodusRequestContext(context.tenantId(), configuredUserId, context.householdId(),
                context.deviceId(), context.sessionId(), context.requestId(), context.sourceClient());
    }
}
