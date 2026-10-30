package com.gyeot.domain.relation.event;

import com.gyeot.domain.relation.entity.InvitationType;

/**
 * 초대 도착 이벤트 데이터입니다. 현재 방식에서는 발행하지 않습니다.
 *
 * 현재 초대는 대상 미지정 링크 공유 방식이므로,
 * 초대 생성 시점에는 수신자를 식별할 수 없습니다.
 * 따라서 현재는 이 이벤트를 발행하지 않습니다.
 *
 * TODO: 수신자 식별 방식과 발행 시점을 확정한 뒤 사용합니다.
 * inviteeId는 이벤트를 받을 사용자 ID를 의미하며,
 * Invitation의 응답 사용자 ID와의 의미 차이를 확인해야 합니다.
 */
public record InvitationReceivedEvent(
        Long invitationId,
        Long inviterId,
        Long inviteeId,
        InvitationType invitationType
) {
}
