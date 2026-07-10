-- One database per service.
--
-- These services previously shared a single "bss" database. That is unsafe once
-- migrations are involved: each service owns an independent Flyway history, and
-- they would collide in a shared flyway_schema_history table. Separate databases
-- also keep each service's schema private, which is the point of the boundary.
--
-- Postgres runs this only on first initialisation of an empty data directory.
-- If you previously ran `docker compose up` against the old shared database,
-- remove the volume first: `docker compose down -v`.

CREATE DATABASE product_catalog;
CREATE DATABASE product_ordering;
CREATE DATABASE product_inventory;
CREATE DATABASE party_account;
CREATE DATABASE product_stock;
CREATE DATABASE payment;
