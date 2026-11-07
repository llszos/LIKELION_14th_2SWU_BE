package com.gyeot.domain.carerequest.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * changed_fields에는 starts_at, ends_at, place, information_ids 네 값만 들어갑니다.
 * List<String>으로 두면 오타를 막기 힘들기 때문에 Enum으로 만들고, @JsonValue를 이용해 Enum을 JSON으로 바꿔줍니다.
 * Enum 및 상태값 저장 방식은 ERD 및 데이터 모델 문서 7-2 참조
 */
public enum ChangeableField {
    STARTS_AT("starts_at"),
    ENDS_AT("ends_at"),
    PLACE("place"),
    INFORMATION_IDS("information_ids");

    private final String fieldName;

    ChangeableField(String fieldName) {
        this.fieldName = fieldName;
    }

    @JsonValue
    public String getFieldName() {
        return fieldName;
    }
}
