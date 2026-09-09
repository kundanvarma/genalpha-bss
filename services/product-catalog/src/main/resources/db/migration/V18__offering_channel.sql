-- TMF620 ProductOffering.channel: the sales channels an offering is available
-- in (JSON list of ChannelRef {id, name}). Empty = every channel, so nothing
-- already on the shelf changes; a non-empty list is enforced server-side.
ALTER TABLE product_offering ADD COLUMN channel VARCHAR(2000);
