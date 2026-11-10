package com.gyeot.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfiguration {

    @Bean
    public Clock serviceClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
