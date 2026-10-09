package com.bss.ontology.receipt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The write-ahead receipt store (ADR 0025).
 *
 * <p>The rule this class exists to enforce is a direction, and getting the
 * direction backwards is the classic error:
 *
 * <ul>
 *   <li>{@link #recordAttempt} runs <b>before</b> the action is dispatched and
 *       throws if it cannot commit. The caller must let that throw reach the
 *       user — a refusal. Nothing is dispatched, so nothing happened, and a
 *       refusal with no side effect is a safe answer.</li>
 *   <li>{@link #recordOutcome} runs <b>after</b> the far side has answered and
 *       must <b>never</b> fail the caller. The action already happened; failing
 *       the response here would tell someone their order did not go through
 *       when it did. A lost outcome leaves the attempt unresolved, which is
 *       what the reconciler looks for.</li>
 * </ul>
 *
 * <p>Both writes are {@code REQUIRES_NEW}. The attempt must be durable on its
 * own before the dispatch happens, not at the end of some enclosing
 * transaction that the dispatch itself might roll back.
 */
@Service
public class ReceiptStore {

    private static final Logger log = LoggerFactory.getLogger(ReceiptStore.class);

    private final ActionReceiptRepository rows;

    public ReceiptStore(ActionReceiptRepository rows) {
        this.rows = rows;
    }

    /**
     * Collapse line breaks before anything reaches the log. The action name and
     * tenant come from a caller, and a newline in either would let one request
     * forge extra log lines.
     *
     * <p>CHAINED replace(char, char), not replaceAll with a character class.
     * Both collapse the breaks; only this shape is the one CodeQL models as a
     * log-injection sanitiser. Same spelling as oneLine() in ReceiptPublisher
     * and service-orchestration, which learned it first.
     */
    private static String oneLine(String value) {
        return value == null ? null : value
                .replace('\n', '_').replace('\r', '_')
                .replace('\u0085', '_').replace('\u2028', '_').replace('\u2029', '_');
    }

    /** What the caller needs back to append the outcome later. */
    public record Attempt(String receiptId, int attemptNo) {
    }

    /**
     * Commit the intent. Throws if the store will not take it — and the caller
     * must not dispatch when it throws.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Attempt recordAttempt(String tenant, String action, String principal, String agent,
                                 String agentVersion, String authority, String actionVersion,
                                 String inputsJson, String checkJson, String target,
                                 String idempotencyKey) {
        String receiptId = UUID.randomUUID().toString();
        ActionReceiptRow row = new ActionReceiptRow(UUID.randomUUID().toString(), receiptId,
                ActionReceiptRow.ATTEMPT, tenant, action, OffsetDateTime.now());
        row.setPrincipal(principal);
        row.setAgent(agent);
        row.setAgentVersion(agentVersion);
        row.setAuthority(authority);
        row.setActionVersion(actionVersion);
        row.setInputs(inputsJson);
        row.setCheckVerdict(checkJson);
        row.setTarget(target);
        row.setIdempotencyKey(idempotencyKey);
        row.setAttemptNo(1);
        chain(row);
        rows.saveAndFlush(row);
        return new Attempt(receiptId, 1);
    }

    /**
     * Append what happened. Returns true when the outcome was stored.
     *
     * <p>Never throws: see the class note. A false return is a real problem —
     * the action happened and its evidence did not land — so it is logged at
     * ERROR and left for reconciliation, but it must not change the answer the
     * caller already earned.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordOutcome(String tenant, String receiptId, String action, String status,
                                 Integer componentStatus, String providerRef, String effectsJson,
                                 String emitsJson, String refusal) {
        try {
            ActionReceiptRow row = new ActionReceiptRow(UUID.randomUUID().toString(), receiptId,
                    ActionReceiptRow.OUTCOME, tenant, action, OffsetDateTime.now());
            row.setStatus(status);
            row.setComponentStatus(componentStatus);
            row.setProviderRef(providerRef);
            row.setEffects(effectsJson);
            row.setEmits(emitsJson);
            row.setRefusal(refusal);
            chain(row);
            rows.saveAndFlush(row);
            return true;
        } catch (RuntimeException e) {
            // ERROR, not WARN: the action happened and the evidence did not land.
            log.error("receipt outcome NOT stored for receiptId={} action={} tenant={} status={} — "
                            + "the attempt stays unresolved for reconciliation: {}",
                    oneLine(receiptId), oneLine(action), oneLine(tenant), oneLine(status),
                    oneLine(e.toString()));
            return false;
        }
    }

    /**
     * A proposal that was refused before anything was dispatched — the check said
     * no, or the request could not be built. One row is enough: there was no
     * dispatch, so there is no outcome to wait for and nothing to reconcile.
     *
     * <p>Recorded because a blocked consequential proposal is evidence too: "the
     * agent tried and was stopped" is exactly what a reviewer needs to see, and
     * it is invisible if only successes are written down.
     *
     * <p>Does not throw, for the same reason {@link #recordOutcome} does not —
     * but the reason is different and worth stating. Nothing happened here, so
     * failing closed would buy no safety; the caller is already being refused,
     * and turning a refusal into a different refusal only loses the real one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordRefusal(String tenant, String action, String principal, String inputsJson,
                                 String checkJson, String refusal) {
        try {
            String receiptId = UUID.randomUUID().toString();
            ActionReceiptRow row = new ActionReceiptRow(UUID.randomUUID().toString(), receiptId,
                    ActionReceiptRow.OUTCOME, tenant, action, OffsetDateTime.now());
            row.setPrincipal(principal);
            row.setInputs(inputsJson);
            row.setCheckVerdict(checkJson);
            row.setStatus(ActionReceiptRow.REFUSED);
            row.setRefusal(refusal);
            chain(row);
            rows.saveAndFlush(row);
            return true;
        } catch (RuntimeException e) {
            log.error("refusal of {} for tenant {} was NOT recorded: {}",
                    oneLine(action), oneLine(tenant), oneLine(e.toString()));
            return false;
        }
    }

    /**
     * Link this row to the tenant's current chain head.
     *
     * <p>Honest limit: two receipts written for the same tenant at the same
     * instant can read the same head and produce a fork. The verifier reports a
     * fork rather than hiding it, and serialising writes per tenant is the fix —
     * not done here, because a wrong lock is worse than a named gap.
     */
    private void chain(ActionReceiptRow row) {
        ActionReceiptRow head = rows.chainHead(row.getTenantId());
        String prev = head == null ? "" : String.valueOf(head.getRowHash());
        row.setPrevHash(head == null ? null : head.getRowHash());
        row.setRowHash(sha256(prev + "|" + canonical(row)));
    }

    /**
     * The bytes the hash covers. Field order is fixed here on purpose: a chain
     * is only verifiable if everyone agrees how a row is spelled.
     */
    static String canonical(ActionReceiptRow r) {
        return String.join("|",
                n(r.getReceiptId()), n(r.getPhase()), n(r.getTenantId()), n(r.getAction()),
                n(r.getActionVersion()), n(r.getPrincipal()), n(r.getAgent()), n(r.getAgentVersion()),
                n(r.getAuthority()), n(r.getInputs()), n(r.getCheckVerdict()), n(r.getTarget()),
                n(r.getIdempotencyKey()), String.valueOf(r.getAttemptNo()), n(r.getStatus()),
                String.valueOf(r.getComponentStatus()), n(r.getProviderRef()), n(r.getEffects()),
                n(r.getEmits()), n(r.getRefusal()), n(r.getCompensationRef()),
                String.valueOf(r.getRecordedAt()));
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for the receipt chain", e);
        }
    }
}
