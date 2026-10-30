package com.gyeot.domain.carerequest.enums;

public enum ChangeEventType {
    RESPONSE_CHANGED,
    TARGET_CHANGED,
    REQUEST_UPDATED,
    RECONFIRMATION_REQUESTED,
    RECONFIRMATION_ACCEPTED,
    RECONFIRMATION_REJECTED,
    RECONFIRMATION_INVALIDATED,
    REQUEST_STATUS_CHANGED,
    ASSIGNMENT_CLOSED; // TODO: 노션 ERD 반영 필요

    public boolean isReconfirmation() {
        return this == RECONFIRMATION_REQUESTED || this == RECONFIRMATION_ACCEPTED
                || this == RECONFIRMATION_REJECTED || this == RECONFIRMATION_INVALIDATED;
    }

    public boolean isRequestLevel() {
        return this == REQUEST_UPDATED || this == REQUEST_STATUS_CHANGED;
    }
}
