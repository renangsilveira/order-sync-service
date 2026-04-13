-- ============================================================
-- V2: order_items
-- ============================================================
CREATE TABLE order_items (
    id          UUID           NOT NULL,
    order_id    UUID           NOT NULL,
    sku         VARCHAR(100)   NOT NULL,
    name        VARCHAR(255)   NOT NULL,
    quantity    INTEGER        NOT NULL,
    unit_price  NUMERIC(19, 2) NOT NULL,
    total_price NUMERIC(19, 2) NOT NULL,

    CONSTRAINT pk_order_items         PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order   FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_order_items_qty    CHECK (quantity > 0),
    CONSTRAINT chk_order_items_price  CHECK (unit_price > 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
