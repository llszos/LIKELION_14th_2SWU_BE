package com.gyeot.domain.safetycard.service;

import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse.CategoryOption;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse.ItemOption;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SharingOptionServiceTest {

    private static final Long USER_ID = 1L;

    private SafetyInformationRepository safetyInformationRepository;
    private RequestInformationPermissionRepository permissionRepository;
    private SharingOptionService service;

    @BeforeEach
    void setUp() {
        safetyInformationRepository = mock(SafetyInformationRepository.class);
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        service = new SharingOptionService(safetyInformationRepository, permissionRepository);

        when(safetyInformationRepository.findAllByOwnerIdOrderByIdAsc(USER_ID)).thenReturn(List.of(
                item(1L, InformationType.BIRTH_DATE, null, "2000-12-30"),
                item(12L, InformationType.MEDICATION, "혈압약", "매일 1정"),
                item(14L, InformationType.ALLERGY, "페니실린", "두드러기 반응"),
                item(20L, InformationType.ADDRESS, null, "서울시 노원구")));
    }

    @Test
    void 분류_4개를_순서대로_내려주고_항목이_없으면_빈_배열이다() {
        SharingOptionsResponse response = service.getSharingOptions(USER_ID, HelpType.OTHER, null);

        assertThat(response.categories()).extracting(CategoryOption::category).containsExactly(
                InformationCategory.BASIC, InformationCategory.HEALTH, InformationCategory.LIFE, InformationCategory.ADVANCE_MESSAGE);
        assertThat(response.categories().get(1).items()).extracting(ItemOption::informationId).containsExactly(12L, 14L);
        assertThat(response.categories().get(3).items()).isEmpty();
    }

    @Test
    void 새_요청은_도움_종류_기본_유형을_선택한다() {
        SharingOptionsResponse response = service.getSharingOptions(USER_ID, HelpType.HOSPITAL, null);

        assertThat(selectedIds(response)).containsExactly(12L, 14L); // 복용약, 알러지
        verify(permissionRepository, never()).findLastSharedInformationIds(any(), any());
    }

    @Test
    void requestId가_있으면_마지막_대상에게_공개했던_항목을_선택한다() {
        List<RevokeReason> excluded = List.of(RevokeReason.SHARING_EXCLUDED, RevokeReason.OWNER_REVOKED, RevokeReason.ITEM_DELETED);
        when(permissionRepository.findLastSharedInformationIds(5L, excluded)).thenReturn(List.of(1L, 20L));

        SharingOptionsResponse response = service.getSharingOptions(USER_ID, HelpType.HOSPITAL, 5L);

        assertThat(selectedIds(response)).containsExactly(1L, 20L); // 기본 유형이 아니어도 공개했던 항목이면 선택
    }

    @Test
    void 항목_정보와_민감_여부를_내려준다() {
        ItemOption medication = service.getSharingOptions(USER_ID, HelpType.OTHER, null)
                .categories().get(1).items().get(0);

        assertThat(medication.typeName()).isEqualTo("복용약");
        assertThat(medication.title()).isEqualTo("혈압약");
        assertThat(medication.informationValue()).isEqualTo("매일 1정");
        assertThat(medication.sensitive()).isTrue();
        assertThat(medication.selected()).isFalse();
    }

    private List<Long> selectedIds(SharingOptionsResponse response) {
        return response.categories().stream()
                .flatMap(category -> category.items().stream())
                .filter(ItemOption::selected)
                .map(ItemOption::informationId)
                .toList();
    }

    private SafetyInformation item(Long id, InformationType type, String title, String value) {
        SafetyInformation information = SafetyInformation.create(USER_ID, type, title, value);
        ReflectionTestUtils.setField(information, "id", id); // DB 없이 테스트하므로 ID를 직접 넣음
        return information;
    }
}
