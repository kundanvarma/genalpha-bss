-- TMF632 contactMedium: where a party's postal address, email and phone live.
-- The storefront saves the shipping address here at checkout.

ALTER TABLE individual ADD COLUMN contact_medium VARCHAR(4000);
