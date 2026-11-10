package com.gyeot.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 영역별 스케줄러(@Scheduled)가 공통으로 쓰는 설정입니다. (#40)
 * - 스케줄링을 켜는 곳은 이 클래스 한 곳입니다. 메인 클래스에 두면 테스트에서도 항상 켜져서 분리했습니다. (JpaAuditingConfig와 같은 이유)
 * - test 프로필에서는 이 설정을 읽지 않아, 테스트 중에 정리 작업 같은 스케줄러가 실행되지 않습니다.
 * - 사용 예정: 접근 이력·회수 권한 2년 보존 정리(D, #44), Refresh Token 정리(A, #33)
 *
 * <p>스케줄러 작성 규칙
 * <ol>
 *   <li>위치: 각 도메인의 scheduler 패키지 (예: domain/access/scheduler)</li>
 *   <li>실행 시각: {@code @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")}처럼 zone을 꼭 적기 (서버 시간대와 상관없이 한국 시간 기준)</li>
 *   <li>현재 시각: LocalDateTime.now() 대신 Clock을 주입받아 사용 (테스트에서 시각을 고정할 수 있게)</li>
 *   <li>중복 실행: 두 번 실행돼도 결과가 같게 작성 (예: "2년 지난 것 삭제"). 서버가 1대라 동시 실행 방지 장치는 두지 않음</li>
 *   <li>로그: 처리 건수만 남기고 개인정보·민감정보는 남기지 않기</li>
 *   <li>테스트: 시간을 기다리지 않고 스케줄러 메서드를 직접 호출해 검증</li>
 * </ol>
 */
@Slf4j              // 스케줄러 작업이 실패했을 때 로그를 남기기 위한 logger 생성
@Configuration      // 애플리케이션이 시작될 때 읽는 설정 클래스
@EnableScheduling   // 이게 있어야 @Scheduled가 붙은 메서드가 정해진 시간에 실행됨
@Profile("!test")   // test 프로필이 아닐 때만 적용 (테스트에서는 스케줄러 전체가 꺼짐)
public class SchedulingConfig {

    private static final int POOL_SIZE = 2; // 동시에 실행할 수 있는 스케줄러 수 (기본값 1이면 한 작업이 오래 걸릴 때 다른 작업이 밀림)

    /**
     * @Scheduled 작업을 실행하는 스레드 풀입니다.
     * 이름이 taskScheduler인 Bean이 있으면 Spring이 이 풀로 스케줄러를 실행합니다.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("gyeot-scheduler-"); // 로그에서 스케줄러 스레드를 구분하기 쉽게 이름을 붙임

        // 작업 하나가 예외로 실패해도 로그만 남기고 다음 실행은 계속되도록 함 (원인 추적용)
        scheduler.setErrorHandler(throwable -> log.error("스케줄러 작업이 실패했습니다.", throwable));

        // 서버가 종료될 때 실행 중인 작업이 끝날 때까지 최대 30초 기다림 (삭제 작업이 중간에 끊기지 않게)
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
