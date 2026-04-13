-- ============================================================
-- V4: integration_attempts
-- ============================================================
CREATE TABLE integration_attempts (
    id                   UUID      NOT NULL,
    order_id             UUID      NOT NULL,
    attempt_number       INTEGER   NOT NULL,
    request_payload      TEXT,
    response_status_code INTEGER,
    response_body        TEXT,
    error_message        TEXT,
    success              BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP NOT NULL,

    CONSTRAINT pk_integration_attempts       PRIMARY KEY (id),
    CONSTRAINT fk_integration_attempts_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_integration_attempts_order_id ON integration_attempts (order_id);
