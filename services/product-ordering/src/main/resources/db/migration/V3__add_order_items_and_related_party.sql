-- TMF622 conformance: productOrderItem is a mandatory part of a product order
-- and relatedParty is required by the spec schema. Both are stored as JSON
-- documents and echoed verbatim; the service does not interpret their content
-- beyond requiring at least one order item.

ALTER TABLE product_order ADD COLUMN product_order_item VARCHAR(4000);
ALTER TABLE product_order ADD COLUMN related_party VARCHAR(4000);
