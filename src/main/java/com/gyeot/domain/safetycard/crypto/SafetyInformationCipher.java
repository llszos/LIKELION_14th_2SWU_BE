package com.gyeot.domain.safetycard.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 안심카드 항목(title, information_value)을 AES-GCM으로 암호화·복호화합니다. (요구사항 명세 F-02 저장 보호)
 * 저장 형식: Base64( nonce 12바이트 + 암호문 + 인증 태그 16바이트 )
 * - 암호화할 때마다 새 nonce를 만들어, 같은 내용도 매번 다른 값으로 저장됩니다.
 * - 인증 태그로 DB 값이 바뀌었는지 확인하므로, 값이 조작되면 복호화에 실패합니다.
 * 예외 메시지에는 키나 원문을 넣지 않습니다. (ERD 8.6 민감정보 로그 금지)
 */
@Component // Spring이 객체로 만들어 EncryptedStringConverter에 넣어 줌
public class SafetyInformationCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding"; // 암호화 방식 (명세: AES-GCM)
    private static final int KEY_LENGTH = 32;      // AES-256
    private static final int NONCE_LENGTH = 12;    // GCM 권장 nonce 길이
    private static final int TAG_LENGTH_BITS = 128; // 인증 태그 16바이트

    private final SecretKey key;                                 // 설정값에서 읽은 AES 키
    private final SecureRandom secureRandom = new SecureRandom(); // nonce 생성용 (예측할 수 없는 난수)

    // 키는 설정값 safety-info.encryption-key (운영은 환경변수 SAFETY_INFO_ENCRYPTION_KEY)
    public SafetyInformationCipher(@Value("${safety-info.encryption-key}") String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES"); // 키 확인 후 AES 키 객체로 변환
    }

    // 평문 → 저장용 문자열 (Base64)
    public String encrypt(String plainText) {
        if (plainText == null) {
            return null; // title이 없는 항목(생년월일 등)은 NULL 그대로 저장
        }
        try {
            // 1. 이번 암호화에만 쓸 nonce를 새로 만듦 (같은 키로 nonce를 재사용하면 안전하지 않음)
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);

            // 2. 키와 nonce로 암호화
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)); // 암호문 + 태그

            // 3. 복호화할 때 nonce가 필요하므로 암호문 앞에 붙여서 Base64 문자열로 저장
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(NONCE_LENGTH + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            // 키·알고리즘 설정 문제 (원문은 메시지에 넣지 않음)
            throw new IllegalStateException("안심카드 정보를 암호화하지 못했습니다.", exception);
        }
    }

    // 저장용 문자열 → 평문. 앞 12바이트는 nonce, 나머지는 암호문 + 태그
    public String decrypt(String storedText) {
        if (storedText == null) {
            return null; // title이 없는 항목은 NULL 그대로 조회
        }
        try {
            byte[] stored = Base64.getDecoder().decode(storedText);
            if (stored.length < NONCE_LENGTH + TAG_LENGTH_BITS / 8) { // nonce + 태그보다 짧으면 암호화된 값이 아님
                throw new IllegalStateException("안심카드 정보의 저장 형식이 올바르지 않습니다.");
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, stored, 0, NONCE_LENGTH)); // 앞 12바이트를 nonce로 사용
            byte[] plain = cipher.doFinal(stored, NONCE_LENGTH, stored.length - NONCE_LENGTH);                    // 나머지를 복호화, 태그가 맞지 않으면 예외

            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            // 키가 다르거나, 값이 조작됐거나, 암호화 전 평문이 DB에 남아 있는 경우
            throw new IllegalStateException("안심카드 정보를 복호화하지 못했습니다.", exception);
        }
    }

    // 설정값(Base64)을 키 바이트로 바꾸고 길이를 확인. 잘못되면 서버 시작 단계에서 바로 실패시킴
    private static byte[] decodeKey(String base64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("안심카드 암호화 키(SAFETY_INFO_ENCRYPTION_KEY)가 Base64 형식이 아닙니다.");
        }
        if (keyBytes.length != KEY_LENGTH) {
            throw new IllegalStateException("안심카드 암호화 키(SAFETY_INFO_ENCRYPTION_KEY)는 Base64로 인코딩한 32바이트여야 합니다.");
        }
        return keyBytes;
    }
}
