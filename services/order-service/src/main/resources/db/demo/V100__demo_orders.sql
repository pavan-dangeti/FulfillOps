-- Demo orders (profile "demo" only): the twelve synthetic orders the CS console used to seed into H2.
insert into orders (order_number, customer_name, email, sku, product_name, quantity, total, status, created_at, refunded_at) values
    ('ORD-1050', 'Ava Chen',        'ava.chen@example.com',        'SKU-1002', 'Mechanical Keyboard', 1,  89.50, 'PROCESSING', now() - interval '2 hours',          null),
    ('ORD-1049', 'Marcus Webb',     'marcus.webb@example.com',     'SKU-1001', 'Wireless Mouse',      3,  74.97, 'SHIPPED',    now() - interval '5 hours',          null),
    ('ORD-1048', 'Priya Nair',      'priya.nair@example.com',      'SKU-1003', 'USB-C Hub 7-in-1',    2,  90.00, 'PENDING',    now() - interval '8 hours',          null),
    ('ORD-1047', 'Diego Fernandez', 'diego.fernandez@example.com', 'SKU-1004', '4K Webcam',           1, 129.99, 'DELIVERED',  now() - interval '1 day',            null),
    ('ORD-1046', 'Yuki Tanaka',     'yuki.tanaka@example.com',     'SKU-1005', 'Standing Desk Mat',   2,  79.50, 'SHIPPED',    now() - interval '1 day 3 hours',    null),
    ('ORD-1045', 'Hannah Schmidt',  'hannah.s@example.com',        'SKU-1002', 'Mechanical Keyboard', 1,  89.50, 'PROCESSING', now() - interval '2 days',           null),
    ('ORD-1044', 'Omar Haddad',     'omar.h@example.com',          'SKU-1003', 'USB-C Hub 7-in-1',    1,  45.00, 'PENDING',    now() - interval '2 days 6 hours',   null),
    ('ORD-1043', 'Lena Kovacs',     'lena.k@example.com',          'SKU-1001', 'Wireless Mouse',      5, 124.95, 'DELIVERED',  now() - interval '3 days',           null),
    ('ORD-1042', 'Tomás Rivera',    'tomas.r@example.com',         'SKU-1004', '4K Webcam',           2, 259.98, 'SHIPPED',    now() - interval '3 days 2 hours',   null),
    ('ORD-1041', 'Sofia Greco',     'sofia.g@example.com',         'SKU-1005', 'Standing Desk Mat',   1,  39.75, 'PENDING',    now() - interval '4 days',           null),
    ('ORD-1040', 'Wei Zhang',       'wei.z@example.com',           'SKU-1002', 'Mechanical Keyboard', 2, 179.00, 'DELIVERED',  now() - interval '5 days',           null),
    ('ORD-1039', 'Emily Stone',     'emily.stone@example.com',     'SKU-1001', 'Wireless Mouse',      1,  24.99, 'REFUNDED',   now() - interval '6 days',           now() - interval '5 days')
on conflict (order_number) do nothing;
