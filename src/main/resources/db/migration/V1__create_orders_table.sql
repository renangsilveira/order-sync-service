-- ============================================================
-- V1: orders
-- ============================================================
CREATE TABLE orders (
    id                UUID         NOT NULL,
    external_order_id VARCHAR(255) NOT NULL,
    source_system     VARCHAR(100) NOT NULL,
    customer_name     VARCHAR(255) NOT NULL,
    customer_email    VARCHAR(255) NOT NULL,
    currency          VARCHAR(10)  NOT NULL,
    total_amount      NUMERIC(19, 2) NOT NULL,
    status            VARCHAR(50)  NOT NULL,
    idempotency_key   VARCHAR(255) NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,

    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_orders_external_order_id ON orders (external_order_id);
CREATE INDEX idx_orders_status            ON orders (status);
CREATE INDEX idx_orders_created_at        ON orders (created_at);
