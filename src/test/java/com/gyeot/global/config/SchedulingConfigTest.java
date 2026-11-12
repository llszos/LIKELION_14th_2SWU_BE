package com.gyeot.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로필에 따라 스케줄링이 켜지고 꺼지는지 확인합니다.
 * 서버 전체가 아니라 SchedulingConfig 하나만 올리는 가벼운 테스트라 DB나 Docker는 필요 없습니다.
 */
class SchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class); // 이 설정 클래스만 올림

    @Test
    void test_프로필이_아니면_스케줄링이_켜지고_스레드_풀이_2개다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local")) // 로컬 실행과 같은 프로필
                .run(context -> {
                    assertThat(context).hasSingleBean(SchedulingConfig.class);                     // 설정이 적용됨
                    assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class); // @Scheduled를 찾아 실행하는 Bean이 생김 = 스케줄링 켜짐
                    assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize())
                            .isEqualTo(2);                                                         // 스레드 풀 크기
                });
    }

    @Test
    void test_프로필이면_스케줄링이_꺼진다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test")) // 테스트와 같은 프로필
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SchedulingConfig.class);                     // 설정 자체를 읽지 않음
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class); // 스케줄링 꺼짐
                    assertThat(context).doesNotHaveBean(ThreadPoolTaskScheduler.class);              // 스레드 풀도 만들지 않음
                });
    }
}
