package com.bss.usage.client;

import java.util.Optional;

/** Phone number -> party, resolved in the SOM's number pool — gifting by
 * MSISDN, the way people actually know each other. */
public interface NumberClient {

    Optional<String> ownerOfNumber(String number);
}
