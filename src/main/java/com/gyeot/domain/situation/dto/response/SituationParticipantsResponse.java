package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.dto.internal.SituationParticipantInfo;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 현재 참여자와 A의 표시 이름을 반환합니다. */
public record SituationParticipantsResponse(List<Item> items) {
    public static SituationParticipantsResponse from(List<SituationParticipantInfo> participants, Map<Long, UserProfile> profiles) {
        return new SituationParticipantsResponse(participants.stream().map(p -> new Item(
                p.participantId(), p.userId(), displayName(profiles.get(p.userId())), p.role(), p.status(), p.joinedAt(), p.leftAt())).toList());
    }

    private static String displayName(UserProfile profile) {
        if (profile == null) return null;
        return profile.withdrawn() ? "탈퇴한 사용자" : profile.name();
    }

    public record Item(Long participantId, Long userId, String displayName, SituationParticipantRole role,
                       SituationParticipantStatus status, LocalDateTime joinedAt, LocalDateTime leftAt) {}
}
