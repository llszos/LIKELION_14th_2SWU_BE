package com.gyeot.domain.situation.service;

import com.gyeot.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class SituationCreationTypeResolverTest {
    private final SituationCreationTypeResolver resolver = new SituationCreationTypeResolver();

    @ParameterizedTest @ValueSource(strings = {"HOSPITAL_VISIT", "RECOVERY", "ABSENCE", "OTHER"})
    void acceptsOnlyAgreedCodes(String code) {
        assertThat(resolver.resolve(code, code.equals("OTHER") ? "기타" : null)).isEqualTo(code);
    }

    @Test void rejectsLegacyUnknownCaseAndPaddedCodes() {
        for (String code : Arrays.asList(null, "", "HOSPITAL", "ETC", "recovery", " RECOVERY", "UNKNOWN"))
            invalid(code, null);
    }

    @Test void otherRequiresNonblankAtMost15TrimmedCharacters() {
        for (String text : Arrays.asList(null, "", " \t\n ", "가".repeat(16))) invalid("OTHER", text);
        assertThat(resolver.resolve("OTHER", "  " + "가".repeat(15) + "  ")).isEqualTo("OTHER");
    }

    @Test void nonOtherRejectsEveryNonNullCustomValue() {
        for (String code : new String[]{"HOSPITAL_VISIT", "RECOVERY", "ABSENCE"})
            for (String custom : new String[]{"", " ", "직접 입력"}) invalid(code, custom);
    }

    private void invalid(String code, String custom) {
        assertThatThrownBy(() -> resolver.resolve(code, custom)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("INVALID_INPUT"));
    }
}
