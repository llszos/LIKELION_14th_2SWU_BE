package com.gyeot.domain.safetycard.service;

import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.entity.RevokeReason;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    private CareRequestSharingReader careRequestSharingReader;
    private SharingOptionService service;

    @BeforeEach
    void setUp() {
        safetyInformationRepository = mock(SafetyInformationRepository.class);
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        careRequestSharingReader = mock(CareRequestSharingReader.class);
        service = new SharingOptionService(safetyInformationRepository, permissionRepository, careRequestSharingReader);

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
        verify(careRequestSharingReader, never()).findLastAssignmentId(any());
        verify(permissionRepository, never()).findAllByAssignmentIdOrderByIdAsc(any());
    }

    @Test
    void requestId가_있으면_마지막_대상에게_공개했던_항목을_선택한다() {
        when(careRequestSharingReader.findLastAssignmentId(5L)).thenReturn(Optional.of(40L));
        RequestInformationPermission revoked = permission(3L, 12L, InformationType.MEDICATION);
        revoked.revoke(RevokeReason.OWNER_REVOKED, LocalDateTime.of(2026, 11, 10, 9, 0));
        when(permissionRepository.findAllByAssignmentIdOrderByIdAsc(40L)).thenReturn(List.of(
                permission(1L, 1L, InformationType.BIRTH_DATE),
                permission(2L, 20L, InformationType.ADDRESS),
                revoked));

        SharingOptionsResponse response = service.getSharingOptions(USER_ID, HelpType.HOSPITAL, 5L);

        // 기본 유형이 아니어도 공개했던 항목이면 선택하고, 기본 유형이어도 직접 철회한 항목(복용약)은 선택하지 않음
        assertThat(selectedIds(response)).containsExactly(1L, 20L);
    }

    @Test
    void 마지막_지정이_없으면_아무것도_선택하지_않는다() {
        when(careRequestSharingReader.findLastAssignmentId(5L)).thenReturn(Optional.empty());

        SharingOptionsResponse response = service.getSharingOptions(USER_ID, HelpType.HOSPITAL, 5L);

        assertThat(selectedIds(response)).isEmpty(); // 도움 종류 기본값으로 돌아가지 않음
        verify(permissionRepository, never()).findAllByAssignmentIdOrderByIdAsc(any());
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

    /** 마지막 지정(ID 40)에 공개한 권한 (수락 전 PENDING). ID는 최신 행 판단에 쓰여 직접 넣음 */
    private RequestInformationPermission permission(Long id, Long informationId, InformationType type) {
        RequestInformationPermission permission = RequestInformationPermission.createPending(5L, 40L, informationId, type);
        ReflectionTestUtils.setField(permission, "id", id);
        return permission;
    }

    private SafetyInformation item(Long id, InformationType type, String title, String value) {
        SafetyInformation information = SafetyInformation.create(USER_ID, type, title, value);
        ReflectionTestUtils.setField(information, "id", id); // DB 없이 테스트하므로 ID를 직접 넣음
        return information;
    }
}
