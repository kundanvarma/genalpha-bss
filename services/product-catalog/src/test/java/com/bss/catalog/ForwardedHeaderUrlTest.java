package com.bss.catalog;

import com.bss.catalog.controller.GeoController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The public page's canonical URL and its "View in the shop" link are built
 * from {@code X-Forwarded-Host} and {@code X-Forwarded-Proto} — values that
 * arrive in a REQUEST HEADER and end up inside an {@code href}.
 *
 * <p>HTML escaping does not help there. {@code javascript:alert(1)} contains
 * no character that {@code &amp;}, {@code &lt;}, {@code &gt;} or {@code &quot;}
 * would touch, and it still runs when the link is clicked. So the scheme is
 * chosen from a closed set and the host must look like a host — CodeQL flagged
 * exactly this path (java/xss, alert 513) and it was right to.</p>
 */
class ForwardedHeaderUrlTest {

    private String baseUrlFor(String host, String proto) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (host != null) {
            request.addHeader("X-Forwarded-Host", host);
        }
        if (proto != null) {
            request.addHeader("X-Forwarded-Proto", proto);
        }
        GeoController controller = new GeoController(null, null, null, null, null, null, null, null);
        return (String) ReflectionTestUtils.invokeMethod(controller, "baseUrl", request);
    }

    @Test
    void aForwardedProtoCannotSmuggleAScheme() {
        for (String hostile : new String[] {
                "javascript", "javascript:alert(1)//", "data", "vbscript", "JaVaScRiPt" }) {
            String url = baseUrlFor("shop.taranga.no", hostile);
            assertFalse(url.toLowerCase().startsWith("javascript"),
                    "a forwarded proto of '" + hostile + "' reached the URL: " + url);
            assertFalse(url.toLowerCase().startsWith("data"),
                    "a forwarded proto of '" + hostile + "' reached the URL: " + url);
            assertEquals("http://shop.taranga.no", url,
                    "anything but https must fall back to http, never be echoed");
        }
    }

    @Test
    void aHostThatIsNotAHostIsRefusedToRelativeUrls() {
        for (String hostile : new String[] {
                "shop.no\" onmouseover=alert(1) x=\"",
                "javascript:alert(1)",
                "shop.no/../../evil",
                "shop.no?x=1" }) {
            assertEquals("", baseUrlFor(hostile, "https"),
                    "a host that is not host[:port] must give relative URLs, not a guess: " + hostile);
        }
    }

    @Test
    void theOrdinaryCaseStillWorks() {
        assertEquals("https://shop.taranga.no", baseUrlFor("shop.taranga.no", "https"));
        assertEquals("https://shop.taranga.no:8443", baseUrlFor("shop.taranga.no:8443", "https"));
        assertEquals("http://shop.taranga.no", baseUrlFor("shop.taranga.no", null));
        // a proxy chain: the first entry is the one the visitor actually used
        assertEquals("https://shop.taranga.no", baseUrlFor("shop.taranga.no, inner.svc", "https, http"));
        // no forwarding at all: relative, never the internal service name
        assertEquals("", baseUrlFor(null, null));
    }
}
