package com.gyeot.domain.safetycard.service;

import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse;
import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse.Item;
import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse.TypeItems;
import com.gyeot.domain.safetycard.dto.response.SafetyCardSummaryResponse;
import com.gyeot.domain.safetycard.dto.response.SafetyCardSummaryResponse.CategorySummary;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DB 없이 Repository를 Mock으로 바꿔 응답 구성 규칙만 확인합니다. */
class SafetyInformationServiceTest {

    private static final Long USER_ID = 1L;

    private SafetyInformationRepository safetyInformationRepository;
    private SharingCountReader sharingCountReader;
    private SafetyInformationService service;

    @BeforeEach
    void setUp() {
        safetyInformationRepository = mock(SafetyInformationRepository.class);
        sharingCountReader = mock(SharingCountReader.class);
        service = new SafetyInformationService(safetyInformationRepository, sharingCountReader); // Spring 없이 직접 만들어 테스트

        // 내 항목: 건강 3개(질환 1, 복용약 2), 생활 1개. 수정 시각은 메인 조회 테스트에서 사용
        when(safetyInformationRepository.findAllByOwnerIdOrderByIdAsc(USER_ID)).thenReturn(List.of(
                item(11L, InformationType.DISEASE, "고혈압", "2024년 진단", LocalDateTime.of(2026, 9, 20, 10, 0)),
                item(12L, InformationType.MEDICATION, "혈압약", "아침 식후 1정", LocalDateTime.of(2026, 10, 1, 9, 30)),
                item(13L, InformationType.MEDICATION, "소화제", "필요할 때", LocalDateTime.of(2026, 9, 25, 8, 0)),
                item(20L, InformationType.ADDRESS, null, "서울시 노원구", LocalDateTime.of(2026, 9, 25, 18, 0))));
    }

    @Test
    void 분류_안_모든_유형을_선언_순서대로_내려준다() {
        CategoryItemsResponse response = service.getCategoryItems(USER_ID, InformationCategory.HEALTH);

        assertThat(response.category()).isEqualTo(InformationCategory.HEALTH);
        assertThat(response.categoryName()).isEqualTo("건강");
        // 건강 유형 4개가 InformationType 선언 순서대로 (알레르기·주의사항은 항목이 없어도 포함)
        assertThat(response.types()).extracting(TypeItems::informationType).containsExactly(
                InformationType.DISEASE, InformationType.MEDICATION, InformationType.ALLERGY, InformationType.CAUTION);
    }

    @Test
    void 항목은_유형별로_작성순이고_없는_유형은_빈_배열이다() {
        List<TypeItems> types = service.getCategoryItems(USER_ID, InformationCategory.HEALTH).types();

        assertThat(types.get(1).items()).extracting(Item::informationId).containsExactly(12L, 13L); // 복용약 2개
        assertThat(types.get(2).items()).isEmpty();                                                // 알레르기 없음
    }

    @Test
    void 다른_분류의_항목은_섞이지_않는다() {
        List<TypeItems> types = service.getCategoryItems(USER_ID, InformationCategory.HEALTH).types();

        assertThat(types).flatExtracting(TypeItems::items).extracting(Item::informationId)
                .doesNotContain(20L); // 생활(주소) 항목
    }

    @Test
    void 유형의_표시_정보를_함께_내려준다() {
        TypeItems address = service.getCategoryItems(USER_ID, InformationCategory.LIFE).types().get(0);

        assertThat(address.informationType()).isEqualTo(InformationType.ADDRESS);
        assertThat(address.typeName()).isEqualTo("주소");
        assertThat(address.sensitive()).isTrue();
        assertThat(address.multiple()).isFalse();      // 1개만 등록
        assertThat(address.titleRequired()).isFalse(); // title 없음
        assertThat(address.items().get(0).title()).isNull();
    }

    @Test
    void 메인은_분류_4개를_고정_순서로_내려주고_분류마다_가장_최근_수정_시각을_쓴다() {
        when(sharingCountReader.countActiveSharings(USER_ID)).thenReturn(2L);

        SafetyCardSummaryResponse response = service.getSummary(USER_ID);

        assertThat(response.categories()).extracting(CategorySummary::category).containsExactly(
                InformationCategory.BASIC, InformationCategory.HEALTH, InformationCategory.LIFE, InformationCategory.ADVANCE_MESSAGE);
        assertThat(response.categories()).extracting(CategorySummary::lastUpdatedAt).containsExactly(
                null,                                // 기본정보: 항목 없음
                LocalDateTime.of(2026, 10, 1, 9, 30), // 건강: 3개 중 가장 최근(혈압약)
                LocalDateTime.of(2026, 9, 25, 18, 0), // 생활: 주소
                null);                               // 미리 남기는 말: 항목 없음
        assertThat(response.sharingCount()).isEqualTo(2L); // SharingCountReader 값을 그대로 사용
    }

    private SafetyInformation item(Long id, InformationType type, String title, String value, LocalDateTime updatedAt) {
        SafetyInformation information = SafetyInformation.create(USER_ID, type, title, value);
        ReflectionTestUtils.setField(information, "id", id);               // DB 없이 테스트하므로 ID를 직접 넣음
        ReflectionTestUtils.setField(information, "updatedAt", updatedAt); // 원래는 저장할 때 자동으로 채워지는 값
        return information;
    }
}
