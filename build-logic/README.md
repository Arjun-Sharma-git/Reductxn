# build-logic

**Purpose:** Gradle convention plugins. The single place compiler and test settings are declared.

**Public surface:** `reductxn.java-conventions`, `reductxn.spring-conventions`, `reductxn.testing-conventions`.

**Policy:** Java 25, preview features off, `-Werror`, `-parameters`, `-Xlint:all` (RULE B9).

**Must NOT contain:** module-specific dependencies.

**Owner:** platform reviewer.
