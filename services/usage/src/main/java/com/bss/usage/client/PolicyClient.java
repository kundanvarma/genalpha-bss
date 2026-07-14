package com.bss.usage.client;

import java.util.Map;
import java.util.Optional;

/** The operator's veto: gifting rules authored as data in the policy
 * component (domain 'gifting'), evaluated live. */
public interface PolicyClient {

    /** The deny MESSAGE when a gifting rule blocks this context, else empty. */
    Optional<String> giftingDeny(Map<String, Object> context);
}
