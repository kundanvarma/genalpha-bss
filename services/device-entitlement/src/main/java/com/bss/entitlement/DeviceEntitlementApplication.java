package com.bss.entitlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * device-entitlement — the GSMA TS.43 Entitlement Configuration Server as an
 * ODA component: phones ask it which services their subscription entitles
 * them to (VoLTE, VoWiFi, SMSoIP, data plan, eSIM activation and transfer);
 * it answers from the catalog and the line's state, after proving the SIM
 * with EAP-AKA against the operator's AUC seam.
 */
@SpringBootApplication
@EnableScheduling
public class DeviceEntitlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeviceEntitlementApplication.class, args);
    }
}
