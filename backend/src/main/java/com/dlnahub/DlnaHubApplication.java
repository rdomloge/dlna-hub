package com.dlnahub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DlnaHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlnaHubApplication.class, args);
    }
}
