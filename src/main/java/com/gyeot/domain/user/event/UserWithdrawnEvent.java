package com.gyeot.domain.user.event;

/** 사용자가 탈퇴했을 때 전달하는 이벤트입니다. */
public record UserWithdrawnEvent(Long userId) {
}