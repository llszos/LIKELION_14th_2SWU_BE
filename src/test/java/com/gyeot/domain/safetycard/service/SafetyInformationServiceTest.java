package com.gyeot.domain.safetycard.service;

import com.gyeot.domain.access.service.PermissionItemDeletionService;
import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.safetycard.dto.request.SafetyInformationCreateRequest;
import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse;
import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse.Item;
import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse.TypeItems;
import com.gyeot.domain.safetycard.dto.response.SafetyCardSummaryResponse;
import com.gyeot.domain.safetycard.dto.response.SafetyCardSummaryResponse.CategorySummary;
import com.gyeot.domain.safetycard.dto.response.SafetyInformationResponse;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.entity.InformationType;
import com.gyeot.domain.safetycard.entity.SafetyInformation;
import com.gyeot.domain.safetycard.exception.SafetyCardErrorCode;
import com.gyeot.domain.safetycard.repository.InformationTypeLastUpdated;
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DB 없이 Repository를 Mock으로 바꿔 응답 구성 규칙만 확인합니다. */
class SafetyInformationServiceTest {

    // 오늘 = 2026-11-13 (생년월일 미래 날짜 판단 기준을 고정)
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static final Long USER_ID = 1L;
    // 분류별 유형 목록 (InformationType 선언 순서, Service가 Repository에 넘기는 값과 같아야 함)
    private static final List<InformationType> HEALTH_TYPES = List.of(
            InformationType.DISEASE, InformationType.MEDICATION, InformationType.ALLERGY, InformationType.CAUTION);
    private static final List<InformationType> LIFE_TYPES = List.of(
            InformationType.ADDRESS, InformationType.ENTRY_METHOD, InformationType.PET);

    private SafetyInformationRepository safetyInformationRepository;
    private SharingCountReader sharingCountReader;
    private PermissionItemDeletionService permissionItemDeletionService;
    private SafetyInformationService service;

    @BeforeEach
    void setUp() {
        safetyInformationRepository = mock(SafetyInformationRepository.class);
        sharingCountReader = mock(SharingCountReader.class);
        permissionItemDeletionService = mock(PermissionItemDeletionService.class);
        service = new SafetyInformationService(safetyInformationRepository, sharingCountReader,
                permissionItemDeletionService, CLOCK); // Spring 없이 직접 만들어 테스트

        // 분류별 조회: 그 분류 유형의 항목만 DB에서 돌려준다고 가정 (건강 3개, 생활 1개)
        when(safetyInformationRepository.findAllByOwnerIdAndInformationTypeInOrderByIdAsc(USER_ID, HEALTH_TYPES)).thenReturn(List.of(
                item(11L, InformationType.DISEASE, "고혈압", "2024년 진단", LocalDateTime.of(2026, 9, 20, 10, 0)),
                item(12L, InformationType.MEDICATION, "혈압약", "아침 식후 1정", LocalDateTime.of(2026, 10, 1, 9, 30)),
                item(13L, InformationType.MEDICATION, "소화제", "필요할 때", LocalDateTime.of(2026, 9, 25, 8, 0))));
        when(safetyInformationRepository.findAllByOwnerIdAndInformationTypeInOrderByIdAsc(USER_ID, LIFE_TYPES)).thenReturn(List.of(
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
    void 분류별_조회는_그_분류_유형의_항목만_DB에서_꺼낸다() {
        service.getCategoryItems(USER_ID, InformationCategory.HEALTH);

        // 건강 유형으로만 조회하고, 내 항목 전체를 꺼내는 메서드는 쓰지 않음 (다른 분류 민감정보 복호화 방지)
        verify(safetyInformationRepository).findAllByOwnerIdAndInformationTypeInOrderByIdAsc(USER_ID, HEALTH_TYPES);
        verify(safetyInformationRepository, never()).findAllByOwnerIdOrderByIdAsc(any());
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
        // 유형별 최근 수정 시각만 돌려준다고 가정 (건강 유형 2개, 생활 유형 1개)
        when(safetyInformationRepository.findLastUpdatedByType(USER_ID)).thenReturn(List.of(
                new InformationTypeLastUpdated(InformationType.DISEASE, LocalDateTime.of(2026, 9, 20, 10, 0)),
                new InformationTypeLastUpdated(InformationType.MEDICATION, LocalDateTime.of(2026, 10, 1, 9, 30)),
                new InformationTypeLastUpdated(InformationType.ADDRESS, LocalDateTime.of(2026, 9, 25, 18, 0))));
        when(sharingCountReader.countActiveSharings(USER_ID)).thenReturn(2L);

        SafetyCardSummaryResponse response = service.getSummary(USER_ID);

        assertThat(response.categories()).extracting(CategorySummary::category).containsExactly(
                InformationCategory.BASIC, InformationCategory.HEALTH, InformationCategory.LIFE, InformationCategory.ADVANCE_MESSAGE);
        assertThat(response.categories()).extracting(CategorySummary::lastUpdatedAt).containsExactly(
                null,                                // 기본정보: 항목 없음
                LocalDateTime.of(2026, 10, 1, 9, 30), // 건강: 질환·복용약 중 더 최근(복용약)
                LocalDateTime.of(2026, 9, 25, 18, 0), // 생활: 주소
                null);                               // 미리 남기는 말: 항목 없음
        assertThat(response.sharingCount()).isEqualTo(2L); // SharingCountReader 값을 그대로 사용

        // 항목 내용(암호화된 값)을 담은 엔티티는 꺼내지 않음 (복호화 없음)
        verify(safetyInformationRepository, never()).findAllByOwnerIdOrderByIdAsc(any());
    }

    // ---- 항목 작성 ----

    @Test
    void 항목을_작성하면_저장하고_평문으로_응답한다() {
        // save가 호출되면 DB가 ID를 붙여 준 것처럼 만들어 돌려줌
        when(safetyInformationRepository.save(any(SafetyInformation.class))).thenAnswer(invocation -> {
            SafetyInformation saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 30L);
            return saved;
        });

        SafetyInformationResponse response = service.create(USER_ID,
                new SafetyInformationCreateRequest(InformationType.MEDICATION, "혈압약", "아침 식후 1정"));

        assertThat(response.informationId()).isEqualTo(30L);
        assertThat(response.typeName()).isEqualTo("복용약");
        assertThat(response.title()).isEqualTo("혈압약");
        assertThat(response.informationValue()).isEqualTo("아침 식후 1정");
        assertThat(response.sensitive()).isTrue(); // 복용약은 민감정보
    }

    @Test
    void 하나만_등록하는_항목이_이미_있으면_409다() {
        when(safetyInformationRepository.existsByOwnerIdAndInformationType(USER_ID, InformationType.ADDRESS)).thenReturn(true);

        assertError(new SafetyInformationCreateRequest(InformationType.ADDRESS, null, "서울시 성북구"),
                SafetyCardErrorCode.SAFETY_INFO_ALREADY_EXISTS);
        verify(safetyInformationRepository, never()).save(any()); // 저장하지 않음
    }

    @Test
    void 여러_개_등록하는_항목은_중복_확인을_하지_않는다() {
        when(safetyInformationRepository.save(any(SafetyInformation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(USER_ID, new SafetyInformationCreateRequest(InformationType.MEDICATION, "소화제", "필요할 때"));

        verify(safetyInformationRepository, never()).existsByOwnerIdAndInformationType(any(), any());
    }

    @Test
    void title이_필요한_유형에_title이_없거나_공백이면_400이다() {
        assertError(new SafetyInformationCreateRequest(InformationType.DISEASE, null, "2024년 진단"), CommonErrorCode.INVALID_INPUT);
        assertError(new SafetyInformationCreateRequest(InformationType.DISEASE, "  ", "2024년 진단"), CommonErrorCode.INVALID_INPUT);
    }

    @Test
    void title이_없는_유형에_title을_보내면_400이다() {
        assertError(new SafetyInformationCreateRequest(InformationType.ADDRESS, "우리 집", "서울시 성북구"), CommonErrorCode.INVALID_INPUT);
    }

    @Test
    void 생년월일은_YYYY_MM_DD_형식의_실제_날짜만_받는다() {
        assertError(new SafetyInformationCreateRequest(InformationType.BIRTH_DATE, null, "20001230"), CommonErrorCode.INVALID_INPUT);   // 8자리 그대로
        assertError(new SafetyInformationCreateRequest(InformationType.BIRTH_DATE, null, "2000-02-30"), CommonErrorCode.INVALID_INPUT); // 없는 날짜
    }

    @Test
    void 생년월일은_미래_날짜를_받지_않고_오늘까지는_받는다() {
        // 오늘은 CLOCK 기준 2026-11-13
        assertError(new SafetyInformationCreateRequest(InformationType.BIRTH_DATE, null, "2026-11-14"), CommonErrorCode.INVALID_INPUT); // 내일

        when(safetyInformationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0)); // 저장 결과를 그대로 돌려줌
        SafetyInformationResponse response = service.create(USER_ID,
                new SafetyInformationCreateRequest(InformationType.BIRTH_DATE, null, "2026-11-13"));       // 오늘

        assertThat(response.informationValue()).isEqualTo("2026-11-13");
    }

    @Test
    void 혈액형은_A_B_O_AB만_받는다() {
        assertError(new SafetyInformationCreateRequest(InformationType.BLOOD_TYPE, null, "AB형"), CommonErrorCode.INVALID_INPUT); // 표시용 문구
        assertError(new SafetyInformationCreateRequest(InformationType.BLOOD_TYPE, null, "C"), CommonErrorCode.INVALID_INPUT);
    }

    // 작성 요청이 주어진 에러 코드의 BusinessException으로 거부되는지 확인
    private void assertError(SafetyInformationCreateRequest request, ErrorCode errorCode) {
        assertThatThrownBy(() -> service.create(USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }

    private SafetyInformation item(Long id, InformationType type, String title, String value, LocalDateTime updatedAt) {
        SafetyInformation information = SafetyInformation.create(USER_ID, type, title, value);
        ReflectionTestUtils.setField(information, "id", id);               // DB 없이 테스트하므로 ID를 직접 넣음
        ReflectionTestUtils.setField(information, "updatedAt", updatedAt); // 원래는 저장할 때 자동으로 채워지는 값
        return information;
    }
}
