package com.bss.som;

import com.bss.som.security.CallbackUrlPolicy;
import org.junit.jupiter.api.Test;
import com.bss.som.exception.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A retailer puts a callback URL in the order body and we POST to it from
 * inside the fleet's own network. The door is signed, so the caller is a known
 * operator — but the URL is still a string they chose, and our network
 * position reaches things nothing outside can. That is request forgery with
 * our own credentials of place.
 */
class CallbackUrlPolicyTest {

    private static final String FLEET_HOST = "service-orchestration";
    private final CallbackUrlPolicy policy = new CallbackUrlPolicy(FLEET_HOST + ", partner.example");

    private static String on(String host) {
        return "http://" + host + ":8080/tmf-api/serviceOrdering/v4/wholesaleAccessOrder/x/notification";
    }

    @Test
    void anAllowedRetailerHostIsCalled() {
        // the fleet's own callback, which is what every real order carries here
        assertThat(policy.allows(on(FLEET_HOST))).isTrue();
    }

    @Test
    void aHostNobodyNamedIsRefused() {
        assertThat(policy.allows(on("collector.attacker.example"))).isFalse();
        assertThatThrownBy(() -> policy.requireAllowed(on("collector.attacker.example")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not on the allowed-host list");
    }

    @Test
    void theInternalNeighboursAreRefusedByName() {
        // the whole point: these are reachable from where we stand and from
        // nowhere else, and none of them is a retailer
        for (String host : new String[] {"keycloak", "postgres", "kafka", "intelligence"}) {
            assertThat(policy.allows(on(host))).as(host).isFalse();
        }
    }

    @Test
    void loopbackAndCloudMetadataAreRefusedEvenIfNamed() {
        CallbackUrlPolicy lax = new CallbackUrlPolicy("localhost, 169.254.169.254");

        // an operator can name a host; they cannot name their way to the
        // instance-metadata service or back into this process
        assertThat(lax.allows("http://localhost:8080/x")).isFalse();
        assertThat(lax.allows("http://169.254.169.254/latest/meta-data/")).isFalse();
    }

    @Test
    void onlyHttpAndHttpsAreCallable() {
        CallbackUrlPolicy lax = new CallbackUrlPolicy("etc");
        assertThat(lax.allows("file:///etc/passwd")).isFalse();
        assertThat(lax.allows("gopher://etc/")).isFalse();
        assertThat(lax.allows("ftp://etc/x")).isFalse();
    }

    @Test
    void credentialsInTheUrlAreRefused() {
        assertThatThrownBy(() -> policy.requireAllowed("http://user:pass@" + FLEET_HOST + ":8080/x"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void junkIsRefusedRatherThanGuessedAt() {
        assertThat(policy.allows(null)).isFalse();
        assertThat(policy.allows("")).isFalse();
        assertThat(policy.allows("not a url at all")).isFalse();
        assertThat(policy.allows("http://")).isFalse();
    }

    @Test
    void withNoListConfiguredNoCallbackIsSent() {
        // the safe direction for a notification: the order still completes, the
        // notice does not go, and the log says why
        CallbackUrlPolicy unconfigured = new CallbackUrlPolicy("");

        assertThat(unconfigured.allows(on(FLEET_HOST))).isFalse();
        assertThatThrownBy(() -> unconfigured.requireAllowed(on(FLEET_HOST)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("empty");
    }
}
