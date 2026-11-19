package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.SharingListResponse;
import com.gyeot.domain.access.dto.response.SharingListResponse.Receiver;
import com.gyeot.domain.access.dto.response.SharingListResponse.SharingCard;
import com.gyeot.domain.user.query.UserProfileQuery;
import com.gyeot.domain.user.query.UserProfileQuery.UserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 내 정보를 지금 볼 수 있는 사람을 (요청, 담당자) 카드 단위로 조회합니다. (노션 D 파트 API 명세 '공유 중 목록 조회', C-06, R-02-01, F-30, #46)
 *
 * - 카드 판단(상태, 다시 허용 버튼, 공개 항목, 순서)은 SharingCardReader.findCards를 그대로 씁니다. (공유 중 건수, 받은 정보와 같은 기준)
 * - 이 서비스는 받는 사람 이름만 채웁니다. 이름은 A의 사용자 프로필 일괄 조회(UserProfileQuery, #109)로 카드 전체를 한 번에 가져옵니다.
 * - 받는 사람이 탈퇴했으면 이름을 "탈퇴한 사용자"로 내려줍니다. (A는 탈퇴 사용자의 이름을 비워서 돌려줌)
 *   사용자 행이 아예 없는 경우도 같은 표시를 씁니다. 지정에 남은 사용자 ID라 정상 흐름에서는 없지만, 응답 전체가 실패하지 않게 합니다.
 * - 조회만 하므로 접근 이력을 남기지 않습니다. (항목 내용을 내려주지 않음)
 * - 급한 요청은 이번 범위에서 빠집니다. (카드 판단과 같은 범위)
 */
@Service                         // Spring이 객체로 만들어 컨트롤러에 주입
@RequiredArgsConstructor         // 아래 final 필드를 채우는 생성자 자동 생성
@Transactional(readOnly = true)  // 조회만 하므로 읽기 전용
public class SharingListService {

    /** 받는 사람이 탈퇴했거나 찾을 수 없을 때 카드에 표시할 이름 (명세) */
    static final String WITHDRAWN_NAME = "탈퇴한 사용자";

    private final SharingCardReader sharingCardReader; // 공유 카드 판단 (상태, 다시 허용, 공개 항목, 순서)
    private final UserProfileQuery userProfileQuery;   // A: 받는 사람 이름과 탈퇴 여부 (일괄 조회)

    /**
     * @param ownerUserId    로그인한 사용자 (정보 주인)
     * @param receiverUserId 받는 사람으로 거를 때의 사용자 ID. null이면 전체 (C-06 전체, R-02-01은 한 사람)
     * @return 공유 카드 목록 (자동 회수 시각 빠른 순). 없으면 빈 목록
     */
    public SharingListResponse getSharings(Long ownerUserId, Long receiverUserId) {
        List<SharingCardView> cards = sharingCardReader.findCards(ownerUserId, receiverUserId);

        if (cards.isEmpty()) {
            return new SharingListResponse(List.of()); // 카드가 없으면 사용자 조회도 하지 않음
        }

        // 카드마다 조회하지 않고 받는 사람 ID를 모아 한 번에 조회 (중복과 null은 A 쪽에서 정리)
        Map<Long, UserProfile> profiles = userProfileQuery.findProfilesByUserIds(
                cards.stream().map(SharingCardView::receiverUserId).toList());

        List<SharingCard> items = cards.stream()             // 카드 순서(자동 회수 시각 빠른 순)를 그대로 유지
                .map(card -> new SharingCard(
                        card.assignmentId(),
                        card.requestId(),
                        new Receiver(card.receiverUserId(), displayName(profiles.get(card.receiverUserId()))),
                        card.helpType(),
                        card.startsAt(),
                        card.accessUntil(),
                        card.status(),
                        card.canReallow(),
                        card.items()))
                .toList();
        return new SharingListResponse(items);
    }

    /**
     * 카드 상단에 표시할 받는 사람 이름을 정합니다.
     *
     * @param profile A에서 받은 프로필. 사용자 행이 없으면 null
     * @return 탈퇴했거나 찾을 수 없으면 "탈퇴한 사용자", 아니면 이름
     */
    private static String displayName(UserProfile profile) {
        if (profile == null || profile.withdrawn()) {
            return WITHDRAWN_NAME;
        }
        return profile.name();
    }
}
