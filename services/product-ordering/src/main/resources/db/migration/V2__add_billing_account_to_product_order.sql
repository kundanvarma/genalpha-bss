-- Order-to-cash orchestration: an order may reference the billing account that
-- will be charged; the reference is validated against party-account at order
-- creation and propagated to product-inventory on completion.

ALTER TABLE product_order ADD COLUMN billing_account_id VARCHAR(255);
