package com.gyeot.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 개인정보 제공 동의의 정책 버전과 동의 시각을 기록합니다. */
@Entity
@Table(
        name = "user_consents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_consents_user_type_version",
                columnNames = {"user_id", "consent_type", "policy_version"}
        ),
        indexes = @Index(name = "idx_user_consents_user_id", columnList = "user_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다른 도메인 객체와의 연관관계 대신 사용자 ID만 저장합니다.
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    private UserConsentType consentType;

    @Column(name = "policy_version", nullable = false, length = 30)
    private String policyVersion;

    @Column(name = "consented_at", nullable = false)
    private LocalDateTime consentedAt;

    private UserConsent(Long userId, UserConsentType consentType,
                        String policyVersion, LocalDateTime consentedAt) {
        this.userId = userId;
        this.consentType = consentType;
        this.policyVersion = policyVersion;
        this.consentedAt = consentedAt;
    }

    public static UserConsent record(Long userId, UserConsentType consentType,
                                     String policyVersion, LocalDateTime consentedAt) {
        if (userId == null || consentType == null || policyVersion == null
                || policyVersion.isBlank() || consentedAt == null) {
            throw new IllegalArgumentException("동의 기록에 필요한 값은 필수입니다.");
        }
        return new UserConsent(userId, consentType, policyVersion, consentedAt);
    }
}
