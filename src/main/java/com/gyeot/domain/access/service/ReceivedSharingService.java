package com.gyeot.domain.access.service;

import com.gyeot.domain.access.dto.response.ReceivedSharingResponse;
import com.gyeot.domain.access.dto.response.ReceivedSharingResponse.ReceivedSharing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 이 사람이 나에게 공유 중인 정보를 항목 이름만 조회합니다. (노션 D 파트 API 명세 '받은 정보 조회', R-02-01, F-30, #46)
 *
 * - 정보 주인은 화면의 '이 사람'(userId), 받는 사람은 로그인한 나입니다. 공유 관리 카드 판단(SharingCardReader)을 그대로 씁니다.
 * - 지금 볼 수 있는 공유만 내려줍니다. (SharingCardReader.findViewableCards)
 *   공유 중 카드 중에서도 정보 주인 → 나 관계가 유효하고 내가 그 상황에 참여 중인 것만 남깁니다. (전달받은 정보 조회 #45와 같은 기준)
 *   정보 주인이 직접 철회한 카드는 나에게는 볼 수 없는 정보라 뺍니다.
 * - 항목 내용을 내려주지 않으므로 정보 주인의 접근 이력(VIEWED)에 남기지 않습니다. 내용은 '전달받은 정보 조회'에서 봅니다.
 * - 급한 요청은 이번 범위에서 빠집니다. (카드 판단과 같은 범위)
 */
@Service                         // Spring이 객체로 만들어 컨트롤러에 주입
@RequiredArgsConstructor         // 아래 final 필드를 채우는 생성자 자동 생성
@Transactional(readOnly = true)  // 조회만 하므로 읽기 전용 (이력을 남기지 않음)
public class ReceivedSharingService {

    private final SharingCardReader sharingCardReader; // 공유 카드 판단 (목록, 건수와 같은 기준)

    /**
     * @param viewerUserId 로그인한 사용자 (받는 사람)
     * @param ownerUserId  화면의 '이 사람' (정보 주인)
     * @return 받은 공유 목록 (접근 종료 시각 빠른 순). 없으면 빈 목록
     */
    public ReceivedSharingResponse getReceived(Long viewerUserId, Long ownerUserId) {
        List<ReceivedSharing> items = sharingCardReader.findViewableCards(ownerUserId, viewerUserId).stream() // 나에게 지금 보이는 카드만
                .map(card -> new ReceivedSharing(card.requestId(), card.helpType(), card.startsAt(),
                        card.accessUntil(), card.items()))              // 카드 순서(자동 회수 시각 빠른 순)를 그대로 유지
                .toList();
        return new ReceivedSharingResponse(items);
    }
}
