package com.gyeot.domain.home.service;

import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.home.controller.HomeController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HomeContextTest {
    @Test
    void C_미병합이어도_Bean은_구성하되_홈_조회는_성공으로_위장하지_않는다() {
        new ApplicationContextRunner()
                .withBean(SharingCountReader.class, () -> mock(SharingCountReader.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(HomeController.class, HomeService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(HomeController.class);
                    assertThatThrownBy(() -> context.getBean(HomeService.class).getHome(20L))
                            .isInstanceOf(IllegalStateException.class);
                });
    }
}
