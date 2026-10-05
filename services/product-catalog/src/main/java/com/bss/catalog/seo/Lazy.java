package com.bss.catalog.seo;

import java.util.function.Supplier;

/**
 * A value computed on first read and remembered — the difference between one
 * projection and an expensive one.
 *
 * <p>{@link PublicOffering} carries every public fact about an offering, but
 * the surfaces read different parts of it: {@code sitemap.xml} wants a URL and
 * a date, {@code llms.txt} adds the price, the agentic feed adds availability,
 * and only the offering page reads the specification. Two of those facts cost
 * a call to another component — the warehouse, and the specification — so a
 * shelf of forty offerings was costing eighty downstream calls to render a
 * sitemap that reads none of them. Measured: 115 seconds, and a 500.</p>
 *
 * <p>So the projection stays one object and one set of rules; it just does not
 * pay for a fact nobody asked for. Memoised, so a surface that reads twice
 * calls once.</p>
 *
 * <p>Not thread-safe by design: a projection is built and read inside one
 * request, and a lock here would cost more than the race it prevents.</p>
 */
public final class Lazy<T> {

    private final Supplier<T> source;
    private T value;
    private boolean read;

    private Lazy(Supplier<T> source) {
        this.source = source;
    }

    public static <T> Lazy<T> of(Supplier<T> source) {
        return new Lazy<>(source);
    }

    /** An already-known value — for tests and for facts that cost nothing. */
    public static <T> Lazy<T> known(T value) {
        Lazy<T> lazy = new Lazy<>(() -> value);
        lazy.value = value;
        lazy.read = true;
        return lazy;
    }

    public T get() {
        if (!read) {
            value = source.get();
            read = true;
        }
        return value;
    }
}
