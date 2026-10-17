package com.bss.intelligence;

import com.bss.intelligence.door.DoorPricing;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The door's price list as SHIPPED (application.yml), read the way the
 * component reads it. A model name with a dot in it (gpt-6.1-sol) binds only
 * when its key is bracketed; unbracketed it silently falls to the default rate,
 * which is the failure this test is here to catch.
 */
class DoorPriceListTest {

    private static DoorPricing shipped() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        yaml.forEach(env.getPropertySources()::addLast);
        return new DoorPricing(env, 2000);
    }

    @Test
    void everyListedModelIsPricedAtItsOwnRateNotTheDefault() throws IOException {
        DoorPricing pricing = shipped();
        DoorPricing.Usage million = new DoorPricing.Usage(1_000_000, 1_000_000, 0, 0);
        for (String model : List.of("claude-haiku-4-5-20251001", "claude-haiku-4-5",
                "gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna")) {
            assertThat(pricing.price(model, million).defaulted()).as(model).isFalse();
        }
        // a million in and a million out, at the published Standard short-context rates
        assertThat(pricing.price("gpt-6.1-sol", million).costMicros()).isEqualTo(2_000_000 + 10_000_000);
        assertThat(pricing.price("gpt-6-luna", new DoorPricing.Usage(0, 0, 0, 1_000_000)).costMicros())
                .isEqualTo(10_000);
        assertThat(pricing.price("gpt-not-listed", million).defaulted()).isTrue();
    }
}
