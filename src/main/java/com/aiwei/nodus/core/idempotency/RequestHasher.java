package com.aiwei.nodus.core.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.aiwei.nodus.core.api.DomainException;

@Component
public class RequestHasher {

    private final ObjectMapper objectMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(Object value) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "REQUEST_HASH_FAILED", "无法生成请求摘要", true, null);
        }
    }
}
