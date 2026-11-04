package com.gyeot.global;

import com.gyeot.global.common.ApiResponse;
import com.gyeot.global.exception.BusinessException;
import com.gyeot.global.exception.CommonErrorCode;
import com.gyeot.global.exception.ErrorCode;
import com.gyeot.global.exception.GlobalExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 테스트 전용 Controller로 실제 MVC 예외 변환을 확인합니다. DB나 Docker는 필요 없습니다. */
class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ExampleController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void successContainsAllFourFieldsIncludingNulls() throws Exception {
        mvc.perform(get("/api/test"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"status":"success","code":null,"message":null,"data":{"name":"미진"}}
                        """));
    }

    @Test
    void creationUses201AndOptionalMessage() throws Exception {
        mvc.perform(post("/api/test").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"미진\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("생성되었습니다."));
    }

    @Test
    void deletionUses200AndNullData() throws Exception {
        mvc.perform(delete("/api/test"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"success\",\"code\":null,\"message\":null,\"data\":null}"));
    }

    @Test
    void emptyListIsWrappedInItems() throws Exception {
        mvc.perform(get("/api/test/items"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void domainErrorPreserves404AndDomainCode() throws Exception {
        mvc.perform(get("/api/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"status":"error","code":"TEST_NOT_FOUND","message":"대상이 없습니다.","data":null}
                        """));
    }

    @Test
    void domainConflictPreserves409() throws Exception {
        mvc.perform(get("/api/test/conflict"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEST_CONFLICT"));
    }

    @Test
    void invalidBodyBecomes400() throws Exception {
        mvc.perform(post("/api/test").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void malformedJsonBecomes400() throws Exception {
        mvc.perform(post("/api/test").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void missingBodyBecomes400() throws Exception {
        mvc.perform(post("/api/test").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void invalidPathVariableBecomes400() throws Exception {
        mvc.perform(get("/api/test/numbers/abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void unsupportedMethodPreserves405AndAllowHeader() throws Exception {
        mvc.perform(put("/api/test"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void unsupportedContentTypePreserves415() throws Exception {
        mvc.perform(post("/api/test").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void unexpectedErrorHidesInternalMessage() throws Exception {
        mvc.perform(get("/api/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {"status":"error","code":"INTERNAL_SERVER_ERROR",
                         "message":"서버 내부 오류가 발생했습니다.","data":null}
                        """));
    }

    @Test
    void businessAuthenticationErrorUses401() throws Exception {
        mvc.perform(get("/api/test/unauthorized"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void businessPermissionErrorUses403() throws Exception {
        mvc.perform(get("/api/test/forbidden"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private enum ExampleErrorCode implements ErrorCode {
        TEST_NOT_FOUND(HttpStatus.NOT_FOUND), TEST_CONFLICT(HttpStatus.CONFLICT);
        private final HttpStatus httpStatus;
        ExampleErrorCode(HttpStatus httpStatus) { this.httpStatus = httpStatus; }
        public HttpStatus getHttpStatus() { return httpStatus; }
        public String getCode() { return name(); }
        public String getMessage() { return "대상이 없습니다."; }
    }

    record CreateRequest(@NotBlank String name) { }

    @RestController
    @RequestMapping("/api/test")
    static class ExampleController {
        @GetMapping
        ApiResponse<Map<String, String>> success() { return ApiResponse.success(Map.of("name", "미진")); }

        @PostMapping
        ResponseEntity<ApiResponse<CreateRequest>> create(@Valid @RequestBody CreateRequest request) {
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(request, "생성되었습니다."));
        }

        @DeleteMapping
        ApiResponse<Void> remove() { return ApiResponse.success(); }

        @GetMapping("/items")
        ApiResponse<Map<String, List<String>>> items() { return ApiResponse.success(Map.of("items", List.of())); }

        @GetMapping("/numbers/{id}")
        ApiResponse<Long> number(@PathVariable("id") Long id) { return ApiResponse.success(id); }

        @GetMapping("/missing")
        void missing() { throw new BusinessException(ExampleErrorCode.TEST_NOT_FOUND); }

        @GetMapping("/conflict")
        void conflict() { throw new BusinessException(ExampleErrorCode.TEST_CONFLICT); }

        @GetMapping("/failure")
        void failure() { throw new IllegalStateException("internal-sensitive-detail"); }

        @GetMapping("/unauthorized")
        void unauthorized() { throw new BusinessException(CommonErrorCode.UNAUTHORIZED); }

        @GetMapping("/forbidden")
        void forbidden() { throw new BusinessException(CommonErrorCode.FORBIDDEN); }
    }
}
