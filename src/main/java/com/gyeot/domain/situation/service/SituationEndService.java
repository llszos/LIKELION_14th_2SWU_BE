package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.response.SituationEndResponse;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.repository.SituationEndRepository;
import com.gyeot.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.gyeot.domain.situation.exception.SituationErrorCode.*;

@Service
@RequiredArgsConstructor
public class SituationEndService {
    private final SituationEndRepository repository;
    private final SituationEndProcessor processor;

    @Transactional
    public SituationEndResponse end(Long situationId, Long userId) {
        Situation situation = repository.findByIdForUpdate(situationId)
                .orElseThrow(() -> new BusinessException(SITUATION_NOT_FOUND));
        if (!situation.getCreatorId().equals(userId)) {
            if (!repository.hasActiveParticipation(situationId, userId)) {
                throw new BusinessException(SITUATION_NOT_FOUND);
            }
            throw new BusinessException(SITUATION_FORBIDDEN);
        }
        if (!processor.endIfActive(situation)) {
            throw new BusinessException(SITUATION_ALREADY_ENDED);
        }
        return SituationEndResponse.from(situation);
    }
}
