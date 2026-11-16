package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HelpRecordFilter;
import com.gyeot.domain.situation.dto.response.HelpRecordPageResponse;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 본인 기록의 노출 범위·상태·기록상 담당자·정렬은 C Reader 계약에 위임합니다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HelpRecordQueryService {
    private final CareRequestSummaryReader reader;

    public HelpRecordPageResponse list(Long userId, String type, Long personId, int page, int size) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        if (page < 0 || size < 1 || (personId != null && personId <= 0)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        HelpRecordFilter filter = parseFilter(type);
        // C가 startsAt DESC, requestId DESC 정렬을 고정하므로 B는 엔티티 sort를 만들지 않습니다.
        return HelpRecordPageResponse.from(
                reader.getHelpRecords(userId, filter, personId, PageRequest.of(page, size)));
    }

    private HelpRecordFilter parseFilter(String type) {
        if (type == null) return HelpRecordFilter.ALL;
        try {
            return HelpRecordFilter.valueOf(type);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }
}
