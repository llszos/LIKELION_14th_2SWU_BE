package com.gyeot.domain.safetycard.policy;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.entity.InformationType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.gyeot.domain.safetycard.entity.InformationType.ADDRESS;
import static com.gyeot.domain.safetycard.entity.InformationType.ALLERGY;
import static com.gyeot.domain.safetycard.entity.InformationType.BIRTH_DATE;
import static com.gyeot.domain.safetycard.entity.InformationType.BLOOD_TYPE;
import static com.gyeot.domain.safetycard.entity.InformationType.CAUTION;
import static com.gyeot.domain.safetycard.entity.InformationType.DISEASE;
import static com.gyeot.domain.safetycard.entity.InformationType.ENTRY_METHOD;
import static com.gyeot.domain.safetycard.entity.InformationType.MEDICATION;
import static com.gyeot.domain.safetycard.entity.InformationType.PET;

/**
 * 도움 종류별로 처음부터 선택해 둘 안심카드 항목 유형입니다. (F-03 기본 공개 항목, 기능명세서 기준)
 * 관계 유형이 아니라 도움 종류 기준이고, DB에 저장하지 않고 코드로 관리합니다. (ERD 4.11)
 * 공개 항목 선택지 조회에서 이 유형에 속한 내 항목을 모두 selected=true로 내려줍니다.
 */
public final class DefaultSharingTypes {

    // 도움 종류 → 기본 공개 항목 유형
    private static final Map<HelpType, Set<InformationType>> DEFAULTS = new EnumMap<>(HelpType.class);

    static {
        // 식사와 장보기는 기본값이 같음 (F-03 기능명세서: '장보기: 식사와 같다')
        Set<InformationType> mealOrGrocery = EnumSet.of(DISEASE, MEDICATION, ALLERGY, CAUTION, ADDRESS, ENTRY_METHOD);

        DEFAULTS.put(HelpType.HOSPITAL, EnumSet.of(MEDICATION, ALLERGY));          // 병원 동행
        DEFAULTS.put(HelpType.MEAL, mealOrGrocery);                                // 식사
        DEFAULTS.put(HelpType.GROCERY, mealOrGrocery);                             // 장보기 (식사와 같음)
        DEFAULTS.put(HelpType.PET_CARE, EnumSet.of(ADDRESS, ENTRY_METHOD, PET));   // 반려동물 돌봄
        DEFAULTS.put(HelpType.CHECK_IN, EnumSet.of(BIRTH_DATE, BLOOD_TYPE));       // 연락·안부
        DEFAULTS.put(HelpType.OTHER, EnumSet.noneOf(InformationType.class));       // 기타 (직접 선택)
    }

    private DefaultSharingTypes() {
        // 값만 꺼내 쓰는 클래스라 객체를 만들지 않음
    }

    /**
     * 도움 종류의 기본 공개 항목 유형을 돌려줍니다. 돌려받은 Set은 수정할 수 없습니다.
     * 매핑이 없는 도움 종류가 새로 생기면 빈 Set을 돌려줍니다. (아무것도 미리 선택하지 않음)
     * 사용 예: DefaultSharingTypes.of(HelpType.HOSPITAL) → [MEDICATION, ALLERGY]
     */
    public static Set<InformationType> of(HelpType helpType) {
        // 받은 쪽에서 수정해도 기본값이 바뀌지 않도록 수정 불가 Set으로 돌려줌
        return Collections.unmodifiableSet(DEFAULTS.getOrDefault(helpType, EnumSet.noneOf(InformationType.class)));
    }
}
