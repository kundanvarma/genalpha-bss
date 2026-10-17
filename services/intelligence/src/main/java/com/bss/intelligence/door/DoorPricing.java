package com.bss.intelligence.door;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * What a door call cost, from the provider's OWN token counts at the provider's
 * listed rates — input and output apart, because a provider charges several
 * times more for output. Rates are data: bss.ai.door-prices.<model>.input-per-mtok
 * and .output-per-mtok, in micro-dollars per million tokens; prompt caching,
 * where a provider bills cache writes and reads differently, takes
 * .cache-write-per-mtok and .cache-read-per-mtok (0 = the input rate). A model with no
 * listed rate is charged at the deployment default and the answer SAYS it was,
 * so a ledger row never passes off a guess as a price.
 */
@Component
public class DoorPricing {

    /** micro-dollars per million tokens, as a provider's price list reads */
    public record Rate(long inputPerMtok, long outputPerMtok, long cacheWritePerMtok, long cacheReadPerMtok) {
    }

    /** the cost of one call, and whether it was priced at the default */
    public record Priced(long costMicros, boolean defaulted) {
    }

    private final Map<String, Rate> rates;
    private final long defaultPerMtok;

    public DoorPricing(Environment env,
            @Value("${bss.ai.default-price-per-1k-micros:2000}") long defaultPricePer1kMicros) {
        this.rates = Binder.get(env)
                .bind("bss.ai.door-prices", Bindable.mapOf(String.class, Rate.class))
                .orElseGet(Map::of);
        this.defaultPerMtok = defaultPricePer1kMicros * 1000L;
    }

    /** One call's usage, as the provider reported it. */
    public record Usage(long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens) {
        public long allInput() {
            return inputTokens + cacheWriteTokens + cacheReadTokens;
        }
    }

    public Priced price(String model, Usage u) {
        Rate rate = model == null ? null : rates.get(model);
        if (rate == null) {
            return new Priced((u.allInput() + u.outputTokens()) * defaultPerMtok / 1_000_000L, true);
        }
        long write = rate.cacheWritePerMtok() > 0 ? rate.cacheWritePerMtok() : rate.inputPerMtok();
        long read = rate.cacheReadPerMtok() > 0 ? rate.cacheReadPerMtok() : rate.inputPerMtok();
        return new Priced((u.inputTokens() * rate.inputPerMtok() + u.outputTokens() * rate.outputPerMtok()
                + u.cacheWriteTokens() * write + u.cacheReadTokens() * read) / 1_000_000L, false);
    }
}
