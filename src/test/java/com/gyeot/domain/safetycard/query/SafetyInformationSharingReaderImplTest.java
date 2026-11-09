package com.gyeot.domain.safetycard.query;

import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 없이 Repository를 Mock으로 바꿔 검증 규칙만 확인합니다. */
class SafetyInformationSharingReaderImplTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 2L;

    private SafetyInformationRepository safetyInformationRepository;
    private RequestInformationPermissionRepository permissionRepository;
    private SafetyInformationSharingReaderImpl reader;

    @BeforeEach
    void setUp() {
        safetyInformationRepository = mock(SafetyInformationRepository.class);
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        reader = new SafetyInformationSharingReaderImpl(safetyInformationRepository, permissionRepository);
    }

    @Test
    void 빈_목록과_null은_통과한다() {
        assertThatCode(() -> reader.validateSelection(OWNER_ID, List.of())).doesNotThrowAnyException();
        assertThatCode(() -> reader.validateSelection(OWNER_ID, null)).doesNotThrowAnyException();
        verify(safetyInformationRepository, never()).findAllById(any());
    }

    @Test
    void 모두_본인_항목이면_통과한다() {
        when(safetyInformationRepository.findAllById(Set.of(10L, 11L)))
                .thenReturn(List.of(item(OWNER_ID), item(OWNER_ID)));

        assertThatCode(() -> reader.validateSelection(OWNER_ID, List.of(10L, 11L))).doesNotThrowAnyException();
    }

    @Test
    void 없는_ID가_있으면_거부한다() {
        when(safetyInformationRepository.findAllById(Set.of(10L, 99L))).thenReturn(List.of(item(OWNER_ID)));

        assertInvalidSelection(List.of(10L, 99L));
    }

    @Test
    void 다른_사람_항목이_있으면_거부한다() {
        when(safetyInformationRepository.findAllById(Set.of(10L, 20L)))
                .thenReturn(List.of(item(OWNER_ID), item(OTHER_ID)));

        assertInvalidSelection(List.of(10L, 20L));
    }

    @Test
    void 중복_ID나_null이_있으면_조회하지_않고_거부한다() {
        assertInvalidSelection(List.of(10L, 10L));
        assertInvalidSelection(Arrays.asList(10L, null));
        verify(safetyInformationRepository, never()).findAllById(any());
    }

    @Test
    void 공개_중_항목은_PENDING과_ACTIVE_권한으로_조회한다() {
        List<PermissionStatus> statuses = List.of(PermissionStatus.PENDING, PermissionStatus.ACTIVE);
        when(permissionRepository.findInformationIdsByRequestIdAndStatusIn(5L, statuses)).thenReturn(List.of(10L, 11L));

        assertThat(reader.findSharingInformationIds(5L)).containsExactly(10L, 11L);
    }

    private void assertInvalidSelection(List<Long> informationIds) {
        assertThatThrownBy(() -> reader.validateSelection(OWNER_ID, informationIds))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SAFETY_INFO_INVALID_SELECTION);
    }

    private SafetyInformation item(Long ownerId) {
        return SafetyInformation.create(ownerId, InformationType.MEDICATION, "혈압약", "아침 식후 1정");
    }
}
