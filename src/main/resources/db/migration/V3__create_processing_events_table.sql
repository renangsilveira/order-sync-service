-- ============================================================
-- V3: processing_events
-- ============================================================
CREATE TABLE processing_events (
    id             UUID        NOT NULL,
    order_id       UUID        NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    message        TEXT,
    attempt_number INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMP   NOT NULL,

    CONSTRAINT pk_processing_events       PRIMARY KEY (id),
    CONSTRAINT fk_processing_events_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_processing_events_order_id   ON processing_events (order_id);
CREATE INDEX idx_processing_events_created_at ON processing_events (created_at);
