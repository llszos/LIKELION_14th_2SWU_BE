package com.gyeot.domain.carerequest.enums;

public enum CareRequestStatus {
    REQUESTED, ACCEPTED, IN_PROGRESS, COMPLETED, CANCELED, EXPIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELED || this == EXPIRED;
    }
}
