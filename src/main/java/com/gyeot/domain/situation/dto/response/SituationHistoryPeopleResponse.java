package com.gyeot.domain.situation.dto.response;

import java.util.List;

public record SituationHistoryPeopleResponse(List<Item> items) {
    public record Item(Long userId, String name) {}
}
