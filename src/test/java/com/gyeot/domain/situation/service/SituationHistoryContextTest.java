package com.gyeot.domain.situation.service;

import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.situation.controller.SituationHistoryController;
import com.gyeot.domain.situation.entity.Situation;
import com.gyeot.domain.situation.repository.SituationHistoryQueryRepository;
import com.gyeot.global.security.auth.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 C Bean 없이 생성자 주입을 확인합니다. DB·전체 Boot 자동 설정 검증은 아닙니다. */
class SituationHistoryContextTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(SituationHistoryQueryRepository.class, () -> mock(SituationHistoryQueryRepository.class))
            .withUserConfiguration(SituationHistoryService.class, SituationHistoryController.class);

    @Test
    void C_Bean_없이_컨텍스트를_시작하고_목록을_호출한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SituationHistoryService.class)
                    .hasSingleBean(SituationHistoryController.class).doesNotHaveBean(CareRequestSummaryReader.class);
            var repository = context.getBean(SituationHistoryQueryRepository.class);
            var pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id")));
            when(repository.findHistory(10L, pageable)).thenReturn(new SliceImpl<>(List.of(), pageable, false));
            var response = context.getBean(SituationHistoryController.class).list(new AuthenticatedUser(10L), 0, 20);
            assertThat(response.status()).isEqualTo("success");
            assertThat(response.data().items()).isEmpty();
            assertThat(response.data().hasNext()).isFalse();
            verify(repository).findHistory(10L, pageable);
        });
    }

    @Test
    void C_Bean_없는_상세_호출은_빈_결과로_위장하지_않는다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(CareRequestSummaryReader.class);
            Situation situation = Situation.create(10L, "돌봄", "지난 상황",
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
            situation.end(LocalDate.of(2026, 11, 1).atStartOfDay());
            when(context.getBean(SituationHistoryQueryRepository.class).findAccessibleHistory(1L, 10L))
                    .thenReturn(Optional.of(situation));
            assertThatThrownBy(() -> context.getBean(SituationHistoryService.class).detail(1L, 10L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("구현이 준비되지 않았습니다");
        });
    }
}
