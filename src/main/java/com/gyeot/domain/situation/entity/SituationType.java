package com.gyeot.domain.situation.entity;

/** FE와 합의한 B 상황 종류 코드입니다. C의 HelpType과 별개입니다. DB에는 문자열로 저장합니다. */
public enum SituationType {
    HOSPITAL_VISIT, RECOVERY, ABSENCE, OTHER;

    /** 앞뒤 공백 제거 후 길이를 검사합니다. 기타 외에는 빈 문자열을 포함한 입력을 거부합니다. */
    public static boolean isCustomTextValid(String code, String custom) {
        if (OTHER.name().equals(code)) {
            return custom != null && !custom.isBlank() && custom.strip().length() <= 15;
        }
        return custom == null;
    }
}
