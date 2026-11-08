package com.gyeot.domain.safetycard.controller;

import com.gyeot.domain.safetycard.dto.response.CategoryItemsResponse;
import com.gyeot.domain.safetycard.entity.InformationCategory;
import com.gyeot.domain.safetycard.service.SafetyInformationService;
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
class SafetyInformationControllerTest {

    private SafetyInformationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SafetyInformationService.class);
        // Spring 전체를 띄우지 않고 이 Controller와 공통 예외 처리기만 연결
        mvc = MockMvcBuilders.standaloneSetup(new SafetyInformationController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 분류별_항목을_조회한다() throws Exception {
        when(service.getCategoryItems(1L, InformationCategory.HEALTH))
                .thenReturn(new CategoryItemsResponse(InformationCategory.HEALTH, "건강", List.of()));

        mvc.perform(get("/api/safety-information").header("X-User-Id", 1L).param("category", "HEALTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.category").value("HEALTH"))
                .andExpect(jsonPath("$.data.categoryName").value("건강"));
    }

    @Test
    void category가_없거나_잘못되면_400이다() throws Exception {
        // category 없음
        mvc.perform(get("/api/safety-information").header("X-User-Id", 1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        // 없는 분류 값
        mvc.perform(get("/api/safety-information").header("X-User-Id", 1L).param("category", "FOOD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
