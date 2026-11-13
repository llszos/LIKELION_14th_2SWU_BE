package com.gyeot.domain.relation.repository;

import com.gyeot.domain.relation.entity.Invitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 나의 사람·보호자 초대를 저장하고 조회합니다. */
public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    // 링크 토큰으로 초대를 찾습니다. 토큰 원문 대신 해시를 조건으로 사용합니다.
    Optional<Invitation> findByTokenHash(String tokenHash);

    // 수락·거절 경합에서 같은 초대가 한 번만 처리되도록 행을 잠급니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    // 취소와 수락이 동시에 실행될 때 상태 변경을 직렬화합니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.id = :id and i.inviterId = :inviterId")
    Optional<Invitation> findByIdAndInviterIdForUpdate(
            @Param("id") Long id,
            @Param("inviterId") Long inviterId
    );
}
