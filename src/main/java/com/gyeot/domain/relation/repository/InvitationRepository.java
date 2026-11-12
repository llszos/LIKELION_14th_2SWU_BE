package com.gyeot.domain.relation.repository;

import com.gyeot.domain.relation.entity.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 나의 사람·보호자 초대를 저장하고 조회합니다. */
public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    // 발신자 본인의 초대만 취소할 수 있도록 소유자까지 조건에 포함합니다.
    Optional<Invitation> findByIdAndInviterId(Long id, Long inviterId);
}