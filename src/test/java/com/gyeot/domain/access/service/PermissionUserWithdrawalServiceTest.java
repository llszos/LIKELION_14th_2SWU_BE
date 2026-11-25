package com.gyeot.domain.access.service;

import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.user.event.UserWithdrawnEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

class PermissionUserWithdrawalServiceTest {

    private static final Long USER_ID = 21L;
    private static final LocalDateTime WITHDRAWN_AT = LocalDateTime.of(2026, 11, 25, 12, 0);

    private SafetyInformationRepository informationRepository;
    private PermissionWriter permissionWriter;
    private InformationAccessLogRepository accessLogRepository;
    private RequestInformationPermissionRepository permissionRepository;
    private PermissionUserWithdrawalService service;

    @BeforeEach
    void setUp() {
        informationRepository = mock(SafetyInformationRepository.class);
        permissionWriter = mock(PermissionWriter.class);
        accessLogRepository = mock(InformationAccessLogRepository.class);
        permissionRepository = mock(RequestInformationPermissionRepository.class);
        service = new PermissionUserWithdrawalService(
                informationRepository, permissionWriter, accessLogRepository, permissionRepository);
    }

    @Test
    void 탈퇴하면_열린_권한의_회수_이력을_남기고_연결을_끊은_뒤_권한과_원본을_삭제한다() {
        SafetyInformation item = mock(SafetyInformation.class);
        doReturn(101L).when(item).getId();
        doReturn(USER_ID).when(item).getOwnerId();
        when(informationRepository.findIdsByOwnerIdOrderByIdAsc(USER_ID)).thenReturn(List.of(101L));
        when(informationRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(item));
        when(permissionWriter.revokeForOwnerWithdrawal(List.of(101L), USER_ID, WITHDRAWN_AT))
                .thenReturn(List.of(301L, 302L));

        service.cleanupOwnerData(new UserWithdrawnEvent(USER_ID, WITHDRAWN_AT));

        InOrder order = inOrder(permissionWriter, accessLogRepository, permissionRepository, informationRepository);
        order.verify(permissionWriter).revokeForOwnerWithdrawal(List.of(101L), USER_ID, WITHDRAWN_AT);
        order.verify(accessLogRepository).flush();
        order.verify(accessLogRepository).clearPermissionIds(List.of(301L, 302L));
        order.verify(permissionRepository).deleteAllByPermissionIdIn(List.of(301L, 302L));
        order.verify(informationRepository).deleteAll(List.of(item));
    }

    @Test
    void 이미_원본이_없으면_재호출에도_아무것도_하지_않는다() {
        when(informationRepository.findIdsByOwnerIdOrderByIdAsc(USER_ID)).thenReturn(List.of());

        service.cleanupOwnerData(new UserWithdrawnEvent(USER_ID, WITHDRAWN_AT));

        verify(permissionWriter, never()).revokeForOwnerWithdrawal(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(accessLogRepository, never()).clearPermissionIds(org.mockito.ArgumentMatchers.anyList());
        verify(permissionRepository, never()).deleteAllByPermissionIdIn(org.mockito.ArgumentMatchers.anyList());
        verify(informationRepository, never()).deleteAll(org.mockito.ArgumentMatchers.<SafetyInformation>anyList());
    }
}
