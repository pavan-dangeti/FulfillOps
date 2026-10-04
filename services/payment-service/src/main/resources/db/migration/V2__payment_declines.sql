-- A declined charge is recorded like a successful one, under the same one-row-per-order key, so a
-- replayed request gets the stored answer instead of a second roll of the dice. For a declined
-- row, charged_at is when the decision was made.
alter table payments drop constraint payments_status_check;
alter table payments add constraint payments_status_check
    check (status in ('CHARGED', 'REFUNDED', 'DECLINED'));
