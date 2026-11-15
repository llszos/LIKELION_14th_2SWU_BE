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
 * 상황(기타 문구 포함)과 OWNER를 원자적으로 저장하는 내부 컴포넌트입니다.
 * 호출자는 인증된 생성자 ID와 검증을 마친 저장 값을 전달해야 합니다.
 * 선택 참여자는 상위 생성 Service의 동일 트랜잭션에서 저장합니다.
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
        return save(authenticatedCreatorId, situationType, name, startAt, endAt, null);
    }

    @Transactional
    public SituationCreateResponse save(Long authenticatedCreatorId, String situationType, String name,
                                        LocalDateTime startAt, LocalDateTime endAt, String customSituationType) {
        Situation situation = situationRepository.save(
                Situation.create(authenticatedCreatorId, situationType, name, startAt, endAt, customSituationType));
        LocalDateTime joinedAt = LocalDateTime.now(clock.withZone(SEOUL)).truncatedTo(ChronoUnit.MICROS);
        participantRepository.save(SituationParticipant.create(
                situation, authenticatedCreatorId, SituationParticipantRole.OWNER, joinedAt));
        return new SituationCreateResponse(situation.getId(), situation.getCreatorId(), situation.getStatus());
    }
}
