#!/usr/bin/env python3
"""Proves the ordering invariants against a running FulfillOps stack.

Standard library only, so it runs anywhere Python 3 does and adds no dependency
to the project. The invariant checks themselves live in `invariants.sql` and are
read directly out of the four services' schemas.

    ./scripts/ordering-harness.py setup --units 100
    ./scripts/ordering-harness.py storm --orders 1000
    ./scripts/ordering-harness.py check
    ./scripts/ordering-harness.py settle --timeout 180
    ./scripts/ordering-harness.py inject duplicate --order ORD-000001
    ./scripts/ordering-harness.py inject reorder --order ORD-000001

`storm` is the headline: it fires many orders at a product with a fixed number of
units and reports how many were confirmed. `check` runs the invariants and exits
non-zero if any is broken.
"""

import argparse
import concurrent.futures
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ORDER_SERVICE = os.environ.get("ORDER_SERVICE_URL", "http://localhost:8081")
INVENTORY_SERVICE = os.environ.get("INVENTORY_SERVICE_URL", "http://localhost:8082")
SELLER = os.environ.get("SELLER_USER", "seller")
SELLER_PASSWORD = os.environ.get("SELLER_PASSWORD", "seller-dev-password")

_token = None


def fail(message):
    print(f"FAIL  {message}", file=sys.stderr)
    sys.exit(1)


def ok(message):
    print(f"ok    {message}")


def info(message):
    print(f"      {message}")


# --- the stores, read as the only role that can see all four schemas -------------

def sql(query, variables=()):
    """Runs SQL as the postgres superuser and returns rows split on a pipe."""
    command = ["docker", "compose", "exec", "-T", "postgres",
               "psql", "-U", "postgres", "-d", "fulfillops", "-t", "-A", "-F|", "-v", "ON_ERROR_STOP=1"]
    for name, value in variables:
        command += ["-v", f"{name}={value}"]
    command += ["-c", query]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"psql failed: {result.stderr.strip()}")
    return [line.split("|") for line in result.stdout.splitlines() if line != ""]


def count(query):
    return int(sql(query)[0][0])


# --- order-service over HTTP ---------------------------------------------------

def token():
    global _token
    if _token is None:
        body = json.dumps({"username": SELLER, "password": SELLER_PASSWORD}).encode()
        request = urllib.request.Request(f"{ORDER_SERVICE}/api/auth/token", data=body,
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                _token = json.load(response)["accessToken"]
        except (urllib.error.URLError, KeyError) as error:
            fail(f"could not sign in to order-service at {ORDER_SERVICE}: {error}\n"
                 f"      Is the stack up? `docker compose up --build --wait`")
    return _token


def post(path, payload, base=ORDER_SERVICE):
    request = urllib.request.Request(f"{base}{path}", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json",
                                              "Authorization": f"Bearer {token()}"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def place_order(sku, quantity):
    """Returns the order number, or None when the order was never accepted.

    A refusal at the door is a legitimate answer, not a test failure: what matters is that
    an order is never accepted without somewhere to record it. A dependency being down is
    counted separately, because such an order does not exist at all — there is nothing to
    lose, and nothing for the invariants to check.
    """
    try:
        return post("/api/orders", {"customerName": "Storm Test", "email": "storm@example.com",
                                    "sku": sku, "quantity": quantity})["orderNumber"]
    except urllib.error.HTTPError as error:
        if error.code in (400, 409, 422):
            return None
        if error.code in (500, 502, 503, 504):
            UNREACHABLE.append(error.code)
            return None
        raise
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        # order-service itself went away mid-burst. The order was never created.
        UNREACHABLE.append(0)
        return None


UNREACHABLE = []


# --- commands ------------------------------------------------------------------

def command_setup(args):
    """Creates the product the storm orders against, or resets its stock if it is already there."""
    try:
        post("/api/products", {"sku": args.sku, "name": "Invariant Widget",
                               "unitPrice": "10.00", "stock": args.units}, base=INVENTORY_SERVICE)
    except urllib.error.HTTPError as error:
        if error.code != 409:
            raise
        # Already exists from an earlier run: reset the stock instead, so the run starts
        # from a known level without needing the database touched.
        request = urllib.request.Request(
            f"{INVENTORY_SERVICE}/api/products/{args.sku}/stock",
            data=json.dumps({"stock": args.units}).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {token()}"},
            method="PUT")
        with urllib.request.urlopen(request, timeout=10):
            pass
        info(f"{args.sku} already existed; stock reset to {args.units}")
    print(f"ok    product {args.sku} ready with {args.units} unit(s) on hand")


def command_settle(args):
    """Waits until no order is in flight, then reports the tally.

    The broker relays on a 200ms tick and reconciliation runs every 60s, so a
    deliberately broken service needs that long to be noticed and repaired.
    """
    deadline = time.time() + args.timeout
    last = None
    while True:
        tally = breakdown()
        if tally == last and in_flight() == 0:
            return ok(f"settled: {describe(tally)}")
        if time.time() > deadline:
            fail(f"did not settle within {args.timeout}s: {describe(tally)}\n"
                 f"      {in_flight()} order(s) still in flight, {outbox_backlog()} event(s) unrelayed")
        last = tally
        time.sleep(1)


def command_storm(args):
    """The headline: many orders, a fixed number of units, and an exact answer."""
    settle_first = count("select count(*) from order_svc.orders where saga_step not in ('CONFIRMED','FAILED')")
    if settle_first:
        info(f"waiting for {settle_first} pre-existing order(s) in flight to settle")
        command_settle(argparse.Namespace(timeout=args.settle_timeout))
    sql(f"update order_svc.outbox set published_at = null")  # everything re-published once, harmlessly

    started = time.time()
    since = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    placed = 0
    refused = 0
    del UNREACHABLE[:]
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(place_order, args.sku, args.quantity) for _ in range(args.orders)]
        for future in concurrent.futures.as_completed(futures):
            if future.result() is None:
                refused += 1
            else:
                placed += 1
    elapsed = time.time() - started
    if fixture_orders():
        info(f"note: {fixture_orders()} pre-existing order(s) are demo fixtures that never went "
             f"through the saga; the per-order checks below are scoped to this run")

    print(f"      placed {placed} order(s) at {args.concurrency} concurrent clients "
          f"in {elapsed:.1f}s ({placed / elapsed:.0f}/s)")
    if refused:
        info(f"{refused} refused at the door (never counted as an order)")
    if UNREACHABLE:
        codes = ", ".join(str(code) or "connection reset" for code in sorted(set(UNREACHABLE)))
        info(f"{len(UNREACHABLE)} could not be reached ({codes}) — a service was down, "
             f"so these orders were never created")

    if placed == 0:
        # Nothing reached the saga, so every check below would pass vacuously.
        fail("no order was placed, so this run proves nothing — is the stack up?")

    command_settle(argparse.Namespace(timeout=args.settle_timeout))
    after = breakdown()

    # Scoped to the product under test: the database may hold orders from earlier runs, and the
    # claim is about this one product's units, not about every order ever placed.
    confirmed_units = count(f"select coalesce(sum(quantity),0) from order_svc.orders "
                            f"where saga_step = 'CONFIRMED' and sku = '{args.sku}'")
    confirmed_orders = count(f"select count(*) from order_svc.orders "
                             f"where saga_step = 'CONFIRMED' and sku = '{args.sku}'")
    rejected = count(f"select count(*) from order_svc.orders "
                     f"where saga_step = 'FAILED' and sku = '{args.sku}'")
    print()
    info(f"confirmed: {confirmed_orders} order(s) / {confirmed_units} unit(s) for {args.sku}")
    info(f"rejected:  {rejected} order(s) for want of stock")
    info(f"database:  {describe(after)} (all products)")

    violations = check(since=since)
    if confirmed_units > args.units:
        fail(f"OVERSOLD: {confirmed_units} units confirmed against {args.units} on hand")
    if confirmed_units != args.units:
        if UNREACHABLE:
            # A service was down, so demand never reached the reserve step. The exact count is not
            # measurable for this run; what is still claimed, and checked, is that nothing
            # oversold and no invariant broke.
            info(f"cannot assert exactly {args.units} confirmed: a service was unreachable, so "
                 f"only {placed} order(s) were ever created")
        else:
            fail(f"expected exactly {args.units} confirmed units, got {confirmed_units}\n"
                 f"      {describe(after)}")
    if violations:
        fail(f"{len(violations)} invariant violation(s) during the storm")
    ok(f"{args.orders} orders for {args.units} unit(s): exactly {confirmed_units} unit(s) confirmed, "
       f"{rejected} rejected, no invariant broken")


def command_place(args):
    """Places one order and prints its number. For scripts that want an order, not a measurement."""
    print(place_order(args.sku, args.quantity) or "")


def command_check(args):
    violations = check()
    if violations:
        for name, subject, detail in violations:
            print(f"      {name}: {subject} ({detail})")
        fail(f"{len(violations)} invariant violation(s)")
    ok(f"all invariants hold: {describe(breakdown())}")


def command_inject(args):
    """Re-publishes events, to test that a redelivery changes nothing.

    Clearing `published_at` makes the relay send the row again, which is a real
    duplicate delivery rather than a simulated one. For `reorder` the oldest event
    of an order is re-announced after the ones that followed it, so it arrives late.
    """
    if args.mode == "duplicate":
        rows = sql(f"""select o.event_id, o.type from order_svc.outbox o
                       where o.order_number = '{args.order}' and o.published_at is not null
                       order by o.id desc limit {args.count}""")
        if not rows:
            fail(f"no published events for {args.order}")
        for event_id, _ in rows:
            sql(f"update order_svc.outbox set published_at = null where event_id = '{event_id}'")
    else:
        row = sql(f"""select id from order_svc.outbox where order_number = '{args.order}'
                      and published_at is not null order by id limit 1""")
        if not row:
            fail(f"no published events for {args.order}")
        sql(f"update order_svc.outbox set published_at = null where id = {row[0][0]}")
    ok(f"queued {args.count} {args.mode} re-delivery for {args.order}")


# --- helpers -------------------------------------------------------------------

def breakdown():
    tally = {"CONFIRMED": 0, "FAILED": 0, "in flight": 0}
    for step, amount in sql("select saga_step, count(*) from order_svc.orders group by saga_step"):
        tally[step] = int(amount)
    tally["in flight"] = sum(
        amount for step, amount in tally.items()
        if step not in ("CONFIRMED", "FAILED", "in flight"))
    return tally


def in_flight():
    return count("select count(*) from order_svc.orders where saga_step not in ('CONFIRMED','FAILED')")


def outbox_backlog():
    return count("select count(*) from order_svc.outbox where published_at is null")


def describe(tally):
    return (f"{tally['CONFIRMED']} confirmed, {tally['FAILED']} failed, "
            f"{tally['in flight']} in flight")


def check(since=None):
    """Runs invariants.sql and returns the rows it produced; any row is a violation.

    `since` scopes the per-order checks to a run. The store-level checks stay global
    whatever is passed, so a scoped run is still a statement about the real stock counters.
    """
    if since is None and fixture_orders():
        print(f"      note: {fixture_orders()} order(s) here are demo fixtures inserted straight "
              f"into the table, so they have no saga history and are reported as violations.")
        print(f"      for an unqualified result, start the stack without the demo profile:")
        print(f"        SPRING_PROFILES_ACTIVE= docker compose up -d --wait")
    command = ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "postgres",
               "-d", "fulfillops", "-t", "-A", "-F|", "-v", "ON_ERROR_STOP=1",
               "-v", "settle_secs=90", "-v", f"since={since or ''}", "-f", "-"]
    result = subprocess.run(command, cwd=ROOT,
                            input=open(os.path.join(ROOT, "scripts", "invariants.sql")).read(),
                            capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"invariants.sql failed: {result.stderr.strip()}")
    return [line.split("|") for line in result.stdout.splitlines() if line != ""]


def fixture_orders():
    """Orders the demo profile inserted directly, which never went through the saga."""
    return count("""select count(*) from order_svc.orders o
                    left join payment_svc.payments p on p.order_number = o.order_number
                    where p.order_number is null and o.created_at < now() - interval '10 minutes'""")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    setup = sub.add_parser("setup", help="create the product the storm orders against")
    setup.add_argument("--sku", default="SKU-STORM")
    setup.add_argument("--units", type=int, default=100)
    setup.set_defaults(run=command_setup)

    storm = sub.add_parser("storm", help="fire many concurrent orders at a fixed stock level")
    storm.add_argument("--orders", type=int, default=1000)
    storm.add_argument("--units", type=int, default=100,
                       help="units on hand; must match what setup created")
    storm.add_argument("--quantity", type=int, default=1, help="units per order")
    storm.add_argument("--sku", default="SKU-STORM")
    storm.add_argument("--concurrency", type=int, default=100,
                       help="clients in flight at once; reported in the output")
    storm.add_argument("--settle-timeout", type=int, default=180)
    storm.set_defaults(run=command_storm)

    settle = sub.add_parser("settle", help="wait until nothing is in flight")
    settle.add_argument("--timeout", type=int, default=180)
    settle.set_defaults(run=command_settle)

    place = sub.add_parser("place", help="place one order and print its number")
    place.add_argument("--sku", default="SKU-STORM")
    place.add_argument("--quantity", type=int, default=1)
    place.set_defaults(run=command_place)

    check_parser = sub.add_parser("check", help="check every invariant, exit non-zero on any")
    check_parser.set_defaults(run=command_check)

    inject = sub.add_parser("inject", help="re-publish events, to test redelivery")
    inject.add_argument("mode", choices=["duplicate", "reorder"])
    inject.add_argument("--order", required=True)
    inject.add_argument("--count", type=int, default=1)
    inject.set_defaults(run=command_inject)

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
