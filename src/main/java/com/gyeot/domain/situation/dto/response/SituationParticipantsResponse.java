package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.dto.internal.SituationParticipantInfo;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 임시 기본 응답 계약. A 표시정보·이력 노출·페이징은 팀 확인 후 별도 반영합니다. */
public record SituationParticipantsResponse(List<Item> items) {
    public static SituationParticipantsResponse from(List<SituationParticipantInfo> participants) {
        return new SituationParticipantsResponse(participants.stream().map(p -> new Item(
                p.participantId(), p.userId(), p.role(), p.status(), p.joinedAt(), p.leftAt())).toList());
    }

    public record Item(Long participantId, Long userId, SituationParticipantRole role,
                       SituationParticipantStatus status, LocalDateTime joinedAt, LocalDateTime leftAt) {}
}
