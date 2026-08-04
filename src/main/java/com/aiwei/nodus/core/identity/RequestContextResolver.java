package com.aiwei.nodus.core.identity;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.aiwei.nodus.core.api.DomainException;

@Component
public class RequestContextResolver {

    public NodusRequestContext resolve(HttpServletRequest request) {
        Object value = request.getAttribute(NodusRequestContext.ATTRIBUTE_NAME);
        if (value instanceof NodusRequestContext context) {
            return context;
        }
        throw new DomainException(HttpStatus.BAD_REQUEST, "REQUEST_CONTEXT_MISSING", "请求身份上下文缺失");
    }
}
