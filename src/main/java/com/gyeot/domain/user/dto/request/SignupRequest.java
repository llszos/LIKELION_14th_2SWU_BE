package com.gyeot.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 이메일·비밀번호 기반 회원가입 요청입니다.
 * 비밀번호는 서비스에서 해시한 뒤 저장합니다.
 */
public record SignupRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이어야 합니다.")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String password,

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
        String name

) {
    // record 기본 toString()으로 비밀번호가 노출되지 않도록 합니다.
    @Override
    public String toString() {
        return "SignupRequest[password=REDACTED]";
    }
}
