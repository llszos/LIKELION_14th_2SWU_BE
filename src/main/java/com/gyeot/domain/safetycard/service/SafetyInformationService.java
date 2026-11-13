package com.gyeot.domain.safetycard.service;

import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.safetycard.dto.request.SafetyInformationCreateRequest;
import com.gyeot.domain.safetycard.dto.request.SafetyInformationUpdateRequest;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 정보 주인이 자기 안심카드 항목을 조회, 작성, 수정합니다. (F-02)
 * 항목 내용은 DB에 암호화돼 있지만, 엔티티를 꺼낼 때 Converter가 복호화해서 여기서는 평문으로 다룹니다.
 */
@Service                        // Spring이 객체로 만들어 Controller에 넣어 줌
@RequiredArgsConstructor        // 아래 final 필드(Repository, SharingCountReader, Clock)를 채우는 생성자 자동 생성
@Transactional(readOnly = true) // 기본은 읽기 전용, 저장하는 메서드만 따로 @Transactional
public class SafetyInformationService {

    private final SafetyInformationRepository safetyInformationRepository; // 안심카드 항목 조회·저장
    private final SharingCountReader sharingCountReader;                   // 공유 중 건수 (F-30 기준, access 영역)
    private final Clock clock;                                             // 오늘 날짜 기준 (서비스 시간대 Asia/Seoul, 테스트에서 고정 가능)

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
     * 본인 안심카드 항목 1개의 이름과 내용을 수정합니다. (항목 수정)
     *
     * - 없는 항목이거나 다른 사람의 항목이면 404 SAFETY_INFO_NOT_FOUND
     * - 입력 규칙은 작성과 같고, 유형은 바꿀 수 없어 기존 항목의 유형 기준으로 검사합니다.
     * - 수정하면 updated_at이 바뀌고, 담당자 화면(전달받은 정보 조회)에는 '갱신됨'으로 표시됩니다. (updated_at > 권한 granted_at)
     * - 공유 중인 항목이어도 권한은 그대로 두고, 담당자는 수정된 내용을 봅니다.
     */
    @Transactional // 수정하므로 읽기 전용이 아닌 트랜잭션
    public SafetyInformationResponse update(Long userId, Long informationId, SafetyInformationUpdateRequest request) {

        // 1. 본인 항목인지 확인 (없거나 남의 항목이면 404, 남의 항목이 있다는 것도 알려 주지 않음)
        SafetyInformation information = getOwnedItem(userId, informationId);

        // 2. 기존 항목의 유형으로 입력 규칙 검사 (작성과 같은 규칙)
        validateByType(information.getInformationType(), request.title(), request.informationValue());

        // 3. 변경 감지로 수정 (title, 내용은 Converter가 다시 암호화해서 저장)
        information.update(request.title(), request.informationValue());

        // 4. updated_at은 DB에 반영(flush)될 때 채워지므로, 먼저 반영해야 응답에 수정 후 시각이 담김
        //    값이 그대로면 변경이 없어 updated_at도 바뀌지 않음 ('갱신됨' 표시 안 함)
        safetyInformationRepository.flush();

        return SafetyInformationResponse.from(information);
    }

    /**
     * 안심카드 메인(C-00)에 필요한 분류별 최근 갱신일과 공유 중 건수를 돌려줍니다. (안심카드 메인 조회)
     */
    public SafetyCardSummaryResponse getSummary(Long userId) {

        // 1. 유형별 최근 수정 시각만 조회해(항목 내용은 읽지 않아 복호화 없음), 분류마다 가장 최근 시각만 남김
        Map<InformationCategory, LocalDateTime> lastUpdatedByCategory = safetyInformationRepository
                .findLastUpdatedByType(userId).stream()
                .collect(Collectors.toMap(
                        row -> row.informationType().getCategory(), // 키: 유형이 속한 분류
                        InformationTypeLastUpdated::updatedAt,      // 값: 그 유형의 최근 수정 시각
                        (a, b) -> a.isAfter(b) ? a : b));           // 같은 분류의 유형이 여러 개면 더 최근 시각을 남김

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

        // 1. 이 분류에 속한 유형 목록 (InformationType 선언 순서)
        List<InformationType> categoryTypes = Arrays.stream(InformationType.values())
                .filter(type -> type.getCategory() == category)
                .toList();

        // 2. 이 분류 유형의 내 항목만 작성순으로 조회해 유형별로 묶음 (다른 분류 항목은 꺼내지 않아 복호화하지 않음)
        Map<InformationType, List<SafetyInformation>> itemsByType = safetyInformationRepository
                .findAllByOwnerIdAndInformationTypeInOrderByIdAsc(userId, categoryTypes).stream()
                .collect(Collectors.groupingBy(SafetyInformation::getInformationType));

        // 3. 분류 안 모든 유형을 돌며, 항목이 없으면 빈 배열로 채움
        List<TypeItems> types = categoryTypes.stream()
                .map(type -> toTypeItems(type, itemsByType.getOrDefault(type, List.of())))
                .toList();

        // 4. 분류 정보와 함께 응답으로 묶음
        return new CategoryItemsResponse(category, category.getDescription(), types);
    }

    /**
     * 본인 항목을 찾습니다. 없는 항목과 다른 사람의 항목을 구분하지 않고 404 SAFETY_INFO_NOT_FOUND로 거부합니다.
     *
     * 구분해서 알려 주면 다른 사람 항목 ID가 있는지 확인하는 데 쓰일 수 있어 같은 응답으로 묶었습니다. (항목 수정, 삭제)
     */
    private SafetyInformation getOwnedItem(Long userId, Long informationId) {
        return safetyInformationRepository.findById(informationId)
                .filter(information -> information.getOwnerId().equals(userId)) // 남의 항목은 없는 것으로 취급
                .orElseThrow(() -> new BusinessException(SafetyCardErrorCode.SAFETY_INFO_NOT_FOUND));
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
            LocalDate birthDate;
            try {
                birthDate = LocalDate.parse(value);
            } catch (DateTimeParseException exception) {
                throw invalidInput();
            }

            // 미래 날짜는 생년월일이 될 수 없음. 오늘은 허용 (FE도 같은 규칙으로 막지만, API 직접 호출에 대비해 서버에서도 확인)
            if (birthDate.isAfter(LocalDate.now(clock))) {
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
