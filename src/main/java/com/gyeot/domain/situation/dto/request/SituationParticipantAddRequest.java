package com.gyeot.domain.situation.dto.request;

import jakarta.validation.constraints.NotNull;

public record SituationParticipantAddRequest(@NotNull Long userId) {}
