package com.gyeot.global.security.refresh.service;

public record RefreshRotationResult(IssuedRefreshToken token, boolean reused) {

    public static RefreshRotationResult rotated(IssuedRefreshToken token) {
        return new RefreshRotationResult(token, false);
    }

    public static RefreshRotationResult detectedReuse() {
        return new RefreshRotationResult(null, true);
    }
}
