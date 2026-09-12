# PersonalHub capsule boundaries

This document defines what **fully capsule-isolated** means for PersonalHub.

## Boundary model

Each `:feature:*` Gradle module is an implementation capsule. Code outside that module must not know its repositories, DAOs, persistence helpers, UI implementation, performance helpers, or other private implementation packages.

The allowed dependency direction is:

`contracts -> core -> feature -> app`

More precisely:

- `:contracts:*` may not depend on `:core:*`, `:feature:*`, or `:app`.
- `:core:*` may depend on contracts and other appropriate core modules, but never on a feature or the app.
- A `:feature:*` module may depend on contracts/core, but never on another feature implementation or the app.
- `:app` is the composition/navigation root. When it must call into a feature, it may import only an explicit feature package containing `.api.` or `.hub.`.

`hub` packages are integration adapters implementing neutral Hub contracts. `api` packages are intentionally tiny host-facing entrypoints. Adding a new host-facing feature import anywhere else is an architecture failure.

## Runtime ownership

A feature owns construction of its implementation objects unless a neutral core contract explicitly requires composition in `:app`.

WordPulse therefore resolves its repository through its own feature-local runtime instead of requiring `PersonalHubApplication` to inherit a WordPulse application class. Timer startup instrumentation is private implementation; the host calls only `TimerStartupApi`.

Hub adapters are intentionally composed by `PersonalHubApplication`, because their purpose is to bridge feature-owned records to the neutral Hub contracts. Their implementation packages are therefore part of the explicit `hub` public surface.

## Persistence ownership

`:core:database` owns the canonical `personalhub.db` lifecycle and Room database assembly. `:contracts:database` owns stable database contracts needed across modules. Feature workflow/repository implementation must remain inside its owning feature.

A shared database does not make feature repositories public. Cross-feature behavior must use a neutral contract, Hub adapter, or deliberately defined narrow API rather than importing another feature implementation.

## Internal feature layering

Capsule isolation is an **external module boundary**, not a requirement that every class inside one feature be in a separate Gradle module. UI, domain and persistence code may collaborate inside their owning feature without leaking that implementation to another feature or to `:app`.

Additional clean-architecture refactors inside an already isolated feature should be driven by maintainability or testability needs, not performed merely to satisfy capsule isolation.

## Automated gate

Run:

```bash
./gradlew checkArchitectureBoundaries
```

The gate fails when it detects any of the following:

- a feature-to-feature Gradle dependency;
- a feature depending on `:app`;
- core depending on a feature/application;
- contracts depending on implementation modules;
- a source-level import from one feature implementation into another;
- a source-level core import of a feature implementation;
- an `:app` import of feature implementation outside an explicit `.api.` or `.hub.` surface;
- feature persistence contracts or implementation returning to generic core locations already protected by the legacy ownership checks.

A change that modifies module wiring, package ownership, a public integration entrypoint, or persistence ownership is not complete until this gate passes.

## Acceptance criterion

PersonalHub is considered **100% capsule-isolated** when `checkArchitectureBoundaries` passes and all feature access from the host is represented by explicit `api`/`hub` surfaces. Device/runtime tests can still be necessary to prove behavior, but they do not redefine the architectural boundary.
