package com.gyeot.domain.relation.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmergencyContactOrderChangeRequestValidationTest {

    private static Validator validator;
    private static AutoCloseable validatorFactory;

    @BeforeAll
    static void setUpValidator() {
        var factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        validatorFactory = factory::close;
    }

    @AfterAll
    static void closeValidatorFactory() throws Exception {
        validatorFactory.close();
    }

    @Test
    void missingGuardianUserIdsUsesCommonInvalidInputMessage() {
        var request = new EmergencyContactOrderChangeRequest(null, List.of());

        assertValidationMessage(request, "guardianUserIds");
    }

    @Test
    void missingEmergencyContactUserIdsUsesCommonInvalidInputMessage() {
        var request = new EmergencyContactOrderChangeRequest(List.of(), null);

        assertValidationMessage(request, "emergencyContactUserIds");
    }

    @Test
    void nullGuardianUserIdUsesCommonInvalidInputMessage() {
        var request = new EmergencyContactOrderChangeRequest(java.util.Arrays.asList(1L, null), List.of());

        assertValidationMessage(request, "guardianUserIds[1].<list element>");
    }

    @Test
    void nullEmergencyContactUserIdUsesCommonInvalidInputMessage() {
        var request = new EmergencyContactOrderChangeRequest(List.of(), java.util.Arrays.asList(2L, null));

        assertValidationMessage(request, "emergencyContactUserIds[1].<list element>");
    }

    private void assertValidationMessage(EmergencyContactOrderChangeRequest request, String propertyPath) {
        var violations = validator.validate(request);

        assertEquals(1, violations.size());
        var violation = violations.iterator().next();
        assertEquals(propertyPath, violation.getPropertyPath().toString());
        assertEquals("입력값이 올바르지 않습니다.", violation.getMessage());
        assertTrue(violation.getInvalidValue() == null);
    }
}
