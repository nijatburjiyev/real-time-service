package com.edwardjones.cre;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Necessary to enable the @Scheduled annotation
public class ComplianceSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComplianceSyncApplication.class, args);
    }
}
