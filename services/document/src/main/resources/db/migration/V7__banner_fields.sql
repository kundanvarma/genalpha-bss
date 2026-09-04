-- Marketing creative lives in the same TMF667 document store as the logo: a
-- banner is a document in category 'banner' with a caption and a destination.
ALTER TABLE document ADD COLUMN description VARCHAR(500);
ALTER TABLE document ADD COLUMN link VARCHAR(500);
