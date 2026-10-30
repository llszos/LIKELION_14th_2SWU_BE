package com.gyeot.domain.carerequest.entity;

/**
 * 체크리스트 항목 (F-12, SHOULD)
 * care_requests.checklist JSON 컬럼에 [{"text": "..."}] 형태로 저장된다.
 * 작성 참고용이며 단계별 완료 기록은 포함하지 않는다.
 */
public record ChecklistItem(String text) {
}