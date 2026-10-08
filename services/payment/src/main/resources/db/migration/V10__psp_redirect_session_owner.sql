-- WHO STARTED A REDIRECT SESSION (issue #250).
--
-- startRedirect asked the PSP for a session and returned its id to the caller
-- without recording anything. The Payment row appeared only at confirm, and its
-- owner came from whoever was confirming:
--
--     entity.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
--
-- Nothing compared that to whoever STARTED the session, because there was
-- nothing to compare against. So anyone holding a session id could call the
-- return leg and receive an AUTHORIZED payment owned by themselves, for money
-- the real customer had paid. The id is long and random and comes from the PSP,
-- so guessing is not the path — but it travels in a URL the customer's browser
-- visits, and URLs leak through referrers, history, shared screens and access
-- logs.
--
-- A session is an INTENT, not a payment: most become one, some are abandoned,
-- and TMF676 Payment means an attempted or completed payment. Recording it as a
-- pending `payment` row would change what every channel's GET /payment returns
-- and need filtering in every reader, so it gets its own table.
--
-- Rows are short-lived by nature (a redirect session lives minutes) but are not
-- swept here; they are small, and a sweep is a separate decision.
CREATE TABLE psp_redirect_session (
    id             VARCHAR(36) PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    session_ref    VARCHAR(128) NOT NULL,      -- the PSP's own session id
    provider       VARCHAR(32)  NOT NULL,
    -- the party who STARTED it. NULL is legitimate: a guest checkout has no
    -- party yet, and an operator-initiated session has no customer scope. A
    -- null owner cannot be used to claim anything, so confirm treats it as
    -- "unknown owner" rather than "anyone's".
    owner_party_id VARCHAR(64),
    amount_value   NUMERIC(19, 4),
    amount_unit    VARCHAR(8),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_psp_redirect_session UNIQUE (tenant_id, session_ref)
);
CREATE INDEX idx_psp_redirect_session_tenant ON psp_redirect_session (tenant_id);
