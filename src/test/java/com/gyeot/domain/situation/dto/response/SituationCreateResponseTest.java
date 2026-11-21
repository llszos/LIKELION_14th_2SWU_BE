package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.situation.entity.SituationStatus;
import com.gyeot.global.common.ApiResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spring Context와 DB 없이 응답 DTO 및 실제 Jackson JSON 구조를 검증합니다. */
class SituationCreateResponseTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void preservesResponseFields() {
        SituationCreateResponse response = new SituationCreateResponse(1L, 10L, SituationStatus.ACTIVE);

        assertEquals(1L, response.situationId());
        assertEquals(10L, response.creatorId());
        assertEquals(SituationStatus.ACTIVE, response.status());
    }

    @Test
    void serializesTheRequestedJsonStructure() {
        SituationCreateResponse response = new SituationCreateResponse(1L, 10L, SituationStatus.ACTIVE);

        assertEquals(mapper.readTree("""
                {"situationId":1,"creatorId":10,"status":"ACTIVE"}
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void serializesExistingStatusEnumsAsTheirNames() {
        for (SituationStatus status : SituationStatus.values()) {
            SituationCreateResponse response = new SituationCreateResponse(1L, 10L, status);
            var json = mapper.readTree(mapper.writeValueAsString(response));

            assertEquals(status.name(), json.get("status").asText());
        }
    }

    @Test
    void serializesTheCommonSuccessEnvelopeIncludingNullFields() {
        SituationCreateResponse response = new SituationCreateResponse(1L, 10L, SituationStatus.ACTIVE);
        ApiResponse<SituationCreateResponse> envelope = ApiResponse.success(response);

        assertEquals(mapper.readTree("""
                {
                  "status":"success",
                  "code":null,
                  "message":null,
                  "data":{"situationId":1,"creatorId":10,"status":"ACTIVE"}
                }
                """), mapper.readTree(mapper.writeValueAsString(envelope)));
    }
}
