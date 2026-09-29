#!/usr/bin/env bash
# Proves each service's database role can use its own schema and no other.
# Without USAGE on a schema a role cannot read or write anything inside it.
# Run against the compose stack: scripts/check-schema-isolation.sh
set -euo pipefail
cd "$(dirname "$0")/.."

violations=$(docker compose exec -T postgres psql -U postgres -d fulfillops -qtA <<'SQL'
select r.rolname || ' -> ' || n.nspname
from pg_roles r
cross join (values ('order_svc'), ('inventory_svc'), ('payment_svc'), ('fulfilment_svc'), ('public')) as n(nspname)
where r.rolname in ('order_svc', 'inventory_svc', 'payment_svc', 'fulfilment_svc')
  and (has_schema_privilege(r.rolname, n.nspname, 'USAGE') or has_schema_privilege(r.rolname, n.nspname, 'CREATE'))
    <> (r.rolname = n.nspname);
SQL
)

if [[ -n "$violations" ]]; then
  echo "schema isolation FAILED (role -> schema where access is wrong):"
  echo "$violations"
  exit 1
fi
echo "schema isolation OK: each of the 4 service roles can use only its own schema"
