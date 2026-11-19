package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.repository.RelationshipQueryRepository;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.domain.situation.query.SituationParticipantReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * VIEWED 이력 저장이 실패하면 전달받은 정보 응답도 실패하는지 실제 트랜잭션으로 확인합니다. (#45)
 *
 * - 이력 없이 항목 내용이 나가면 정보 주인이 누가 언제 봤는지 알 수 없으므로, 이력 저장과 응답을 한 트랜잭션으로 묶었습니다.
 * - 이력 저장소만 Mock으로 바꿔 saveAll을 실패시킵니다. (다른 통합 테스트와 Spring 설정이 달라 클래스를 나눔)
 * - 실패 직전에 실제 DB에 검증용 항목을 저장하고 flush합니다. 서비스 트랜잭션이 끝난 뒤 항목이 남지 않아야 합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SharedInformationViewLogRollbackTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final Long REQUEST_ID = 31L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long ASSIGNEE_ID = 20L;
    private static final Long ASSIGNMENT_ID = 40L;
    private static final Long SITUATION_ID = 5L;
    private static final LocalDateTime ACCEPTED_AT = LocalDateTime.of(2026, 11, 14, 9, 0);

    @Autowired private SharedInformationService sharedInformationService;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;

    @MockitoBean private InformationAccessLogRepository accessLogRepository; // VIEWED 저장을 실패시킴
    @MockitoBean private CareRequestSharingReader careRequestSharingReader;
    @MockitoBean private RelationshipQueryRepository relationshipQueryRepository;
    @MockitoBean private SituationParticipantReader situationParticipantReader;

    private final AtomicReference<Long> rollbackMarkerId = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        Long medicationId = safetyInformationRepository.save(
                SafetyInformation.create(REQUESTER_ID, InformationType.MEDICATION, "혈압약", "아침 1정")).getId();
        permissionRepository.save(RequestInformationPermission.createActive(
                REQUEST_ID, ASSIGNMENT_ID, medicationId, InformationType.MEDICATION, ACCEPTED_AT, null));

        // 볼 수 있는 상태 (진행 중인 일반 요청의 지금 담당자, 관계 유효, 상황 참여 중)
        when(careRequestSharingReader.hasBeenAssigned(REQUEST_ID, ASSIGNEE_ID)).thenReturn(true);
        when(careRequestSharingReader.findById(REQUEST_ID)).thenReturn(Optional.of(new SharingRequestInfo(
                REQUEST_ID, REQUESTER_ID, ASSIGNMENT_ID, CareRequestStatus.IN_PROGRESS, RequestType.GENERAL,
                ACCEPTED_AT, LocalDateTime.of(2099, 1, 1, 0, 0), SITUATION_ID, ASSIGNEE_ID, ACCEPTED_AT, HelpType.HOSPITAL)));
        when(relationshipQueryRepository.existsBySourceUserIdAndTargetUserIdAndStatus(
                REQUESTER_ID, ASSIGNEE_ID, RelationshipStatus.ACTIVE)).thenReturn(true);
        when(situationParticipantReader.isActiveParticipant(SITUATION_ID, ASSIGNEE_ID)).thenReturn(true);

        when(accessLogRepository.saveAll(anyList())).thenAnswer(invocation -> {
            // VIEWED 저장 대신 같은 트랜잭션에 실제 쓰기를 한 뒤 실패시켜, 단순 예외 전파와 롤백을 구분합니다.
            SafetyInformation marker = safetyInformationRepository.saveAndFlush(
                    SafetyInformation.create(REQUESTER_ID, InformationType.CAUTION, "롤백 검증", "검증용 내용"));
            rollbackMarkerId.set(marker.getId());
            throw new IllegalStateException("이력 저장 실패");
        });
    }

    @AfterEach
    void cleanUp() {
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
    }

    @Test
    void VIEWED_저장이_실패하면_항목_내용을_내려주지_않고_실패한다() {
        long itemsBefore = safetyInformationRepository.count();
        assertThatThrownBy(() -> sharedInformationService.getSharedInformation(REQUEST_ID, ASSIGNEE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이력 저장 실패");

        // ID가 생기고 flush된 실제 항목도 서비스 트랜잭션과 함께 롤백돼야 합니다.
        // 서비스의 @Transactional이 빠지면 저장소가 별도 커밋하므로 이 검증이 실패합니다.
        assertThat(rollbackMarkerId.get()).isNotNull();
        assertThat(safetyInformationRepository.findById(rollbackMarkerId.get())).isEmpty();
        assertThat(safetyInformationRepository.count()).isEqualTo(itemsBefore);
    }
}
