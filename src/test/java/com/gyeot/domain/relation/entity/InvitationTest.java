package com.gyeot.domain.relation.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvitationTest {

    @Test
    void 만료_시각과_같거나_지난_초대는_응답할_수_없다() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 11, 16, 12, 0);
        Invitation invitation = Invitation.create(
                1L,
                InvitationType.MY_PERSON,
                "token-hash",
                null,
                expiresAt
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> invitation.respond(2L, InvitationStatus.ACCEPTED, expiresAt)
        );

        assertEquals("만료된 초대는 응답할 수 없습니다.", exception.getMessage());
        assertEquals(InvitationStatus.PENDING, invitation.getStatus());
    }
}