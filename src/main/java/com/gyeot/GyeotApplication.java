package com.gyeot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class GyeotApplication {

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul")); // 서버가 어디서 실행되든 기본 시간대를 한국 시간으로 고정 (ERD 7.6)
		SpringApplication.run(GyeotApplication.class, args);
	}

}