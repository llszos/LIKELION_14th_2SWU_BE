package com.gyeot;

import org.springframework.boot.SpringApplication;

public class TestGyeotApplication {

	public static void main(String[] args) {
		SpringApplication.from(GyeotApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
