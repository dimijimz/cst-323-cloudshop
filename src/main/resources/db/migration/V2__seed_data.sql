-- V2: seed catalog so a freshly provisioned database has something to sell.
--
-- Products only. No user accounts are seeded, on purpose: a seeded account means
-- a password hash committed to the repository. Buyers register at /register, and
-- the single owner account is created at startup by OwnerAccountInitializer from
-- the OWNER_USERNAME and OWNER_PASSWORD environment variables.
--
-- Two rows are chosen for the demo: the webcam has exactly one unit, which shows
-- that two buyers cannot both buy the last one, and the laptop stand has none,
-- which shows how the catalog marks an out-of-stock product.

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
