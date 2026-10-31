package com.gyeot.domain.user.repository;

import com.gyeot.domain.user.entity.AccountStatus;
import com.gyeot.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // 회원가입 시 이미 등록된 이메일인지 확인합니다.
    boolean existsByEmail(String email);

    // 로그인 시 이메일로 사용자 계정을 조회합니다.
    Optional<User> findByEmail(String email);

    // 로그인과 탈퇴 처리가 동시에 실행될 때 계정 상태 확인을 직렬화합니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    // JWT 인증 시 활성 계정인지 확인합니다.
    boolean existsByIdAndAccountStatus(
            Long id,
            AccountStatus accountStatus
    );

    // 사용자 단위 작업을 직렬화할 때 사용하는 잠금 조회입니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);
}
