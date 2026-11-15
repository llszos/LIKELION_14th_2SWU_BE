package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.response.SituationParticipantsResponse;
import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.exception.SituationRemovalErrorCode;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팀 최종 확인이 필요한 임시 정책: ACTIVE 조회자만 허용(OWNER 포함), ENDED 허용,
 * ACTIVE 대상만 OWNER 포함 반환, joinedAt/PK 오름차순, 페이징·A 표시정보 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SituationParticipantsQueryService {
    private final SituationParticipantReader reader;
    private final SituationParticipantRepository participants;

    public SituationParticipantsResponse list(Long situationId, Long viewerId) {
        // 기존 Reader는 상황 미존재도 false로 반환하며 상황 상태를 제한하지 않습니다.
        if (!reader.isActiveParticipant(situationId, viewerId))
            throw new BusinessException(SituationRemovalErrorCode.SITUATION_NOT_FOUND);
        return SituationParticipantsResponse.from(participants.findInfoBySituationAndStatus(
                situationId, SituationParticipantStatus.ACTIVE));
    }
}
