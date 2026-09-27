package com.chris64233.ambulancedispatch.service;

import com.chris64233.ambulancedispatch.dto.DispatchRequest;
import com.chris64233.ambulancedispatch.dto.PreemptRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 计算请求内容指纹：相同业务号下，指纹相同视为重复提交，不同则为冲突。
 * 字段按固定顺序拼接，与 JSON 字段顺序无关。
 */
@Component
public class RequestFingerprint {

    public String dispatch(DispatchRequest request) {
        return sha256("DISPATCH|%s|%d|%d|%d".formatted(
                request.bizNo(), request.eventId(), request.ambulanceId(), request.crewId()));
    }

    public String preempt(PreemptRequest request) {
        return sha256("PREEMPT|%s|%d|%d|%d|%d".formatted(
                request.bizNo(), request.newEventId(), request.targetDispatchId(),
                request.ambulanceId(), request.crewId()));
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
