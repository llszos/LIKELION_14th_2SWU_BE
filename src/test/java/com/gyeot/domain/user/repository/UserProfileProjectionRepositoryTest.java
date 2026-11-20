package com.gyeot.domain.user.repository;

import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import com.gyeot.domain.safetycard.crypto.SafetyInformationCipher;
import com.gyeot.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 findProfilesByIds의 조회 조건과 interface projection 매핑을 확인합니다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, SafetyInformationCipher.class})
@ActiveProfiles("test")
@Testcontainers
class UserProfileProjectionRepositoryTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withEnv("TZ", "Asia/Seoul");

    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void 사용자_ID로_조회하고_실제_MySQL의_projection_필드를_매핑한다() {
        User active = userRepository.save(User.register("active@example.com", "hash", "활성 사용자"));
        User withdrawn = User.register("withdrawn@example.com", "hash", "탈퇴 전 이름");
        withdrawn.withdraw(LocalDateTime.of(2026, 11, 16, 9, 0));
        userRepository.save(withdrawn);
        userRepository.flush();
        entityManager.clear();

        Map<Long, UserProfileProjection> profilesById = userRepository.findProfilesByIds(
                        List.of(active.getId(), withdrawn.getId(), Long.MAX_VALUE))
                .stream()
                .collect(Collectors.toMap(UserProfileProjection::getId, Function.identity()));

        assertThat(profilesById).containsOnlyKeys(active.getId(), withdrawn.getId());
        assertThat(profilesById.get(active.getId()).getName()).isEqualTo("활성 사용자");
        assertThat(profilesById.get(active.getId()).getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(profilesById.get(withdrawn.getId()).getName()).isNull();
        assertThat(profilesById.get(withdrawn.getId()).getAccountStatus()).isEqualTo(AccountStatus.WITHDRAWN);
    }
}
