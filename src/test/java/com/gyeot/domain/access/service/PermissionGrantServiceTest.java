package com.gyeot.domain.access.service;

import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 이벤트 값이 공통 처리(PermissionWriter)에 올바르게 넘어가는지 확인합니다. (#42) */
class PermissionGrantServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 15, 0);

    private PermissionWriter permissionWriter;
    private PermissionGrantService service;

    @BeforeEach
    void setUp() {
        permissionWriter = mock(PermissionWriter.class);
        service = new PermissionGrantService(permissionWriter);
    }

    @Test
    void 요청_생성이면_요청자를_정보_주인으로_최초_지정에_PENDING을_만든다() {
        // 요청 1번, 요청자 10번, 지정 7번, 대상자 20번, 공개 항목 100·101번
        service.grantOnRequestCreated(new CareRequestCreatedEvent(
                1L, 10L, RequestType.GENERAL, 7L, 20L, List.of(100L, 101L), NOW));

        verify(permissionWriter).createPending(1L, 7L, 10L, List.of(100L, 101L));
    }
}
