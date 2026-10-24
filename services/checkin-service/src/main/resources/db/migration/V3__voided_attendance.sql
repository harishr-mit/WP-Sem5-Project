-- Voided check-ins (RL12, D17). A voided row moves here from attendance, with who voided it, when and why: the audit
-- trail keeps it, while every count, report, certificate and the dashboard (which read attendance) leave it out, and
-- uk_attendance_pass_session lets the pass be checked in again. scan_log still holds the original scan.
CREATE TABLE voided_attendance (
    id            UUID PRIMARY KEY,
    pass_id       UUID         NOT NULL,
    event_id      BIGINT       NOT NULL,
    session_id    BIGINT       NOT NULL,
    gate_id       BIGINT       NOT NULL,
    gate_code     VARCHAR(16)  NOT NULL,
    volunteer_id  UUID         NOT NULL,
    method        VARCHAR(12)  NOT NULL,
    checked_in_at TIMESTAMPTZ  NOT NULL,
    scanned_at    TIMESTAMPTZ  NOT NULL,
    voided_at     TIMESTAMPTZ  NOT NULL,
    voided_by     VARCHAR(64)  NOT NULL,
    void_reason   VARCHAR(200) NOT NULL
);
CREATE INDEX ix_voided_attendance_event ON voided_attendance (event_id, pass_id);
