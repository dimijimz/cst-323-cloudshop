-- ===========================================================================
-- CloudShop - seed data (DOCUMENTATION EXPORT)
--
-- Source of truth: src/main/resources/db/migration/V2__seed_data.sql
--
-- A documentation export of the Flyway seed migration, provided alongside
-- schema.sql so the DDL script has the reference data that goes with it. As
-- with schema.sql, this is NOT meant to be run by hand against a cloud
-- database - Flyway applies it at first startup and records it as version 2.
-- See the header of schema.sql for why a manual run breaks the next deploy.
--
-- Depends on schema.sql having been applied first.
--
-- Products only. No user accounts are seeded: buyers register at /register,
-- and the single owner account is created at startup from the OWNER_USERNAME
-- and OWNER_PASSWORD environment variables, so no password hash is ever
-- committed to the repository.
--
-- DdlExportSyncTest fails the build if this file and V2 drift apart.
-- ===========================================================================

INSERT INTO products (name, description, price, stock, active) VALUES
    ('Mechanical Keyboard',
     'Tenkeyless layout with hot-swappable tactile switches and white backlighting.',
     79.99, 25, TRUE),
    ('Wireless Mouse',
     'Ergonomic six-button mouse with a silent scroll wheel and a USB-C rechargeable battery.',
     24.99, 40, TRUE),
    ('27-inch 4K Monitor',
     'IPS panel at 3840 x 2160 with a height-adjustable stand and USB-C power delivery.',
     329.00, 8, TRUE),
    ('USB-C Docking Station',
     'Eleven ports including dual HDMI, gigabit Ethernet and 100 W laptop charging.',
     119.50, 12, TRUE),
    ('Noise-Cancelling Headphones',
     'Over-ear wireless headphones with active noise cancellation and 30 hours of battery life.',
     199.00, 5, TRUE),
    ('1080p Webcam',
     'Full HD webcam with autofocus, a privacy shutter and dual noise-reducing microphones.',
     49.95, 1, TRUE),
    ('Laptop Stand',
     'Folding aluminum stand that raises a laptop screen to eye level. Fits up to 17 inches.',
     34.00, 0, TRUE),
    ('Portable SSD 1TB',
     'Pocket-sized solid state drive with read speeds up to 1050 MB/s over USB 3.2.',
     89.99, 30, TRUE);
