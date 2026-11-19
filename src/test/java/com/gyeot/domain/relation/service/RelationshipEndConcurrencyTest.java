package com.gyeot.domain.relation.service;

import com.gyeot.domain.relation.entity.Relationship;
import com.gyeot.domain.relation.entity.RelationshipStatus;
import com.gyeot.domain.relation.event.RelationshipEndedEvent;
import com.gyeot.domain.relation.exception.RelationshipErrorCode;
import com.gyeot.domain.relation.repository.EmergencyContactRepository;
import com.gyeot.domain.relation.repository.RelationshipRepository;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.user.repository.UserRepository;
import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 같은 관계의 동시 해제가 한 번만 성공하고 이벤트도 한 번만 발행되는지 확인합니다. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@RecordApplicationEvents
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RelationshipEndConcurrencyTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    /** 두 작업이 모두 사용자 잠금 전에 관계 참여자 projection을 마친 뒤 진행하게 합니다. */
    static class PauseAfterParticipantsProjection {
        private static volatile CountDownLatch arrived;
        private static volatile CountDownLatch release;

        static void arm() {
            arrived = new CountDownLatch(2);
            release = new CountDownLatch(1);
        }

        static void disarm() {
            CountDownLatch currentRelease = release;
            release = null;
            arrived = null;
            if (currentRelease != null) {
                currentRelease.countDown();
            }
        }

        static void afterProjection(String methodName) {
            CountDownLatch currentArrived = arrived;
            CountDownLatch currentRelease = release;
            if (!"findParticipantsById".equals(methodName) || currentArrived == null || currentRelease == null) {
                return;
            }
            currentArrived.countDown();
            try {
                if (!currentRelease.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 해제 작업을 시작하지 못했습니다.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
    }

    @TestConfiguration
    static class PauseConfig {
        @Bean
        static BeanPostProcessor relationshipRepositoryPausePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof RelationshipRepository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            RelationshipRepository.class.getClassLoader(),
                            new Class<?>[]{RelationshipRepository.class},
                            (proxy, method, args) -> {
                                try {
                                    Object result = method.invoke(bean, args);
                                    PauseAfterParticipantsProjection.afterProjection(method.getName());
                                    return result;
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }
                            });
                }
            };
        }
    }

    @Autowired private RelationshipService relationshipService;
    @Autowired private RelationshipRepository relationshipRepository;
    @Autowired private EmergencyContactRepository emergencyContactRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ApplicationEvents applicationEvents;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Long relationshipId;
    private Long sourceUserId;

    @AfterEach
    void cleanUp() throws InterruptedException {
        PauseAfterParticipantsProjection.disarm();
        executor.shutdown();
        if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("DB 정리 전에 동시 작업 스레드가 종료돼야 합니다")
                    .isTrue();
        }
        relationshipRepository.deleteAllInBatch();
        emergencyContactRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void 같은_나의_사람_관계를_동시에_해제하면_성공_하나_409_하나와_이벤트_한_건만_남는다() throws Exception {
        String unique = UUID.randomUUID().toString();
        User source = userRepository.save(User.register("source-" + unique + "@example.com", "hash", "보내는 사람"));
        User target = userRepository.save(User.register("target-" + unique + "@example.com", "hash", "대상자"));
        sourceUserId = source.getId();
        relationshipId = relationshipRepository.save(
                Relationship.createMyPerson(sourceUserId, target.getId(), "친구")).getId();

        PauseAfterParticipantsProjection.arm();
        Callable<String> end = () -> {
            try {
                relationshipService.endRelationship(sourceUserId, relationshipId);
                return "SUCCESS";
            } catch (BusinessException exception) {
                return exception.getErrorCode().getCode();
            }
        };
        List<Future<String>> futures = List.of(executor.submit(end), executor.submit(end));

        assertThat(PauseAfterParticipantsProjection.arrived.await(10, TimeUnit.SECONDS))
                .as("두 작업이 모두 사용자 잠금 전에 관계 참여자를 조회해야 합니다")
                .isTrue();
        PauseAfterParticipantsProjection.release.countDown();

        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }

        assertThat(results).containsExactlyInAnyOrder(
                "SUCCESS", RelationshipErrorCode.RELATIONSHIP_ALREADY_ENDED.getCode());
        assertThat(relationshipRepository.findById(relationshipId).orElseThrow().getStatus())
                .isEqualTo(RelationshipStatus.ENDED);
        assertThat(applicationEvents.stream(RelationshipEndedEvent.class))
                .filteredOn(event -> event.relationshipId().equals(relationshipId))
                .hasSize(1);
    }
}
