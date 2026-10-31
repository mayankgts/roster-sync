-- Hash of the HR-owned state (id + version + HR fields), maintained on every HR write.
ALTER TABLE employee ADD COLUMN record_hash BIGINT;

CREATE TABLE reconciliation_run (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    file_path    VARCHAR(500) NOT NULL,
    summary      JSONB,
    approved_by  VARCHAR(100),
    approved_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    CONSTRAINT chk_run_status CHECK (status IN ('RUNNING', 'BLOCKED', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_recon_run_tenant ON reconciliation_run (tenant_id, created_at DESC);