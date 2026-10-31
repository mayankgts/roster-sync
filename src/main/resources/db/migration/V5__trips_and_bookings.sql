CREATE TABLE trip (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    office_code   VARCHAR(32)  NOT NULL,
    departs_at    TIMESTAMPTZ  NOT NULL,
    capacity      INT          NOT NULL,
    seats_booked  INT          NOT NULL DEFAULT 0,
    CONSTRAINT chk_trip_capacity CHECK (capacity > 0),
    CONSTRAINT chk_trip_seats CHECK (seats_booked >= 0 AND seats_booked <= capacity)
);

CREATE TABLE booking (
    id             BIGSERIAL    PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    employee_id    VARCHAR(64)  NOT NULL,
    trip_id        BIGINT       NOT NULL REFERENCES trip (id),
    status         VARCHAR(16)  NOT NULL,
    cancel_reason  VARCHAR(32),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cancelled_at   TIMESTAMPTZ,
    CONSTRAINT chk_booking_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT fk_booking_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES employee (tenant_id, employee_id)
);

-- One confirmed seat per employee per trip.
CREATE UNIQUE INDEX uq_booking_confirmed_seat ON booking (trip_id, employee_id) WHERE status = 'CONFIRMED';
-- Fast lookup of an employee's live bookings.
CREATE INDEX idx_booking_employee_confirmed ON booking (tenant_id, employee_id) WHERE status = 'CONFIRMED';