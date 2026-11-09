package com.gyeot.domain.safetycard.controller;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse;
import com.gyeot.domain.safetycard.dto.response.SharingOptionsResponse.CategoryOption;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.service.SharingOptionService;
import com.gyeot.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 MVC 요청 변환(파라미터·헤더·예외 응답)을 확인합니다. DB나 Docker는 필요 없습니다. */
class SharingOptionControllerTest {

    private SharingOptionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SharingOptionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SharingOptionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 선택지를_조회한다() throws Exception {
        when(service.getSharingOptions(1L, HelpType.HOSPITAL, 5L)).thenReturn(new SharingOptionsResponse(
                List.of(new CategoryOption(InformationCategory.BASIC, "기본정보", List.of()))));

        mvc.perform(get("/api/safety-information/sharing-options")
                        .header("X-User-Id", 1L)
                        .param("helpType", "HOSPITAL")
                        .param("requestId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.categories[0].category").value("BASIC"))
                .andExpect(jsonPath("$.data.categories[0].categoryName").value("기본정보"));
    }

    @Test
    void helpType이_없으면_400이다() throws Exception {
        mvc.perform(get("/api/safety-information/sharing-options").header("X-User-Id", 1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void helpType이_잘못된_값이면_400이다() throws Exception {
        mvc.perform(get("/api/safety-information/sharing-options")
                        .header("X-User-Id", 1L)
                        .param("helpType", "SWIMMING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
