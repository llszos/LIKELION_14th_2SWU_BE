package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.entity.SituationType;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import org.springframework.stereotype.Component;

/**
 * 승인된 상황 종류만 문자열 저장값으로 변환합니다. 임시 FE 코드·대소문자·공백을 자동 치환하지 않습니다.
 */
@Component
public class SituationCreationTypeResolver {
    public String resolve(String situationType, String customSituationType) {
        final SituationType type;
        try {
            type = SituationType.valueOf(situationType);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (!SituationType.isCustomTextValid(type.name(), customSituationType)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return type.name();
    }
}
