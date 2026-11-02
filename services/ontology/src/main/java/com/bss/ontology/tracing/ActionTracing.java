package com.bss.ontology.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One span per governed action execute (#422), spelled as the OpenTelemetry
 * GenAI conventions spell a tool execution: an agent's governed action IS its
 * tool. Follows {@code open-telemetry/semantic-conventions-genai} at commit
 * {@value #SEMCONV_PIN} (2026-11-02, no tagged release; "Development"
 * status), {@code docs/gen-ai/gen-ai-spans.md} "Execute tool span": name
 * {@code execute_tool {gen_ai.tool.name}}, kind INTERNAL,
 * {@code gen_ai.operation.name = execute_tool}, {@code gen_ai.tool.name},
 * {@code gen_ai.tool.type}, {@code error.type} on a failure.
 *
 * <p>{@code gen_ai.tool.call.id} is left off on purpose: the conventions set it
 * only when a model supplied one, and the registry is not told it. The house
 * keys beside it — decision id, receipt id, idempotency key — are what join a
 * span to the receipt store and the decision log.
 *
 * <p>Inputs, the check's evidence and refusal sentences are never put on the
 * span: they name customers and offerings, and the receipt is where they live.
 */
@Component
public class ActionTracing {

    public static final String SEMCONV_PIN = "16f3130892e2e02fa3f0eef72c0e6b554ff08e5c";

    public static final AttributeKey<String> OPERATION = AttributeKey.stringKey("gen_ai.operation.name");
    public static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
    public static final AttributeKey<String> TOOL_TYPE = AttributeKey.stringKey("gen_ai.tool.type");
    public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    public static final AttributeKey<String> TENANT = AttributeKey.stringKey("bss.tenant");
    public static final AttributeKey<String> AGENT = AttributeKey.stringKey("bss.agent");
    public static final AttributeKey<String> CHANNEL = AttributeKey.stringKey("bss.channel");
    public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("bss.action.outcome");
    public static final AttributeKey<Long> STATUS = AttributeKey.longKey("bss.action.status");
    public static final AttributeKey<String> DECISION_ID = AttributeKey.stringKey("bss.decision_id");
    public static final AttributeKey<String> RECEIPT_ID = AttributeKey.stringKey("bss.receipt_id");
    public static final AttributeKey<String> APPROVAL_ID = AttributeKey.stringKey("bss.approval_id");

    private final Tracer tracer;

    @Autowired
    public ActionTracing(ObjectProvider<OpenTelemetry> otel) {
        this(otel.getIfAvailable(OpenTelemetry::noop));
    }

    public ActionTracing(OpenTelemetry otel) {
        this.tracer = otel.getTracer("com.bss.ontology.actions");
    }

    /** Opens the span; the caller makes it current, then ends it. */
    public Span start(String action, String tenant, String agent, String channel) {
        String name = action == null || action.isBlank() ? "unknown" : action;
        return tracer.spanBuilder("execute_tool " + name)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(OPERATION, "execute_tool")
                .setAttribute(TOOL_NAME, name)
                // the conventions' word for a tool run agent-side that calls an outside API
                .setAttribute(TOOL_TYPE, "extension")
                .setAttribute(TENANT, tenant == null ? "unknown" : tenant)
                .setAttribute(AGENT, agent == null ? "none" : agent)
                .setAttribute(CHANNEL, channel == null ? "unknown" : channel)
                .startSpan();
    }

    /**
     * How it ended: the outcome word and the status the caller got. A 5xx is
     * an error (the action could not be attempted, or its outcome is unknown);
     * a refusal by the check or a component is a governed answer, not an error.
     */
    public static void finish(Span span, String outcome, int status, String decisionId, String approvalId) {
        span.setAttribute(OUTCOME, outcome);
        span.setAttribute(STATUS, (long) status);
        if (decisionId != null) {
            span.setAttribute(DECISION_ID, decisionId);
        }
        if (approvalId != null) {
            span.setAttribute(APPROVAL_ID, approvalId);
        }
        if (status >= 500) {
            span.setAttribute(ERROR_TYPE, String.valueOf(status));
            span.setStatus(StatusCode.ERROR);
        }
    }

    /** The receipt store names its receipt on whatever span is current. */
    public static void receipt(String receiptId) {
        if (receiptId != null) {
            Span.current().setAttribute(RECEIPT_ID, receiptId);
        }
    }

    /** The current trace id (32 hex), or null when there is no valid trace. */
    public static String currentTraceId() {
        SpanContext ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : null;
    }
}
