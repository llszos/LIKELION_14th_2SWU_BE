package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.response.SituationCreateResponse;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 상황과 OWNER만 원자적으로 저장하는 내부 컴포넌트입니다. 아직 HTTP에 연결하지 않습니다.
 * 호출자는 인증된 생성자 ID와 검증을 마친 저장 값을 전달해야 합니다.
 * 전체 Request를 받지 않으며 선택 참여자·기타 문구를 처리하거나 무시하지 않습니다.
 * 후속 생성 Service는 해당 정책과 저장 모델을 완성한 뒤 이 컴포넌트를 사용해야 합니다.
 */
@Component
@RequiredArgsConstructor
public class SituationCreateWriter {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SituationRepository situationRepository;
    private final SituationParticipantRepository participantRepository;
    private final Clock clock;

    /** 외부 Spring Bean에서 호출하여 상황·OWNER 저장에 동일한 트랜잭션을 적용합니다. */
    @Transactional
    public SituationCreateResponse save(Long authenticatedCreatorId, String situationType, String name,
                                        LocalDateTime startAt, LocalDateTime endAt) {
        Situation situation = situationRepository.save(
                Situation.create(authenticatedCreatorId, situationType, name, startAt, endAt));
        LocalDateTime joinedAt = LocalDateTime.now(clock.withZone(SEOUL)).truncatedTo(ChronoUnit.MICROS);
        participantRepository.save(SituationParticipant.create(
                situation, authenticatedCreatorId, SituationParticipantRole.OWNER, joinedAt));
        return new SituationCreateResponse(situation.getId(), situation.getCreatorId(), situation.getStatus());
    }
}
