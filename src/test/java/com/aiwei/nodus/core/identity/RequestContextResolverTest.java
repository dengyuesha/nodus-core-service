package com.aiwei.nodus.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.aiwei.nodus.core.config.NodusCoreProperties;

class RequestContextResolverTest {

    @Test
    void pinsStructuredImportsToConfiguredLocalUser() {
        RequestContextResolver resolver = new RequestContextResolver(properties("iq9075-local"));
        MockHttpServletRequest request = requestWithUser("iq9075-wdb");

        NodusRequestContext context = resolver.resolveStructuredDataImport(request);

        assertThat(context.userId()).isEqualTo("iq9075-local");
        assertThat(context.tenantId()).isEqualTo("default");
        assertThat(context.sourceClient()).isEqualTo("im");
    }

    @Test
    void preservesCallerUserWhenNoDefaultIsConfigured() {
        RequestContextResolver resolver = new RequestContextResolver(properties(""));
        MockHttpServletRequest request = requestWithUser("another-user");

        assertThat(resolver.resolveStructuredDataImport(request).userId()).isEqualTo("another-user");
    }

    private MockHttpServletRequest requestWithUser(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(NodusRequestContext.ATTRIBUTE_NAME,
                new NodusRequestContext("default", userId, null, "device", "session", "request", "im"));
        return request;
    }

    private NodusCoreProperties properties(String structuredDataUserId) {
        return new NodusCoreProperties("", null, null, null, null, structuredDataUserId,
                false, null, null, null);
    }
}
