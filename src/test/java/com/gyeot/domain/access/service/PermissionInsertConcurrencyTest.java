package com.gyeot.domain.access.service;

import com.gyeot.domain.access.entity.AccessEventType;
import com.gyeot.domain.access.entity.InformationAccessLog;
import com.gyeot.domain.access.entity.PermissionStatus;
import com.gyeot.domain.access.entity.RequestInformationPermission;
import com.gyeot.domain.access.repository.InformationAccessLogRepository;
import com.gyeot.domain.access.repository.RequestInformationPermissionRepository;
import com.gyeot.domain.carerequest.enums.CareRequestStatus;
import com.gyeot.domain.carerequest.enums.ChangeableField;
import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.enums.RequestType;
import com.gyeot.domain.carerequest.event.CareRequestChangeConfirmedEvent;
import com.gyeot.domain.carerequest.event.CareRequestCreatedEvent;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader;
import com.gyeot.domain.carerequest.query.CareRequestSharingReader.SharingRequestInfo;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

/**
 * 서로 다른 요청이 동시에 권한을 만들 때, 실제 권한 처리(리스너 → 서비스 → PermissionWriter)의 조회가
 * 서로의 INSERT를 막지 않는지 확인합니다. (#38 리뷰 반영)
 *
 * - 배경: 권한을 만들기 전 조회가 범위 잠금이면 권한이 없는 지정의 빈 인덱스 범위에 갭 락이 걸리고,
 *   두 트랜잭션이 같은 빈 범위를 잠근 뒤 각자 INSERT하면 서로 기다리다 교착이 날 수 있습니다.
 * - 바꾼 방식: 중복 확인은 잠그지 않는 항목 ID 조회, 기존 권한은 권한 ID 조회 뒤 기본 키 잠금 (PermissionWriter)
 *
 * - 실제 이벤트를 발행해 Writer를 그대로 거칩니다. 조회와 INSERT 사이를 맞추려고, 권한 저장소 빈을 테스트에서만 감싸
 *   지정한 조회 메서드가 실행된 직후 두 작업이 모두 그 지점에 올 때까지 기다리게 합니다. (PauseAfterQuery)
 *   나머지 호출은 실제 저장소로 그대로 넘어갑니다.
 *   Writer가 다시 범위 잠금 조회를 쓰도록 바뀌면 이 메서드를 부르지 않아 멈추는 지점에 오지 못하고 테스트가 실패합니다.
 * - 두 요청은 요청자, 지정, 항목이 모두 다릅니다. 항목 잠금으로는 순서가 정해지지 않는 경우를 겨냥합니다.
 * - 교착이 반드시 재현된다는 보장은 없어, 바꾼 방식으로 두 작업이 모두 성공하는지를 보는 회귀 테스트입니다.
 * - 각 테스트는 커밋되므로 끝나면 직접 지웁니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PermissionInsertConcurrencyTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 14, 10, 0);
    private static final LocalDateTime CONFIRMED_AT = CREATED_AT.plusHours(2);

    // 서로 다른 두 요청 (요청 ID, 요청자, 지정, 대상자 모두 다름)
    private static final Long REQUEST_A = 1L;
    private static final Long REQUEST_B = 2L;
    private static final Long OWNER_A = 10L;
    private static final Long OWNER_B = 11L;
    private static final Long ASSIGNMENT_A = 7L;
    private static final Long ASSIGNMENT_B = 8L;
    private static final Long TARGET_A = 20L;
    private static final Long TARGET_B = 21L;

    /**
     * 권한 저장소를 감싸, 지정한 조회 메서드가 실행된 직후 두 작업이 모두 올 때까지 기다리게 합니다.
     *
     * - 테스트마다 멈출 메서드 이름을 정하고(arm), 두 작업이 모두 도착하면 함께 진행합니다.
     * - 한 작업이 같은 메서드를 여러 번 불러도 처음 한 번만 멈춥니다. (스레드별 1회)
     * - 정하지 않았으면(disarm) 그대로 통과합니다. 준비 데이터를 만들 때는 멈추지 않습니다.
     */
    static class PauseAfterQuery {

        private static volatile String method;           // 멈출 메서드 이름 (null이면 멈추지 않음)
        private static volatile CountDownLatch arrived;   // 두 작업이 조회를 마쳤다는 신호
        private static final ThreadLocal<Boolean> paused = ThreadLocal.withInitial(() -> false);

        static void arm(String methodName) {
            arrived = new CountDownLatch(2);
            method = methodName;
        }

        static void disarm() {
            method = null;
        }

        static void afterCall(String calledMethod) {
            if (calledMethod.equals(method) && !paused.get()) {
                paused.set(true);
                arrived.countDown();
                try {
                    if (!arrived.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("상대 작업이 " + calledMethod + " 조회 지점에 오지 않았습니다.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        }

        static void resetThread() {
            paused.remove();
        }
    }

    @TestConfiguration
    static class PauseConfig {

        /** 권한 저장소 빈만 감싸 PermissionWriter에 주입되게 합니다. 호출은 모두 실제 저장소로 넘깁니다. */
        @Bean
        static BeanPostProcessor pauseAfterQueryPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof RequestInformationPermissionRepository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            RequestInformationPermissionRepository.class.getClassLoader(),
                            new Class<?>[]{RequestInformationPermissionRepository.class},
                            (proxy, invokedMethod, args) -> {
                                Object result;
                                try {
                                    result = invokedMethod.invoke(bean, args);
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause(); // 실제 저장소의 예외를 그대로 던짐
                                }
                                PauseAfterQuery.afterCall(invokedMethod.getName()); // 실행 직후에 멈춤
                                return result;
                            });
                }
            };
        }
    }

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private SafetyInformationRepository safetyInformationRepository;
    @Autowired private RequestInformationPermissionRepository permissionRepository;
    @Autowired private InformationAccessLogRepository accessLogRepository;

    @MockitoBean private CareRequestSharingReader careRequestSharingReader; // 재확인 수락의 기한 계산용 (요청 유형, 수락 시각)

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Long allergyOfA;    // 요청 A가 공개할 항목 (A의 알러지)
    private Long medicationOfB; // 요청 B가 공개할 항목 (B의 복용약)

    @BeforeEach
    void setUp() {
        PauseAfterQuery.disarm();
        allergyOfA = safetyInformationRepository.save(
                SafetyInformation.create(OWNER_A, InformationType.ALLERGY, "땅콩", "호흡 곤란")).getId();
        medicationOfB = safetyInformationRepository.save(
                SafetyInformation.create(OWNER_B, InformationType.MEDICATION, "혈압약", "아침 식후 1정")).getId();
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        PauseAfterQuery.disarm();
        // 스레드가 끝나 잠금이 모두 풀린 뒤에 지움
        executor.shutdown();
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("DB 정리 전에 작업 스레드가 종료돼야 합니다")
                    .isTrue();
        }
        accessLogRepository.deleteAll();
        permissionRepository.deleteAll();
        safetyInformationRepository.deleteAll();
    }

    @Test
    void 서로_다른_두_요청을_동시에_만들어도_중복_확인_뒤_INSERT가_서로_막히지_않는다() throws Exception {
        // 요청 생성: 항목 잠금 → 항목 확인 → 중복 확인 조회 [두 작업 모두 여기서 대기] → PENDING INSERT
        PauseAfterQuery.arm("findInformationIdsByAssignmentIdAndStatusIn");

        Future<?> first = executor.submit(() -> publish(new CareRequestCreatedEvent(
                REQUEST_A, OWNER_A, RequestType.GENERAL, ASSIGNMENT_A, TARGET_A, List.of(allergyOfA), CREATED_AT)));
        Future<?> second = executor.submit(() -> publish(new CareRequestCreatedEvent(
                REQUEST_B, OWNER_B, RequestType.GENERAL, ASSIGNMENT_B, TARGET_B, List.of(medicationOfB), CREATED_AT)));

        // 교착이면 한쪽이 교착 예외로 실패하거나 시간 안에 끝나지 않음
        assertThat(PauseAfterQuery.arrived.await(10, TimeUnit.SECONDS))
                .as("두 작업 모두 지정한 조회를 마쳐야 합니다")
                .isTrue();
        first.get(15, TimeUnit.SECONDS);
        second.get(15, TimeUnit.SECONDS);

        // 두 요청 모두 공개 예정 권한이 생기고, 유형은 공개한 항목의 실제 유형
        assertThat(permissionRepository.findAll())
                .extracting(RequestInformationPermission::getAssignmentId,
                        RequestInformationPermission::getInformationType,
                        RequestInformationPermission::getStatus)
                .containsExactlyInAnyOrder(
                        tuple(ASSIGNMENT_A, InformationType.ALLERGY, PermissionStatus.PENDING),
                        tuple(ASSIGNMENT_B, InformationType.MEDICATION, PermissionStatus.PENDING));
    }

    @Test
    void 공개_항목이_0개인_두_요청에_동시에_재확인_수락으로_추가해도_INSERT가_서로_막히지_않는다() throws Exception {
        // 수락된 두 요청이 처음에는 공개 항목 0개 (권한 행 없음), 재확인 수락으로 항목을 하나씩 추가
        when(careRequestSharingReader.findById(REQUEST_A)).thenReturn(Optional.of(acceptedRequest(REQUEST_A, OWNER_A, ASSIGNMENT_A, TARGET_A)));
        when(careRequestSharingReader.findById(REQUEST_B)).thenReturn(Optional.of(acceptedRequest(REQUEST_B, OWNER_B, ASSIGNMENT_B, TARGET_B)));

        // 수정 확정: 항목 잠금 → 공개 중 권한 ID 조회 [두 작업 모두 여기서 대기] → (0개라 기본 키 잠금 없음) → ACTIVE INSERT
        PauseAfterQuery.arm("findIdsByAssignmentIdAndStatusIn");

        Future<?> first = executor.submit(() -> publish(reconfirmed(REQUEST_A, OWNER_A, 100L, ASSIGNMENT_A, TARGET_A, allergyOfA)));
        Future<?> second = executor.submit(() -> publish(reconfirmed(REQUEST_B, OWNER_B, 200L, ASSIGNMENT_B, TARGET_B, medicationOfB)));

        assertThat(PauseAfterQuery.arrived.await(10, TimeUnit.SECONDS))
                .as("두 작업 모두 지정한 조회를 마쳐야 합니다")
                .isTrue();
        first.get(15, TimeUnit.SECONDS);
        second.get(15, TimeUnit.SECONDS);

        // 재확인 수락 후 추가라 바로 ACTIVE, 항목 유형은 실제 유형
        assertThat(permissionRepository.findAll())
                .extracting(RequestInformationPermission::getAssignmentId,
                        RequestInformationPermission::getInformationType,
                        RequestInformationPermission::getStatus)
                .containsExactlyInAnyOrder(
                        tuple(ASSIGNMENT_A, InformationType.ALLERGY, PermissionStatus.ACTIVE),
                        tuple(ASSIGNMENT_B, InformationType.MEDICATION, PermissionStatus.ACTIVE));

        // 항목마다 GRANTED 이력, 정보 주인은 각 요청자, 수행자는 재확인을 수락한 담당자
        assertThat(accessLogRepository.findAll())
                .extracting(InformationAccessLog::getEventType,
                        InformationAccessLog::getOwnerUserId,
                        InformationAccessLog::getActorUserId)
                .containsExactlyInAnyOrder(
                        tuple(AccessEventType.GRANTED, OWNER_A, TARGET_A),
                        tuple(AccessEventType.GRANTED, OWNER_B, TARGET_B));
    }

    // === 도우미 ===

    /** C처럼 트랜잭션 안에서 이벤트를 발행하고 커밋합니다. 스레드를 재사용하므로 멈춤 기록을 먼저 지웁니다. */
    private void publish(Object event) {
        PauseAfterQuery.resetThread();
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    /** 재확인 수락(제안 ID 있음)으로 공개 항목 목록을 바꾼 수정 확정 이벤트 */
    private static CareRequestChangeConfirmedEvent reconfirmed(Long requestId, Long ownerId, Long proposalId,
                                                               Long assignmentId, Long targetId, Long informationId) {
        return new CareRequestChangeConfirmedEvent(requestId, ownerId, proposalId, assignmentId, targetId,
                Set.of(ChangeableField.INFORMATION_IDS), List.of(informationId), CONFIRMED_AT);
    }

    /** 수락된 일반 요청 (기한 계산에서 일반 요청은 기한 없음) */
    private static SharingRequestInfo acceptedRequest(Long requestId, Long ownerId, Long assignmentId, Long targetId) {
        return new SharingRequestInfo(requestId, ownerId, assignmentId, CareRequestStatus.ACCEPTED, RequestType.GENERAL,
                null, null, null, targetId, CREATED_AT.plusHours(1), HelpType.HOSPITAL);
    }
}
