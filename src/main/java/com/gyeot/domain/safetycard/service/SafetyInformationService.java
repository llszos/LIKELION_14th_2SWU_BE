package com.gyeot.domain.safetycard.service;

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
import com.gyeot.domain.safetycard.repository.SafetyInformationRepository;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 정보 주인이 자기 안심카드 항목을 조회·작성합니다. (F-02)
 * 항목 내용은 DB에 암호화돼 있지만, 엔티티를 꺼낼 때 Converter가 복호화해서 여기서는 평문으로 다룹니다.
 */
@Service                        // Spring이 객체로 만들어 Controller에 넣어 줌
@RequiredArgsConstructor        // 아래 final 필드(Repository, SharingCountReader)를 채우는 생성자 자동 생성
@Transactional(readOnly = true) // 기본은 읽기 전용, 저장하는 메서드만 따로 @Transactional
public class SafetyInformationService {

    private final SafetyInformationRepository safetyInformationRepository; // 안심카드 항목 조회·저장
    private final SharingCountReader sharingCountReader;                   // 공유 중 건수 (F-30 기준, access 영역)

    private static final Set<String> BLOOD_TYPES = Set.of("A", "B", "O", "AB"); // 혈액형 허용 값 (표시 "AB형"은 FE가 변환)

    /**
     * 안심카드 항목 1개를 작성합니다. (항목 작성)
     * 1개만 등록하는 항목(생년월일·혈액형·주소·출입 방법)을 또 작성하면 409 SAFETY_INFO_ALREADY_EXISTS
     */
    @Transactional // 저장하므로 읽기 전용이 아닌 트랜잭션
    public SafetyInformationResponse create(Long userId, SafetyInformationCreateRequest request) {
        InformationType type = request.informationType();

        // 1. 유형마다 다른 입력 규칙 검사 (공통 규칙은 요청 DTO의 @Valid에서 이미 검사)
        validateByType(type, request.title(), request.informationValue());

        // 2. 1개만 등록하는 유형이면 이미 있는지 확인. 있으면 FE는 기존 항목 수정으로 안내
        if (!type.isMultiple() && safetyInformationRepository.existsByOwnerIdAndInformationType(userId, type)) {
            throw new BusinessException(SafetyCardErrorCode.SAFETY_INFO_ALREADY_EXISTS);
        }

        // 3. 저장 (title·내용은 Converter가 암호화해서 DB에 넣음, 응답은 평문)
        SafetyInformation saved = safetyInformationRepository.save(
                SafetyInformation.create(userId, type, request.title(), request.informationValue()));

        return SafetyInformationResponse.from(saved);
    }

    /**
     * 안심카드 메인(C-00)에 필요한 분류별 최근 갱신일과 공유 중 건수를 돌려줍니다. (안심카드 메인 조회)
     */
    public SafetyCardSummaryResponse getSummary(Long userId) {

        // 1. 내 항목을 분류별로 묶어, 분류마다 가장 최근 수정 시각만 남김
        Map<InformationCategory, LocalDateTime> lastUpdatedByCategory = safetyInformationRepository
                .findAllByOwnerIdOrderByIdAsc(userId).stream()
                .collect(Collectors.toMap(
                        item -> item.getInformationType().getCategory(), // 키: 항목이 속한 분류
                        SafetyInformation::getUpdatedAt,                 // 값: 항목의 수정 시각
                        (a, b) -> a.isAfter(b) ? a : b));                // 같은 분류면 더 최근 시각을 남김

        // 2. 분류 4개를 고정 순서로 채움 (항목이 없는 분류는 null)
        List<CategorySummary> categories = Arrays.stream(InformationCategory.values())
                .map(category -> new CategorySummary(category, category.getDescription(), lastUpdatedByCategory.get(category)))
                .toList();

        // 3. 공유 중 건수는 홈(F-34)과 같은 기준을 쓰도록 SharingCountReader에 맡김
        return new SafetyCardSummaryResponse(categories, sharingCountReader.countActiveSharings(userId));
    }

    /**
     * 한 분류의 항목을 유형별로 묶어 돌려줍니다. (분류별 항목 조회)
     * 본인 항목만 조회하고, 등록한 항목이 없는 유형도 빈 배열로 포함합니다.
     */
    public CategoryItemsResponse getCategoryItems(Long userId, InformationCategory category) {

        // 1. 내 항목을 작성순으로 가져와 유형별로 묶음 (한 사람의 항목 수가 적어 전체를 가져와 나눔)
        Map<InformationType, List<SafetyInformation>> itemsByType = safetyInformationRepository
                .findAllByOwnerIdOrderByIdAsc(userId).stream()
                .filter(item -> item.getInformationType().getCategory() == category)
                .collect(Collectors.groupingBy(SafetyInformation::getInformationType));

        // 2. 분류 안 모든 유형을 선언 순서대로 돌며, 항목이 없으면 빈 배열로 채움
        List<TypeItems> types = Arrays.stream(InformationType.values())
                .filter(type -> type.getCategory() == category)
                .map(type -> toTypeItems(type, itemsByType.getOrDefault(type, List.of())))
                .toList();

        // 3. 분류 정보와 함께 응답으로 묶음
        return new CategoryItemsResponse(category, category.getDescription(), types);
    }

    // 유형마다 다른 입력 규칙. 어기면 공통 에러 400 INVALID_INPUT (요구사항 명세 F-02 입력 형식)
    private void validateByType(InformationType type, String title, String value) {
        // title: 이름이 있는 유형(질환 등)은 필수이고 공백만 있으면 안 됨
        if (type.isTitleRequired() && (title == null || title.isBlank())) {
            throw invalidInput();
        }
        // title: 이름이 없는 유형(생년월일·혈액형·주소·출입 방법)은 null이어야 함
        if (!type.isTitleRequired() && title != null) {
            throw invalidInput();
        }

        // 생년월일: YYYY-MM-DD 형식이고 실제 있는 날짜 (예: 2000-02-30 거부). 8자리 입력·표시 변환은 FE
        if (type == InformationType.BIRTH_DATE) {
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw invalidInput();
            }
            try {
                LocalDate.parse(value);
            } catch (DateTimeParseException exception) {
                throw invalidInput();
            }
        }

        // 혈액형: A / B / O / AB 중 하나
        if (type == InformationType.BLOOD_TYPE && !BLOOD_TYPES.contains(value)) {
            throw invalidInput();
        }
    }

    private BusinessException invalidInput() {
        return new BusinessException(CommonErrorCode.INVALID_INPUT);
    }

    // 유형 하나 → 응답의 types[] 한 칸 (유형 표시 정보 + 그 유형의 항목들)
    private TypeItems toTypeItems(InformationType type, List<SafetyInformation> items) {
        return new TypeItems(
                type,
                type.getDescription(),
                type.isSensitive(),
                type.isMultiple(),
                type.isTitleRequired(),
                items.stream().map(this::toItem).toList()); // groupingBy는 원래 순서를 유지해 작성순 그대로
    }

    // 항목 하나 → 응답의 items[] 한 칸 (title·내용은 Converter가 이미 복호화한 평문)
    private Item toItem(SafetyInformation item) {
        return new Item(item.getId(), item.getTitle(), item.getInformationValue(), item.getUpdatedAt());
    }
}
