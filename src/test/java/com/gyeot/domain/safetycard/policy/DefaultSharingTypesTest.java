package com.gyeot.domain.safetycard.policy;

import com.gyeot.domain.carerequest.enums.HelpType;
import org.junit.jupiter.api.Test;

import static com.gyeot.domain.safetycard.entity.InformationType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 도움 종류별 기본 공개 항목이 기능명세서(F-03)와 같은지 확인합니다. DB나 Docker는 필요 없습니다. */
class DefaultSharingTypesTest {

    @Test
    void 도움_종류별_기본_공개_유형이_기능명세서와_같다() {
        assertThat(DefaultSharingTypes.of(HelpType.HOSPITAL)).containsExactlyInAnyOrder(MEDICATION, ALLERGY);
        assertThat(DefaultSharingTypes.of(HelpType.MEAL))
                .containsExactlyInAnyOrder(DISEASE, MEDICATION, ALLERGY, CAUTION, ADDRESS, ENTRY_METHOD);
        assertThat(DefaultSharingTypes.of(HelpType.GROCERY)).isEqualTo(DefaultSharingTypes.of(HelpType.MEAL));
        assertThat(DefaultSharingTypes.of(HelpType.PET_CARE)).containsExactlyInAnyOrder(ADDRESS, ENTRY_METHOD, PET);
        assertThat(DefaultSharingTypes.of(HelpType.CHECK_IN)).containsExactlyInAnyOrder(BIRTH_DATE, BLOOD_TYPE);
        assertThat(DefaultSharingTypes.of(HelpType.OTHER)).isEmpty();
    }

    @Test
    void 돌려받은_목록은_수정할_수_없다() {
        assertThatThrownBy(() -> DefaultSharingTypes.of(HelpType.HOSPITAL).add(PET))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
