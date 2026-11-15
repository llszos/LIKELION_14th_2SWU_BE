package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.dto.response.SituationListResponse;
import com.gyeot.domain.situation.repository.SituationRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SituationQueryService {
    private final SituationRepository repository;

    public SituationListResponse listActive(Long userId, int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        var pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return SituationListResponse.from(repository.findActiveForParticipant(userId, pageable));
    }
}
