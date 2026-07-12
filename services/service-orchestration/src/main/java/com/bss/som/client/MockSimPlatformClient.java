package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dev SIM platform: every OTA PIN push succeeds instantly. */
@Component
@ConditionalOnProperty(name = "bss.sim.platform", havingValue = "mock", matchIfMissing = true)
public class MockSimPlatformClient implements SimPlatformClient {

    private static final Logger log = LoggerFactory.getLogger(MockSimPlatformClient.class);

    @Override
    public boolean resetPin(String iccid, String newPin) {
        // never log the PIN itself
        log.info("mock SIM platform: OTA PIN update pushed to ICCID …{}",
                iccid.substring(Math.max(0, iccid.length() - 5)));
        return true;
    }
}
