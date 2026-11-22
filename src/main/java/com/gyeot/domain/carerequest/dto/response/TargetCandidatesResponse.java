package com.gyeot.domain.carerequest.dto.response;

import java.util.List;

/**
 * 대상 후보 조회 응답 (GET /api/care-requests/target-candidates)
 * items: 서로의 보호자 먼저, 그다음 나의 사람. 없으면 빈 배열
 * 목록은 "data": { "items": [...] }로 감쌉니다. (API 공통 규칙 3, 담당자 없음 안내 목록과 같은 형태)
 */
public record TargetCandidatesResponse(List<CandidateView> items) {

    /**
     * 후보 한 명
     * - name: 탈퇴했거나 사용자 정보가 없으면 "탈퇴한 사용자"
     * - isGuardian: 서로의 보호자면 true (보호자 배지), 나의 사람이면 false
     */
    public record CandidateView(Long userId, String name, boolean isGuardian) {
    }
}
