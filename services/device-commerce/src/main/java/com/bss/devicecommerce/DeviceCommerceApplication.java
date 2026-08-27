package com.bss.devicecommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DeviceCommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeviceCommerceApplication.class, args);
    }
}
