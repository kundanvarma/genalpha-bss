package com.bss.revenue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TMF658 Loyalty Management — component #34. Reward & retain: a points
 * ledger earning on SETTLED BILLS (the billing relationship, not shopping
 * trips) and burning into the rewards a telco customer actually wants —
 * gigabytes first (via the usage allowance-boost mechanic), delivered
 * through events, verified at the meter.
 */
@SpringBootApplication
@EnableScheduling
public class RevenueApplication {

    public static void main(String[] args) {
        SpringApplication.run(RevenueApplication.class, args);
    }
}
