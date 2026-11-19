package com.gyeot.domain.home.service;

import com.gyeot.domain.access.query.SharingCountReader;
import com.gyeot.domain.carerequest.query.CareRequestSummaryReader;
import com.gyeot.domain.home.controller.HomeController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HomeContextTest {
    @Test
    void C_Reader가_없으면_필수_의존성_누락으로_기동에_실패한다() {
        new ApplicationContextRunner()
                .withBean(SharingCountReader.class, () -> mock(SharingCountReader.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(HomeController.class, HomeService.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
                });
    }

    @Test
    void C_D_Reader가_있으면_홈_Bean을_구성한다() {
        new ApplicationContextRunner()
                .withBean(CareRequestSummaryReader.class, () -> mock(CareRequestSummaryReader.class))
                .withBean(SharingCountReader.class, () -> mock(SharingCountReader.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(HomeController.class, HomeService.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(HomeController.class));
    }
}
