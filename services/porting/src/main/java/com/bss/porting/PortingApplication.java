package com.bss.porting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PortingApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortingApplication.class, args);
    }
}
