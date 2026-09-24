# apps

**Purpose:** deployable applications.

**Public surface:** none - these are leaves of the dependency graph.

**May depend on:** `libs/*`, `platform/*`.

**Must NOT contain:** cross-app imports (`apps/api` and `apps/web` never reference each other).

**Owner:** backend reviewer (api), frontend reviewer (web).
