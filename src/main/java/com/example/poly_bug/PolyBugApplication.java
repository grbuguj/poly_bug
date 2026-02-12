package com.example.poly_bug;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PolyBugApplication {
    public static void main(String[] args) {
        SpringApplication.run(PolyBugApplication.class, args);
    }
}
