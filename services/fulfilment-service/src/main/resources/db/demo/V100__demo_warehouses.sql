-- Demo warehouses (profile "demo" only).
insert into warehouses (code, capacity) values
    ('WH-EAST', 500),
    ('WH-WEST', 500)
on conflict (code) do nothing;
