package com.gyeot.domain.user.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void acceptsValidSignupRequest() {
        SignupRequest request =
                new SignupRequest("user@example.com", "password", "사용자");

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsBlankSignupFields() {
        SignupRequest request = new SignupRequest(" ", " ", " ");

        assertHasViolation(request, "email");
        assertHasViolation(request, "password");
        assertHasViolation(request, "name");
    }

    @Test
    void rejectsNullSignupFields() {
        SignupRequest request = new SignupRequest(null, null, null);

        assertHasViolation(request, "email");
        assertHasViolation(request, "password");
        assertHasViolation(request, "name");
    }

    @Test
    void rejectsInvalidEmailFormat() {
        assertHasViolation(
                new SignupRequest("invalid-email", "password", "사용자"),
                "email"
        );

        assertHasViolation(
                new LoginRequest("invalid-email", "password"),
                "email"
        );
    }

    @Test
    void rejectsNameLongerThanFiftyCharacters() {
        SignupRequest request =
                new SignupRequest(
                        "user@example.com", "password", "가".repeat(51));

        assertHasViolation(request, "name");
    }

    @Test
    void acceptsValidLoginRequest() {
        LoginRequest request =
                new LoginRequest("user@example.com", "password");

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsBlankAndNullLoginFields() {
        LoginRequest blankRequest = new LoginRequest(" ", " ");
        LoginRequest nullRequest = new LoginRequest(null, null);

        assertHasViolation(blankRequest, "email");
        assertHasViolation(blankRequest, "password");
        assertHasViolation(nullRequest, "email");
        assertHasViolation(nullRequest, "password");
    }

    @Test
    void excludesPasswordsFromToString() {
        String password = "secret-test-password";

        assertFalse(new SignupRequest(
                "user@example.com", password, "사용자"
        ).toString().contains(password));

        assertFalse(new LoginRequest(
                "user@example.com", password
        ).toString().contains(password));
    }

    private void assertHasViolation(Object request, String field) {
        assertTrue(
                validator.validate(request).stream()
                        .anyMatch(violation ->
                                field.equals(
                                        violation.getPropertyPath().toString())),
                field + " 필드의 검증 오류가 있어야 합니다."
        );
    }
}
