# Tradeoffs

The largest deliberate compromise is storage: both services keep data **in
memory**. The CS console uses H2 with `ddl-auto: create-drop` and reseeds itself
on boot, while the seller dashboard holds state in a `BehaviorSubject` that
resets on refresh. This makes the project instantly reproducible with zero
infrastructure — no Postgres instance, no migration tooling, no seed scripts to
keep in sync — at the cost of non-persistence and divergence between the two
stores. In production these would back onto a shared persistent datastore (the
seller service writing, the CS service reading the same orders), but for a
portfolio exercise demonstrating two different presentation stacks, isolation is
the point. The same reasoning justifies jQuery over a SPA for the CS console:
handing an SSR team a full framework would be the expensive migration this
project exists to *avoid*, and a few dozen lines of jQuery deliver the three
interactions a CS rep actually needs.