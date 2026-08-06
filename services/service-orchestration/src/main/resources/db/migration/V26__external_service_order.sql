-- TMF641: service orders POSTed by external systems (the northbound face).
-- Internal rows keep their product-order lineage; external rows carry the
-- posted document and 'external' as their origin marker.
ALTER TABLE service_order ADD COLUMN external_id VARCHAR(64);
ALTER TABLE service_order ADD COLUMN priority VARCHAR(8);
ALTER TABLE service_order ADD COLUMN description VARCHAR(1000);
ALTER TABLE service_order ADD COLUMN document_json VARCHAR(4000);
