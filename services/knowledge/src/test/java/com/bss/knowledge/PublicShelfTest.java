package com.bss.knowledge;

import com.bss.knowledge.service.ArticleService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Making help crawlable was never a rendering problem — it means publishing
 * content that sits behind sign-in today (#225).
 *
 * The trap it had to avoid: `customer` looks like the public shelf and is not.
 * It means a SIGNED-IN customer, and there are 43 articles on it. Reusing it
 * would have put all 43 on the open internet in one commit, reviewed by
 * nobody. So `public` is a separate, explicit shelf, and the property worth
 * testing is that **nothing reaches it by default** — not that the field
 * exists.
 */
class PublicShelfTest {

    @SuppressWarnings("unchecked")
    private Set<String> audiences() throws Exception {
        Field f = ArticleService.class.getDeclaredField("AUDIENCES");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    @Test
    void thePublicShelfIsAValidAudience() throws Exception {
        assertThat(audiences()).contains(ArticleService.PUBLIC_AUDIENCE);
    }

    /**
     * `all` is the widest shelf an authenticated reader sees and it must NOT
     * imply public: an author writing for "all" staff and customers is not
     * choosing to publish on the internet.
     */
    @Test
    void theWidestInternalShelfIsNotThePublicOne() {
        assertThat(ArticleService.PUBLIC_AUDIENCE).isNotEqualTo("all");
        assertThat(ArticleService.PUBLIC_AUDIENCE).isNotEqualTo("customer");
    }

    /**
     * The shelf is spelled exactly once in code, so an article can only land on
     * it by carrying that audience — there is no second spelling for a
     * migration or a default to match by accident.
     */
    @Test
    void theShelfNameIsExplicitAndSingular() {
        assertThat(ArticleService.PUBLIC_AUDIENCE).isEqualTo("public");
    }

    /**
     * The audience list is ordered because it is printed in the refusal a bad
     * audience gets. Adding the public shelf must not have disturbed the
     * shelves that were already there.
     */
    @Test
    void theExistingShelvesAreStillAllThere() throws Exception {
        assertThat(audiences()).contains("customer", "csr", "productOwner", "all", "sales");
    }
}
