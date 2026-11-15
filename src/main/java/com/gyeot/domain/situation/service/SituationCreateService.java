package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.request.SituationCreateRequest;
import com.gyeot.domain.situation.dto.response.SituationCreateResponse;
import com.gyeot.domain.situation.entity.SituationParticipant;
import com.gyeot.domain.situation.entity.SituationParticipantRole;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 공통 저장 흐름. 실제 종류 Resolver가 미완성이므로 현재 운영 경로에서는 저장되지 않습니다. */
@Service @RequiredArgsConstructor
public class SituationCreateService {
    private final Validator validator;
    private final SituationCreationTypeResolver types;
    private final SituationCreateParticipantValidator participantValidator;
    private final SituationCreateWriter writer;
    private final SituationRepository situations;
    private final SituationParticipantRepository participants;
    private final Clock clock;

    @Transactional
    public SituationCreateResponse create(Long authenticatedCreatorId, SituationCreateRequest request) {
        if (authenticatedCreatorId == null || request == null || !validator.validate(request).isEmpty())
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        // 호출 중 외부 변경을 받지 않도록 ID 목록을 복사합니다. null 요소는 전용 Validator가 처리합니다.
        List<Long> ids = new java.util.ArrayList<>(request.participantIds());
        String storedType = types.resolve(request.situationType(), request.customSituationType());
        participantValidator.validate(authenticatedCreatorId, ids);
        var response = writer.save(authenticatedCreatorId, storedType, request.name(), request.startAt(), request.endAt());
        var situation = situations.getReferenceById(response.situationId());
        var joinedAt = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))).truncatedTo(ChronoUnit.MICROS);
        for (Long id : ids) {
            participants.save(SituationParticipant.create(situation, id, SituationParticipantRole.PARTICIPANT, joinedAt));
        }
        return response;
    }
}
