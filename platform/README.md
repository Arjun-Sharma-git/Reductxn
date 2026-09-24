# platform

**Purpose:** cross-cutting NON-DOMAIN concerns - observability, persistence wiring, security config, test tooling.

**Public surface:** Spring configuration and infrastructure adapters.

**May depend on:** `libs/kernel` only, plus third-party infrastructure libraries.

**Must NOT contain:** domain concepts (`Obligation`, `NetPosition`, `Money`). If a domain word appears here, a boundary has leaked (section 4.1).

**Owners:** platform reviewer (all submodules).
