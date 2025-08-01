package com.example.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RealTimeServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(RealTimeServiceApplication.class, args);
	}

}
