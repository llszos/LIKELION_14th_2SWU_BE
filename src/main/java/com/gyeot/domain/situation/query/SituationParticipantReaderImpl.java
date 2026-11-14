package com.gyeot.domain.situation.query;

import com.gyeot.domain.situation.entity.SituationParticipantStatus;
import com.gyeot.domain.situation.repository.SituationParticipantRepository;
import com.gyeot.domain.situation.repository.SituationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** C의 요청 자격 판단에 필요한 사실만 조회하고 업무 검증은 수행하지 않습니다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SituationParticipantReaderImpl implements SituationParticipantReader {

    private final SituationRepository situationRepository;
    private final SituationParticipantRepository participantRepository;

    @Override
    public boolean isActiveParticipant(Long situationId, Long userId) {
        Objects.requireNonNull(situationId, "situationId는 필수입니다.");
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        return participantRepository.existsBySituation_IdAndUserIdAndStatus(
                situationId, userId, SituationParticipantStatus.ACTIVE);
    }

    @Override
    public Optional<SituationSummary> findSituation(Long situationId) {
        Objects.requireNonNull(situationId, "situationId는 필수입니다.");
        return situationRepository.findById(situationId)
                .map(situation -> new SituationSummary(situation.getCreatorId(),
                        situation.getStartDate(), situation.getEndDate(), situation.getStatus().name()));
    }
}
