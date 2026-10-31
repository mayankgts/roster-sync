-- Offices per tenant. Used to validate office codes on incoming records.
CREATE TABLE office (
    tenant_id    VARCHAR(64)  NOT NULL,
    office_code  VARCHAR(32)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    PRIMARY KEY (tenant_id, office_code)
);

-- One row per employee per tenant.
CREATE TABLE employee (
    tenant_id           VARCHAR(64)  NOT NULL,
    employee_id         VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,

    -- HR-owned fields. Nullable so a DEACTIVATE that arrives before
    -- the CREATE can still leave a tombstone row.
    hr_version          BIGINT       NOT NULL,
    name                VARCHAR(200),
    email               VARCHAR(320),
    phone               VARCHAR(20),
    manager_id          VARCHAR(64),
    office_code         VARCHAR(32),
    shift_code          VARCHAR(32),

    -- Transport-admin-owned fields. HR events never write these.
    pickup_point        VARCHAR(500),
    special_assistance  VARCHAR(500),
    admin_version       BIGINT       NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, employee_id),
    CONSTRAINT chk_employee_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_hr_version CHECK (hr_version >= 0),
    CONSTRAINT fk_employee_office FOREIGN KEY (tenant_id, office_code)
        REFERENCES office (tenant_id, office_code)
);

CREATE INDEX idx_employee_tenant_status ON employee (tenant_id, status);

-- Transactional outbox. Written in the SAME transaction as the employee change;
-- a separate relay publishes rows and stamps published_at.
CREATE TABLE outbox (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    employee_id     VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(32)  NOT NULL,
    source_version  BIGINT       NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;