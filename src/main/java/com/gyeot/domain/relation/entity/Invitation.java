package com.gyeot.domain.relation.entity;

import com.gyeot.global.common.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 나의 사람 초대와 보호자 요청을 저장합니다. (ERD 4.2 invitations) */
@Entity
@Table(name = "invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation extends BaseCreatedEntity {

    // 초대 ID.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 초대 발급자 ID (users.id).
    @Column(name = "inviter_id", nullable = false)
    private Long inviterId;

    // 초대에 응답한 사용자 ID (users.id). 응답 전에는 NULL.
    @Column(name = "invitee_id")
    private Long inviteeId;

    // 나의 사람 초대 또는 보호자 요청.
    @Enumerated(EnumType.STRING)
    @Column(name = "invitation_type", nullable = false, length = 20)
    private InvitationType invitationType;

    // 초대 링크 토큰의 해시값. 원문 토큰은 저장하지 않음.
    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    // 보호자 요청에 함께 보낼 짧은 편지.
    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    // 대기·수락·거절·만료·취소 상태.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvitationStatus status;

    // 초대 링크 만료 시각.
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // 수락 또는 거절 시각.
    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    private Invitation(
            Long inviterId,
            InvitationType invitationType,
            String tokenHash,
            String message,
            LocalDateTime expiresAt
    ) {
        this.inviterId = inviterId;
        this.invitationType = invitationType;
        this.tokenHash = tokenHash;
        this.message = message;
        this.expiresAt = expiresAt;
        this.status = InvitationStatus.PENDING;
    }

    // 새 초대를 생성.
    public static Invitation create(
            Long inviterId,
            InvitationType invitationType,
            String tokenHash,
            String message,
            LocalDateTime expiresAt
    ) {
        if (inviterId == null || invitationType == null || tokenHash == null || tokenHash.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("초대 발급자, 유형, 토큰 해시, 만료 시각은 필수입니다.");
        }
        if (invitationType == InvitationType.MY_PERSON && message != null) {
            throw new IllegalArgumentException("나의 사람 초대에는 편지를 저장하지 않습니다.");
        }

        return new Invitation(inviterId, invitationType, tokenHash, message, expiresAt);
    }

    // 초대를 수락하거나 거절함.
    public void respond(Long inviteeId, InvitationStatus response, LocalDateTime respondedAt) {
        if (this.status != InvitationStatus.PENDING) {
            throw new IllegalStateException("대기 중인 초대만 응답할 수 있습니다.");
        }
        if (inviteeId == null || respondedAt == null) {
            throw new IllegalArgumentException("응답 사용자와 응답 시각은 필수입니다.");
        }
        if (response != InvitationStatus.ACCEPTED && response != InvitationStatus.REJECTED) {
            throw new IllegalArgumentException("초대 응답은 수락 또는 거절만 가능합니다.");
        }

        this.inviteeId = inviteeId;
        this.status = response;
        this.respondedAt = respondedAt;
    }

    // 대기 중인 초대를 취소합니다.
    public void cancel() {
        if (this.status != InvitationStatus.PENDING) {
            throw new IllegalStateException("대기 중인 초대만 취소할 수 있습니다.");
        }
        this.status = InvitationStatus.CANCELED;
    }

    // 대기 중인 초대를 만료 처리합니다.
    public void expire() {
        if (this.status != InvitationStatus.PENDING) {
            throw new IllegalStateException("대기 중인 초대만 만료 처리할 수 있습니다.");
        }
        this.status = InvitationStatus.EXPIRED;
    }
}
