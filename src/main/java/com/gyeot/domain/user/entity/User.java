package com.gyeot.domain.user.entity;

import com.gyeot.global.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 서비스 사용자 계정과 표시 정보를 관리합니다. (ERD 4.1 users)
 * 탈퇴 후에도 사용자 행과 ID는 유지하고, 개인정보는 비식별화합니다.
 * 생성·수정 일시는 BaseEntity가 자동으로 채웁니다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    // 사용자 식별자. MySQL에서 번호를 자동 발급.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로그인 식별 이메일. 탈퇴 후 개인정보 비식별화를 위해 NULL을 허용함.
    @Column(length = 255, unique = true)
    private String email;

    // 원문 비밀번호가 아닌 단방향 해시값을 저장합니다. 탈퇴 시 NULL로 변경함.
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    // 서비스에서 표시할 이름. 가입 시 필수 여부는 서비스에서 검증하고, 탈퇴 시 NULL로 변경함.
    @Column(length = 50)
    private String name;

    // Enum 이름을 문자열로 저장함.
    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", length = 20, nullable = false)
    private AccountStatus accountStatus;

    // 탈퇴 처리 시각. 활성 계정이면 NULL.
    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    // 외부에서 임의로 생성하지 않고 register()를 통해 가입 상태를 명확히 설정함.
    private User(String email, String passwordHash, String name) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.accountStatus = AccountStatus.ACTIVE;
    }

    // 새 계정을 생성. 비밀번호는 호출 전에 해시한 값을 전달해야 함.
    public static User register(String email, String passwordHash, String name) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("이메일은 필수입니다.");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("비밀번호 해시는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름은 필수입니다.");
        }

        return new User(email, passwordHash, name);
    }

    // 탈퇴 시 개인정보를 비우고 계정 상태와 탈퇴 시각을 기록.
    public void withdraw(LocalDateTime withdrawnAt) {
        if (this.accountStatus == AccountStatus.WITHDRAWN) {
            throw new IllegalStateException("이미 탈퇴한 계정입니다.");
        }

        this.email = null;
        this.passwordHash = null;
        this.name = null;
        this.accountStatus = AccountStatus.WITHDRAWN;
        this.withdrawnAt = withdrawnAt;
    }
}
