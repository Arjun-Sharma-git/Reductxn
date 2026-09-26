-- A fixture migration, deliberately in test resources rather than beside a module's source.
--
-- MigrationsConfigurationTest needs two things a real deployment never provides: a schema with migrations in
-- it, to prove the strategy delegates rather than merely refusing; and a location with none, to prove it
-- refuses. This file is the first. It is also why SchemaOwnershipTest only reads
-- `src/main/resources/db/migration`: a test fixture is not a table any module owns.

CREATE TABLE fixture (id uuid PRIMARY KEY);
