package com.gyeot.domain.access.query;

import com.gyeot.domain.access.service.SharingCardReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SharingCountReader(공유 건수 조회 인터페이스)의 실제 구현입니다.
 * 공유 관리 목록과 같은 카드 판단(SharingCardReader.findSharingCards)으로 '공유 중' 카드를 셉니다. (F-30, #46)
 * 사용하는 쪽(안심카드 메인 C-00, 이후 B의 홈 F-34)은 해당 구현 클래스가 아니라 SharingCountReader 타입으로 선언합니다.
 *
 * - 이전에는 ACTIVE 권한이 있는 지정 수를 쿼리 한 번으로 셌습니다. 그 방식은 요청 상태와 종료 시각을 보지 않아,
 *   종료 시각이 지나고 스케줄러가 완료로 바꾸기 전(최대 1분) 등에 목록에는 없는 카드가 건수에는 잡혔습니다.
 *   목록과 건수를 같은 기준으로 맞추려고 카드 판단을 그대로 씁니다.
 * - 건수에 필요 없는 '다시 허용' 버튼 계산(관계, 상황 참여 조회)은 findSharingCards에서 하지 않습니다.
 */

@Service                         // Spring이 이 클래스를 객체로 만들어 관리함. SharingCountReader를 주입받는 곳에 이 객체가 들어감
@RequiredArgsConstructor         // 아래 final 필드(sharingCardReader)를 채우는 생성자 자동 생성
@Transactional(readOnly = true)  // 조회만 하므로 읽기 전용 트랜잭션으로 실행
public class SharingCountReaderImpl implements SharingCountReader {

    private final SharingCardReader sharingCardReader; // 공유 관리 목록과 같은 카드 판단

    @Override
    public long countActiveSharings(Long ownerUserId) {
        return sharingCardReader.findSharingCards(ownerUserId, null).size(); // 받는 사람으로 거르지 않음 (내 정보 전체). 직접 철회한 카드는 이미 빠짐
    }
}
