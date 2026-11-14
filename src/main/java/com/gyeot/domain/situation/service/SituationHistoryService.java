package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.situation.dto.response.SituationHistoryDetailResponse;
import com.gyeot.domain.situation.dto.response.SituationHistoryListResponse;
import com.gyeot.domain.situation.repository.SituationHistoryQueryRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static com.gyeot.domain.situation.exception.SituationHistoryErrorCode.SITUATION_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SituationHistoryService {
    private final SituationHistoryQueryRepository repository;
    private final ObjectProvider<CareRequestSummaryReader> requestReader;

    /** 정렬·최대 크기는 #63 잠정 기준입니다. */
    public SituationHistoryListResponse list(Long userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id")));
        return SituationHistoryListResponse.from(repository.findHistory(userId, pageable));
    }

    /** 내부 연동 준비용. C 구현·정책 확인 전 상세 Controller는 추가하지 않습니다. */
    public SituationHistoryDetailResponse detail(Long situationId, Long userId) {
        var situation = repository.findAccessibleHistory(situationId, userId)
                .orElseThrow(() -> new BusinessException(SITUATION_NOT_FOUND));
        CareRequestSummaryReader reader = requestReader.getIfAvailable();
        if (reader == null) {
            throw new IllegalStateException("지난 요청 기록 조회 구현이 준비되지 않았습니다.");
        }
        var requests = Objects.requireNonNull(reader.getPastRequestRecords(situationId, userId),
                "지난 요청 기록 조회 결과는 null일 수 없습니다.");
        return SituationHistoryDetailResponse.from(situation, requests);
    }
}
