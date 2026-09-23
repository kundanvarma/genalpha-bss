package com.bss.usage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** What an OCS puts in {@code x-ocs-signature}, for the tests that play one. */
final class OcsSignature {

    private OcsSignature() {
    }

    static String of(String secret, String body) {
        return at(secret, body, System.currentTimeMillis());
    }

    static String at(String secret, String body, long epochMillis) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String v1 = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal((epochMillis + "." + body).getBytes(StandardCharsets.UTF_8)));
            return "t=" + epochMillis + ",v1=" + v1;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
