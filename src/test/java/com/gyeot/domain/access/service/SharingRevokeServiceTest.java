package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingRevokeResponse;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공유 직접 철회 진입점이 요청 ID를 확보해 쓰기 트랜잭션에 넘기는지 확인합니다. (트랜잭션 경계 자체는 통합 테스트에서 확인)
 */
class SharingRevokeServiceTest {

    private static final Long OWNER_ID = 10L;
    private static final Long ASSIGNMENT_ID = 40L;
    private static final Long REQUEST_ID = 31L;

    private RequestInformationPermissionRepository permissionRepository;
    private SharingRevokeTransaction revokeTransaction;
    private SharingRevokeService service;

    @BeforeEach
    void setUp() {
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        revokeTransaction = mock(SharingRevokeTransaction.class);
        service = new SharingRevokeService(permissionRepository, revokeTransaction);
    }

    @Test
    void 지정의_요청_ID를_찾아_쓰기_트랜잭션에_넘긴다() {
        SharingRevokeResponse expected = SharingRevokeResponse.revoked(ASSIGNMENT_ID, true);
        when(permissionRepository.findRequestIdByAssignmentId(ASSIGNMENT_ID)).thenReturn(Optional.of(REQUEST_ID));
        when(revokeTransaction.revoke(OWNER_ID, ASSIGNMENT_ID, REQUEST_ID)).thenReturn(expected);

        assertThat(service.revoke(OWNER_ID, ASSIGNMENT_ID)).isEqualTo(expected);
    }

    @Test
    void 권한이_없는_지정이면_404이고_쓰기_트랜잭션을_시작하지_않는다() {
        when(permissionRepository.findRequestIdByAssignmentId(ASSIGNMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(OWNER_ID, ASSIGNMENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SafetyCardErrorCode.SHARING_NOT_FOUND);
        verify(revokeTransaction, never()).revoke(any(), any(), any());
    }
}
