package com.gyeot.domain.situation.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring Context와 DB 없이 Jakarta Validation 입력 계약만 검증합니다. */
class SituationCreateRequestValidationTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 9, 0);
    private static final LocalDateTime END = START.plusHours(1);
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
    void acceptsValidInput() {
        assertTrue(validator.validate(request("상황 종류", null, "회복 지원", List.of(12L, 13L), START, END)).isEmpty());
    }

    @Test
    void acceptsEmptyParticipants() {
        assertTrue(validator.validate(request("상황 종류", null, "회복 지원", List.of(), START, END)).isEmpty());
    }

    @Test
    void rejectsMissingRequiredFieldsWithoutAnExtraPeriodViolation() {
        assertEquals(Set.of("situationType", "name", "participantIds", "startAt", "endAt"),
                invalidFields(request(null, null, null, null, null, null)));
    }

    @Test
    void rejectsBlankSituationType() {
        for (String type : List.of("", " ", "\t\n")) {
            assertEquals(Set.of("situationType"), invalidFields(request(type, null, "회복 지원", List.of(), START, END)));
        }
    }

    @Test
    void checksTypeStorageLengthWithoutApprovingAnyCode() {
        assertTrue(validator.validate(request("x".repeat(30), null, "이름", List.of(), START, END)).isEmpty());
        assertEquals(Set.of("situationType"),
                invalidFields(request("x".repeat(31), null, "이름", List.of(), START, END)));
    }

    @Test
    void rejectsNullParticipantElementBeforeBusinessValidation() {
        var violations = validator.validate(request("미확정 종류", null, "이름",
                java.util.Arrays.asList(20L, null), START, END));
        assertEquals(1, violations.size());
        assertEquals("participantIds[1].<list element>", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    void rejectsBlankName() {
        for (String name : List.of("", " ", "\t\n")) {
            assertEquals(Set.of("name"), invalidFields(request("상황 종류", null, name, List.of(), START, END)));
        }
    }

    @Test
    void acceptsNameAtMaximumLength() {
        assertTrue(validator.validate(request("상황 종류", null, "가".repeat(150), List.of(), START, END)).isEmpty());
    }

    @Test
    void rejectsNameOverMaximumLength() {
        assertEquals(Set.of("name"), invalidFields(request("상황 종류", null, "가".repeat(151), List.of(), START, END)));
    }

    @Test
    void rejectsReversedPeriod() {
        assertEquals(Set.of("periodValid"), invalidFields(request("상황 종류", null, "회복 지원", List.of(), END, START)));
    }

    @Test
    void rejectsEqualStartAndEnd() {
        assertEquals(Set.of("periodValid"), invalidFields(request("상황 종류", null, "회복 지원", List.of(), START, START)));
    }

    @Test
    void acceptsPeriodAcrossMidnight() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 23, 59);
        assertTrue(validator.validate(request("상황 종류", null, "회복 지원", List.of(), start, start.plusMinutes(1))).isEmpty());
    }

    @Test
    void reportsOnlyTheMissingTimeField() {
        assertEquals(Set.of("startAt"), invalidFields(request("상황 종류", null, "회복 지원", List.of(), null, END)));
        assertEquals(Set.of("endAt"), invalidFields(request("상황 종류", null, "회복 지원", List.of(), START, null)));
    }

    @Test
    void acceptsCustomTypeAtMaximumLengthAndWhenAbsent() {
        for (String custom : new String[]{null, "", "가".repeat(15)}) {
            assertTrue(validator.validate(request("상황 종류", custom, "회복 지원", List.of(), START, END)).isEmpty());
        }
    }

    @Test
    void rejectsCustomTypeOverMaximumLength() {
        assertEquals(Set.of("customSituationType"),
                invalidFields(request("상황 종류", "가".repeat(16), "회복 지원", List.of(), START, END)));
    }

    private static SituationCreateRequest request(String type, String custom, String name,
                                                  List<Long> participants, LocalDateTime start, LocalDateTime end) {
        return new SituationCreateRequest(type, custom, name, participants, start, end);
    }

    private static Set<String> invalidFields(SituationCreateRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
