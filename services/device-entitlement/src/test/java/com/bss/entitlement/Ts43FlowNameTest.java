package com.bss.entitlement;

import com.bss.entitlement.controller.Ts43Controller;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /ts43/flow/{name} rendered the path variable into a single-quoted HTML
 * attribute without escaping it, so
 *
 *     /flow/x'&gt;&lt;script&gt;alert(document.domain)&lt;/script&gt;
 *
 * closed the attribute and the tag and injected markup. The page only ever made
 * sense for the four flows it names, so an unknown one is now refused — which
 * removes the injection and the nonsense page together.
 */
class Ts43FlowNameTest {

    private static ResponseEntity<String> flow(String name) throws Exception {
        Ts43Controller c = new Ts43Controller(null, null, null, null);
        Method m = Ts43Controller.class.getDeclaredMethod("otherFlow", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        ResponseEntity<String> r = (ResponseEntity<String>) m.invoke(c, name);
        return r;
    }

    @Test
    void anInjectedFlowNameIsRefusedRatherThanRendered() throws Exception {
        ResponseEntity<String> r = flow("x'><script>alert(document.domain)</script>");
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).isNull();
    }

    @Test
    void theFourRealFlowsStillRender() throws Exception {
        for (String name : new String[] {"carrier-billing", "satellite", "not-enabled", "subscribe"}) {
            ResponseEntity<String> r = flow(name);
            assertThat(r.getStatusCode().value()).as(name).isEqualTo(200);
            assertThat(r.getBody()).as(name).contains("<h1>");
        }
    }
}
