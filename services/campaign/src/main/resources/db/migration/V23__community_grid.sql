-- G3 — THE COMMUNITY GRID: an area goal is a game the whole street plays
-- ("62% to your fibre unlock"), and a referral can carry the area it grows.
-- Klubbdugnad rides the code's club_org_id from G1: a code tied to a local
-- club makes every conversion count for the club's season.
CREATE TABLE community_goal (
    id         VARCHAR(36)  PRIMARY KEY,
    tenant_id  VARCHAR(64)  NOT NULL DEFAULT 'genalpha',
    name       VARCHAR(255) NOT NULL,
    area_code  VARCHAR(32)  NOT NULL,
    target     INT          NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_community_goal ON community_goal (tenant_id, area_code);

ALTER TABLE referral_conversion ADD COLUMN area_code VARCHAR(32);
