package com.gyeot.domain.safetycard.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 엔티티 필드를 저장할 때 암호화하고, 조회할 때 복호화합니다. (JPA AttributeConverter)
 * 필드에 @Convert(converter = EncryptedStringConverter.class)를 붙인 곳에만 적용됩니다.
 * Hibernate가 Spring을 통해 이 객체를 만들기 때문에 SafetyInformationCipher를 생성자로 주입받을 수 있습니다.
 * 서비스 코드와 API 요청·응답은 평문 그대로 다루고, DB에만 암호문이 저장됩니다.
 */
@Converter // JPA Converter라는 표시. autoApply가 없어서 @Convert를 붙인 필드에만 적용
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SafetyInformationCipher cipher; // 실제 암호화·복호화를 하는 객체

    // Hibernate가 Spring에게 이 객체를 만들어 달라고 하면, Spring이 cipher를 넣어 줌
    public EncryptedStringConverter(SafetyInformationCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute); // 저장: 평문 → 암호문
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData); // 조회: 암호문 → 평문
    }
}
