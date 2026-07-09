-- TMF620 CTK conformance: every tested resource must expose lastUpdate and
-- lifecycleStatus; productOffering echoes its productSpecification reference;
-- productOfferingPrice is a spec resource the CTK exercises with full CRUD.

ALTER TABLE product_offering ADD COLUMN last_update TIMESTAMP WITH TIME ZONE;
ALTER TABLE product_offering ADD COLUMN product_specification VARCHAR(4000);

ALTER TABLE product_specification ADD COLUMN lifecycle_status VARCHAR(255);
ALTER TABLE product_specification ADD COLUMN last_update TIMESTAMP WITH TIME ZONE;

CREATE TABLE product_offering_price (
    id               VARCHAR(36)   NOT NULL,
    href             VARCHAR(255),
    name             VARCHAR(255)  NOT NULL,
    price_type       VARCHAR(255),
    is_bundle        BOOLEAN,
    lifecycle_status VARCHAR(255),
    version          VARCHAR(255),
    last_update      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_product_offering_price PRIMARY KEY (id)
);
