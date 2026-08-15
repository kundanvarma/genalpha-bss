-- Marketing opt-out: the customer's self-serve preference (the preference centre
-- + one-click unsubscribe). Party-keyed so the send path skips them directly, for
-- in-app and email alike. A row means opted OUT; opting back in deletes it.
CREATE TABLE marketing_opt_out (
    id         VARCHAR(36) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL DEFAULT 'genalpha',
    party_id   VARCHAR(64) NOT NULL,
    reason     VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_marketing_opt_out PRIMARY KEY (id),
    CONSTRAINT uq_marketing_opt_out UNIQUE (tenant_id, party_id)
);
CREATE INDEX idx_marketing_opt_out_party ON marketing_opt_out (tenant_id, party_id);
