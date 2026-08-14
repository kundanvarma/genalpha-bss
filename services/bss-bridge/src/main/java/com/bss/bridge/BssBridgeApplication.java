package com.bss.bridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The BSS bridge: the ONE boundary component that lets the martech module run as
 * a standalone add-on on top of a FOREIGN BSS. A foreign BSS posts its own
 * events here (in its own shape); the bridge normalizes them to the martech's
 * tiny envelope — {eventType, tenantId, event:{resource}} — and republishes them
 * onto the martech ingress topic. Everything downstream (traits, audiences,
 * journeys, activation) is unchanged and never learns there is a foreign BSS.
 *
 * <p>Per-BSS knowledge lives in the mapping config, NOT in the martech services —
 * onboarding a new BSS is a config + deploy, not a code fork.
 */
@SpringBootApplication
public class BssBridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(BssBridgeApplication.class, args);
    }
}
