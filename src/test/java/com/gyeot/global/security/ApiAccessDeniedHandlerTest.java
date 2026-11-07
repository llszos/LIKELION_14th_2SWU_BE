package com.gyeot.global.security;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiAccessDeniedHandlerTest {

    @Test
    void returnsCommonForbiddenResponse() throws Exception {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler(jsonMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(
                new MockHttpServletRequest(),
                response,
                new AccessDeniedException("forbidden"));

        var body = jsonMapper.readTree(response.getContentAsString());
        assertEquals(403, response.getStatus());
        assertEquals("error", body.get("status").asText());
        assertEquals("FORBIDDEN", body.get("code").asText());
        assertTrue(response.getContentType().startsWith("application/json"));
    }
}
