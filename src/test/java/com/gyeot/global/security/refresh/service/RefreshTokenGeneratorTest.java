package com.gyeot.global.security.refresh.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefreshTokenGeneratorTest {

    private final RefreshTokenGenerator generator = new RefreshTokenGenerator();

    @Test
    void generatesThirtyTwoBytesAndHashesThemWithSha256() {
        String first = generator.generate();
        String second = generator.generate();

        assertEquals(43, first.length());
        assertNotEquals(first, second);
        assertEquals(64, generator.hash(first).length());
        assertNotEquals(first, generator.hash(first));
        assertEquals(generator.hash(first), generator.hash(first));
    }

    @Test
    void rejectsBlankTokenBeforeHashing() {
        assertThrows(IllegalArgumentException.class, () -> generator.hash(" "));
    }
}
