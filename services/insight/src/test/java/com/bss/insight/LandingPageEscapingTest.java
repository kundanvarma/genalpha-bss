package com.bss.insight;

import com.bss.insight.service.LandingPageService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE LANDING PAGE IS PUBLIC AND IT REFLECTS A QUERY PARAMETER.
 *
 * /landingPage/{slug}/view takes utm_source straight from the URL and writes it into
 * a string literal inside an inline &lt;script&gt; block. Escaping the apostrophe and
 * the backslash keeps the literal well-formed and misses the actual danger: the HTML
 * parser looks for the closing tag before JavaScript ever sees the string, so a value
 * containing &lt;/script&gt; ends the block early and everything after it is markup.
 *
 * That was reflected XSS on a page a campaign links to. These tests hold the fix.
 */
class LandingPageEscapingTest {

    private static String jsStr(String raw) throws Exception {
        Method m = LandingPageService.class.getDeclaredMethod("jsStr", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }

    @Test
    void aScriptCloseInTheUtmParameterCannotEndTheBlock() throws Exception {
        String payload = "x</script><script>alert(document.domain)</script>";
        String out = jsStr(payload);
        assertThat(out)
                .as("no literal angle bracket may survive into a <script> block")
                .doesNotContain("<").doesNotContain(">")
                .doesNotContainIgnoringCase("</script");
        // the value is still the same string to JavaScript, just unicode-escaped
        assertThat(out).contains("\\u003C").contains("\\u003E");
    }

    @Test
    void quotesBackslashesAndLineTerminatorsStayInsideTheLiteral() throws Exception {
        assertThat(jsStr("it's")).isEqualTo("'it\\'s'");
        assertThat(jsStr("a\\b")).isEqualTo("'a\\\\b'");
        assertThat(jsStr("one\ntwo")).isEqualTo("'one\\ntwo'");
        assertThat(jsStr("u sep")).isEqualTo("'u\\u2028sep'");
        assertThat(jsStr(null)).isEqualTo("''");
    }

    @Test
    void anOrdinaryCampaignSourceIsUnchanged() throws Exception {
        assertThat(jsStr("winter-2026")).isEqualTo("'winter-2026'");
    }
}
