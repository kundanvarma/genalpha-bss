package com.bss.intelligence.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * One span per model call (#422), spelled the way the OpenTelemetry GenAI
 * semantic conventions spell an inference span — so any GenAI-aware backend
 * reads it without a mapping.
 *
 * <p><b>Pinned version.</b> The conventions are in "Development" status and
 * have no tagged release; this follows
 * {@code open-telemetry/semantic-conventions-genai} at commit
 * {@value #SEMCONV_PIN} (2026-11-02), {@code docs/gen-ai/client-inference.md}:
 * span name {@code {gen_ai.operation.name} {gen_ai.request.model}}, kind
 * CLIENT, {@code gen_ai.provider.name} (the successor of the older
 * {@code gen_ai.system}), {@code gen_ai.request.model},
 * {@code gen_ai.usage.input_tokens} / {@code output_tokens}, {@code error.type}.
 *
 * <p><b>No content, ever.</b> The conventions' opt-in content attributes
 * ({@code gen_ai.input.messages}, {@code gen_ai.output.messages},
 * {@code gen_ai.system_instructions}) are never written: a prompt carries
 * customers, and a trace backend is not where the redactor's placeholders are
 * reversed. This class has no method that accepts text, so a later caller
 * cannot put a prompt on a span by accident.
 */
@Component
public class GenAiTracing {

    public static final String SEMCONV_PIN = "16f3130892e2e02fa3f0eef72c0e6b554ff08e5c";

    public static final AttributeKey<String> OPERATION = AttributeKey.stringKey("gen_ai.operation.name");
    public static final AttributeKey<String> PROVIDER = AttributeKey.stringKey("gen_ai.provider.name");
    public static final AttributeKey<String> REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    public static final AttributeKey<Long> INPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    public static final AttributeKey<Long> OUTPUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    /** House attributes ride beside the standard ones, never instead of them. */
    public static final AttributeKey<String> TENANT = AttributeKey.stringKey("bss.tenant");
    public static final AttributeKey<String> USE_CASE = AttributeKey.stringKey("bss.use_case");
    public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("bss.ai.outcome");
    /** True when the token counts are the ledger's chars/4 estimate, not the provider's own usage. */
    public static final AttributeKey<Boolean> TOKENS_ESTIMATED = AttributeKey.booleanKey("bss.usage.estimated");
    /** On a fallback: the provider the answering one stood in for, and the class of its failure. */
    public static final AttributeKey<String> FALLBACK_FROM = AttributeKey.stringKey("bss.fallback_from");
    public static final AttributeKey<String> FALLBACK_ERROR_TYPE = AttributeKey.stringKey("bss.fallback_error_type");

    private final Tracer tracer;

    @org.springframework.beans.factory.annotation.Autowired
    public GenAiTracing(ObjectProvider<OpenTelemetry> otel) {
        this(otel.getIfAvailable(OpenTelemetry::noop));
    }

    public GenAiTracing(OpenTelemetry otel) {
        this.tracer = otel.getTracer("com.bss.intelligence.ai");
    }

    /** Opens a {@code chat} span; the caller makes it current, then ends it. */
    public Span startChat(String provider, String model, String tenant, String useCase) {
        String m = model == null || model.isBlank() ? "unknown" : model;
        return tracer.spanBuilder("chat " + m)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(OPERATION, "chat")
                .setAttribute(PROVIDER, provider == null ? "unknown" : provider)
                .setAttribute(REQUEST_MODEL, m)
                .setAttribute(TENANT, tenant == null ? "unknown" : tenant)
                .setAttribute(USE_CASE, useCase == null ? "unknown" : useCase)
                .startSpan();
    }

    /** Token counts and the ledger's outcome word; numbers only. */
    public static void usage(Span span, long inputTokens, long outputTokens, boolean estimated, String outcome) {
        span.setAttribute(INPUT_TOKENS, inputTokens);
        span.setAttribute(OUTPUT_TOKENS, outputTokens);
        span.setAttribute(TOKENS_ESTIMATED, estimated);
        span.setAttribute(OUTCOME, outcome);
    }

    /**
     * A failed call: the error CLASS only. The exception's message is not
     * recorded — a provider's error text can quote the prompt back.
     */
    public static void failed(Span span, String errorType, String outcome) {
        span.setAttribute(ERROR_TYPE, errorType);
        span.setAttribute(OUTCOME, outcome);
        span.setStatus(StatusCode.ERROR);
    }

    /**
     * The first choice failed and the tenant's fallback is being tried: the span
     * now names the provider and model that will answer (as the ledger's served
     * row does), and keeps the one it stood in for, with that failure's CLASS, in
     * house attributes. {@code error.type} and ERROR status are left for a call
     * that actually failed — the fallback's own failure sets them via
     * {@link #failed}. When the fallback answers, the status stays UNSET, as for
     * any successful call: the OpenTelemetry spec asks instrumentation not to
     * set OK itself.
     */
    public static void fellBack(Span span, String fromProvider, String failureType, String provider, String model) {
        String m = model == null || model.isBlank() ? "unknown" : model;
        span.updateName("chat " + m);
        span.setAttribute(PROVIDER, provider == null ? "unknown" : provider);
        span.setAttribute(REQUEST_MODEL, m);
        span.setAttribute(FALLBACK_FROM, fromProvider == null ? "unknown" : fromProvider);
        span.setAttribute(FALLBACK_ERROR_TYPE, failureType);
    }

    /**
     * The trace id the ledger row is keyed by — the current span's, or null when
     * there is no valid trace (tracing absent). A null is an absence, never the
     * all-zero id OpenTelemetry uses for "invalid".
     */
    public static String currentTraceId() {
        SpanContext ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : null;
    }
}
