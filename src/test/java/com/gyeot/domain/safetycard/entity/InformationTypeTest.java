package com.gyeot.domain.safetycard.entity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.gyeot.domain.safetycard.entity.InformationType.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 안심카드 항목 유형이 요구사항 명세 F-02와 같은지 확인합니다. DB나 Docker는 필요 없습니다. */
class InformationTypeTest {

    @Test
    void 민감정보는_질환_복용약_알러지_주소_출입_방법_5개다() {
        assertThat(Arrays.stream(values()).filter(InformationType::isSensitive))
                .containsExactlyInAnyOrder(DISEASE, MEDICATION, ALLERGY, ADDRESS, ENTRY_METHOD);
    }

    @Test
    void 하나만_등록하는_항목은_생년월일_혈액형_주소_출입_방법이다() {
        assertThat(Arrays.stream(values()).filter(type -> !type.isMultiple()))
                .containsExactlyInAnyOrder(BIRTH_DATE, BLOOD_TYPE, ADDRESS, ENTRY_METHOD);
    }

    @Test
    void title이_없는_항목은_생년월일_혈액형_주소_출입_방법이다() {
        assertThat(Arrays.stream(values()).filter(type -> !type.isTitleRequired()))
                .containsExactlyInAnyOrder(BIRTH_DATE, BLOOD_TYPE, ADDRESS, ENTRY_METHOD);
    }

    @Test
    void 분류마다_항목_수가_맞다() {
        assertThat(Arrays.stream(values()).filter(type -> type.getCategory() == InformationCategory.BASIC)).hasSize(2);
        assertThat(Arrays.stream(values()).filter(type -> type.getCategory() == InformationCategory.HEALTH)).hasSize(4);
        assertThat(Arrays.stream(values()).filter(type -> type.getCategory() == InformationCategory.LIFE)).hasSize(3);
        assertThat(Arrays.stream(values()).filter(type -> type.getCategory() == InformationCategory.ADVANCE_MESSAGE)).hasSize(2);
    }
}
