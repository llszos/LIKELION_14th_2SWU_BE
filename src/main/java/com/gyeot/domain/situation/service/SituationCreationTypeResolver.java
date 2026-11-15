package com.gyeot.domain.situation.service;

import com.gyeot.domain.situation.exception.SituationCreationErrorCode;
import com.gyeot.global.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 종류 코드 검증·저장값 변환 경계. 허용 코드와 기타 문구 저장 모델 확정 전에는 지원 입력이 없습니다.
 * 이 경계가 완성될 때 기타 문구 영속 저장도 함께 연결해야 하며 값을 버려서는 안 됩니다.
 */
@Component
public class SituationCreationTypeResolver {
    public String resolve(String situationType, String customSituationType) {
        throw new BusinessException(SituationCreationErrorCode.SITUATION_TYPE_NOT_CONFIGURED);
    }
}
