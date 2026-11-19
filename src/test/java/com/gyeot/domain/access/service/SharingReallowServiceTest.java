package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharedItemName;
import com.gyeot.domain.access.dto.response.SharingReallowResponse;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공유 다시 허용 진입점이 요청 ID를 확보해 쓰기 트랜잭션에 넘기는지 확인합니다. (트랜잭션 경계 자체는 통합 테스트에서 확인)
 */
class SharingReallowServiceTest {

    private static final Long OWNER_ID = 10L;      // 로그인한 사용자 (정보 주인)
    private static final Long ASSIGNMENT_ID = 40L; // 다시 허용할 공유 카드 ID
    private static final Long REQUEST_ID = 31L;    // 카드가 속한 요청 ID

    private RequestInformationPermissionRepository permissionRepository; // 지정 → 요청 ID 조회 (Mock)
    private SharingReallowTransaction reallowTransaction;                 // 실제 다시 허용 (Mock, 넘긴 값만 확인)
    private SharingReallowService service;

    @BeforeEach
    void setUp() {
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        reallowTransaction = mock(SharingReallowTransaction.class);
        service = new SharingReallowService(permissionRepository, reallowTransaction);
    }

    @Test
    void 지정의_요청_ID를_찾아_쓰기_트랜잭션에_넘긴다() {
        SharingReallowResponse expected = SharingReallowResponse.reallowed(ASSIGNMENT_ID,
                List.of(SharedItemName.of(InformationType.MEDICATION, "혈압약")));
        when(permissionRepository.findRequestIdByAssignmentId(ASSIGNMENT_ID)).thenReturn(Optional.of(REQUEST_ID));
        when(reallowTransaction.reallow(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID)).thenReturn(expected);

        assertThat(service.reallow(OWNER_ID, ASSIGNMENT_ID)).isEqualTo(expected); // 쓰기 트랜잭션의 응답을 그대로 돌려줌
    }

    @Test
    void 권한이_없는_지정이면_404이고_쓰기_트랜잭션을_시작하지_않는다() {
        when(permissionRepository.findRequestIdByAssignmentId(ASSIGNMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reallow(OWNER_ID, ASSIGNMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SHARING_NOT_FOUND);
        verify(reallowTransaction, never()).reallow(any(), any(), any()); // 잠금 없이 바로 404
    }
}
