package com.gyeot.domain.situation.dto.response;

import com.gyeot.domain.carerequest.enums.HelpType;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HelpRecord;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader.HelpRecordFilter;
import com.gyeot.domain.carerequest.query.RequestDisplayStatus;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** A 표시정보·안심카드·장소 없이 C의 확정 기록 필드만 노출합니다. */
public record HelpRecordPageResponse(List<Item> items, int page, int size, boolean hasNext) {
    public HelpRecordPageResponse {
        items = List.copyOf(items);
    }

    public static HelpRecordPageResponse from(Page<HelpRecord> records) {
        Objects.requireNonNull(records, "C 도움 기록 페이지는 null일 수 없습니다.");
        return new HelpRecordPageResponse(records.getContent().stream().map(Item::from).toList(),
                records.getNumber(), records.getSize(), records.hasNext());
    }

    /** 급한 요청의 situationId/startsAt, 담당자 없이 끝난 요청의 assigneeId는 null입니다. */
    public record Item(Long requestId, Long situationId, HelpType helpType, LocalDateTime startsAt,
                       Long requesterId, Long assigneeId, RequestDisplayStatus status, HelpRecordFilter role) {
        private static Item from(HelpRecord record) {
            return new Item(record.requestId(), record.situationId(), record.helpType(), record.startsAt(),
                    record.requesterId(), record.assigneeId(), record.status(), record.role());
        }
    }
}
