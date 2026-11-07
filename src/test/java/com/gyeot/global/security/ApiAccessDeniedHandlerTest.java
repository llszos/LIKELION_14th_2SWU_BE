package com.gyeot.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiAccessDeniedHandlerTest {

    @Test
    void returnsCommonForbiddenResponse() throws Exception {
        ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler(new ObjectMapper());
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(
                new MockHttpServletRequest(),
                response,
                new AccessDeniedException("forbidden"));

        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals(403, response.getStatus());
        assertEquals("error", body.get("status").asText());
        assertEquals("FORBIDDEN", body.get("code").asText());
        assertTrue(response.getContentType().startsWith("application/json"));
    }
}
