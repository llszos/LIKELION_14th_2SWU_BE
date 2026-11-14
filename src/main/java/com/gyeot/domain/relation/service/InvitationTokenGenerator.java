package com.gyeot.domain.relation.service;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 초대 링크에 사용할 일회성 난수 토큰을 만들고, DB 저장용 해시를 계산합니다. */
@Component
public class InvitationTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    public IssuedInvitationToken issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);

        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedInvitationToken(token, hash(token));
    }

    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("초대 토큰을 처리할 수 없습니다.", exception);
        }
    }

    public record IssuedInvitationToken(String token, String tokenHash) {
    }
}