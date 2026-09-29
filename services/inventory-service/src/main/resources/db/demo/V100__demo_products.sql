-- Demo catalog (profile "demo" only). Same five SKUs the seller dashboard's offline demo mode uses.
insert into products (sku, name, unit_price, on_hand) values
    ('SKU-1001', 'Wireless Mouse',      24.99, 142),
    ('SKU-1002', 'Mechanical Keyboard', 89.50,  56),
    ('SKU-1003', 'USB-C Hub 7-in-1',    45.00,   8),
    ('SKU-1004', '4K Webcam',          129.99,  23),
    ('SKU-1005', 'Standing Desk Mat',   39.75,   0)
on conflict (sku) do nothing;
