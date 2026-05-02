# OpenAPI Plan For KtKit

Related docs:

- [`01-ktkit-current-state.md`](./01-ktkit-current-state.md): current KtKit route/runtime constraints and example setup
- [`02-ktor-openapi-references.md`](./02-ktor-openapi-references.md): Ktor OpenAPI, `.describe {}`, Swagger UI, and integration constraints
- [`03-kotlinx-schema-references.md`](./03-kotlinx-schema-references.md): schema-model separation and kotlinx-schema references

## Goal

Implement a KtKit-native API contract layer that can export OpenAPI for JVM/Ktor without breaking KMP support or the current `AbstractRestHandler` authoring style.

Success for this plan means two things:

- the work is implementable incrementally without a large speculative rewrite
- the document reflects the real cost drivers and hidden work, not just the desired end-state API

The plan must preserve these existing properties:

- KtKit routes are authored through handler classes and uppercase helpers like `GET` and `POST`, not through a separate routing DSL.
- Request extraction currently lives in `HttpContext` helpers such as `pathVariable`, `queryParam`, `header`, and `body`.
- KtKit already normalizes multiple failure styles at runtime: `Raise<ErrorSpec>`, returned `Either<ErrorSpec, T>`, `Result<T>`, and thrown `RuntimeError`.
- Downstream apps may translate internal errors to public errors at the route boundary through a route-local wrapper such as `exposePublicly(log)`.
- Core contract and schema logic must stay in `commonMain`. OpenAPI rendering can be JVM-only.

## MVP Agent Start

This section is the concise implementation brief for an agent starting the first MVP. The rest of the document explains
the reasoning and later phases.

Build only this first:

1. Add a minimal contract IR in `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/contract/`:
   `EndpointContract`, `SuccessSpec`, `ParamSpec`, `ParamLocation`, `BodySpec` if needed, and `RouteContractRegistry`.
2. Use `kotlinx.serialization.KSerializer<T>` or a small KMP-safe schema token around it for the first type capture path.
3. Add optional `contract` parameters to the existing `AbstractRestHandler.GET/POST/PUT/PATCH/DELETE` helpers.
4. Make those helpers call `RouteContractRegistry.register(...)` during route registration when `contract != null`.
5. Add one app-scoped registry owned by `Application`, and finalize it after the existing handler auto-registration block in
   `Application.Configurer.configure()`.
6. Add the JVM-only OpenAPI module only after the registry path works. The first exporter may emit a small OpenAPI JSON
   subset: paths, methods, summary, parameters, and one success response.
7. Update one route in `example/` to use `contract = ...` and prove the registry contains that contract.

Do not build these in the MVP:

- KSP annotations
- compiler-plugin inference
- Ktor `.describe {}` integration
- Redoc or Swagger UI
- full schema coverage
- automatic inference from route handler bodies
- multiple success variants or streaming responses

The MVP acceptance check is narrow:

- existing routes still compile without contracts
- one documented example route registers a contract
- the registry can be finalized and read as an immutable snapshot
- on JVM, the snapshot can be converted into a minimal `/openapi.json` document in the optional module

## Primary Design Decisions

### 1. Source of truth

The source of truth should be a KtKit contract model owned by `ktkit`, not raw Ktor OpenAPI annotations and not generated OpenAPI JSON.

Reason:

- Current route authoring is centered on `AbstractRestHandler` and `Route.GET/POST/...`: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:354`
- Input extraction is wrapped by KtKit helpers, so Ktor compiler inference will miss important semantics unless KtKit exposes its own metadata layer: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/HttpContext.kt:103`
- Route discovery is centralized in application bootstrap, so the natural attachment point is KtKit route registration: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/Application.kt:302`

### 2. KMP boundary

Split the implementation into:

- `commonMain`: endpoint contract IR, parameter/body/response/error specs, schema abstraction, contract builders, route metadata registration API
- `jvmMain` or a JVM-only module: Ktor/OpenAPI adapter and serving endpoints

Do not make OpenAPI classes part of the common API surface.

### 3. Type and schema capture

Type and schema capture must be a first-class part of the vanilla design, not a later refinement.

Reason:

- the current `Route.GET/POST/...` helpers are generic but not reified, so `GET<T>` alone cannot recover `T` at runtime: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:354`
- there is no existing KtKit commonMain type-introspection layer that can be retrofitted after the IR is already designed
- OpenAPI export is only as good as the schema/type tokens captured by the contract model

Therefore:

- every success/body/parameter/error payload spec must capture enough information up front to derive a schema later
- the vanilla API should prefer explicit schema capture through `contract<T>`, `body<T>()`, `pathParam<T>()`, and similar builders
- an inline reified helper can be added later for ergonomics, but the core IR must not depend on JVM-only reflection

### 4. Error model

Public API errors are not always identical to service/internal errors.

Example:

- an application may translate internal `ErrorSpec` values into a smaller public error hierarchy through a wrapper like `exposePublicly(log)`
- public endpoints should expose the translated public errors, not raw infrastructure or third-party client errors

Therefore:

- `ktkit` should own the contract mechanism
- application code should own app-specific public error sets and translator wrappers

### 5. Plugin strategy

Implementation must be usable without KSP or compiler plugins.

Recommended progression:

1. Vanilla API first
2. KSP second for boilerplate reduction
3. Compiler-plugin inference later only for recognized KtKit DSL patterns

### 6. Rollout style

The implementation should be additive-first and migration-safe.

Therefore:

- existing `GET/POST/...` call sites must keep compiling without contract declarations
- contract support should be introduced through overloads or optional parameters rather than by replacing the current route API
- the OpenAPI exporter should live in a dedicated JVM-only module from the start
- the contract IR and reusable contract DSL should remain in `commonMain`

## Target API

### Vanilla target

```kotlin
private val storeParam =
    pathParam<Store>("store", description = "Store code")

private val itemIdParam =
    pathParam<String>("itemId", description = "Catalog item identifier")

private val originalTokenHeader =
    headerParam<String>("x-original-token", description = "Original access token")

private val PublicCatalogErrors =
    errorSet {
        error<PublicApiError.Forbidden>()
        error<PublicApiError.ProfileIncomplete>()
        error<PublicApiError.UpstreamUnavailable>()
        error<PublicApiError.ServiceUnavailable>()
    }

override fun Route.routes() {
    GET(
        "/{itemId}",
        contract = contract<CatalogItem> {
            summary = "Fetch one catalog item"
            params(storeParam, itemIdParam, originalTokenHeader)
            errors(PublicCatalogErrors)
            tag("Catalog")
        }
    ) {
        exposePublicly(log) {
            val token = originalTokenHeader.required()
            val store = storeParam()
            val itemId = itemIdParam()

            transaction {
                profileService.requireProfile(currentUserId())
                catalogService.getItem(store, itemId, token)
            }
        }
    }
}
```

Properties:

- no KSP required
- no duplicate `ok()` declaration; in the vanilla path the success schema comes from `contract<CatalogItem>`
- parameter handles are reusable for docs and extraction
- error sets are reusable across endpoints

### Final ergonomic target with KSP + optional compiler support

```kotlin
@PublicErrors(
    PublicApiError.Forbidden::class,
    PublicApiError.ProfileIncomplete::class,
    PublicApiError.UpstreamUnavailable::class,
    PublicApiError.ServiceUnavailable::class,
)
object PublicCatalogErrors

@PublicEndpoint(
    summary = "Fetch one catalog item",
    errors = PublicCatalogErrors::class,
)
GET<CatalogItem>("/{itemId}") {
    exposePublicly(log) {
        val token = header("x-original-token").asString()
        val store = pathVariable("store").asEnum<Store>()
        val itemId = pathVariable("itemId").asString()

        transaction {
            profileService.requireProfile(currentUserId())
            catalogService.getItem(store, itemId, token)
        }
    }
}
```

Notes:

- KSP can generate error sets and contract fragments from annotations.
- an inline reified overload could later allow `GET<T>` to supply success schema ergonomically, but that is not a requirement for the first ship
- A compiler plugin would be needed if the body itself should implicitly contribute parameter metadata from calls like `pathVariable("store").asEnum<Store>()`.
- This final form is the aspiration, not the required first ship.
- v1 should stay DSL-only; annotations belong to later KSP ergonomics.

## Proposed Code Structure

### New commonMain package

Create a new package under:

- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/contract/`

Suggested files:

- `EndpointContract.kt`
- `ParamSpec.kt`
- `BodySpec.kt`
- `SuccessSpec.kt`
- `ErrorResponseSpec.kt`
- `ErrorSet.kt`
- `ParamHandle.kt`
- `ContractDsl.kt`
- `RouteContractRegistry.kt`
- do not introduce annotation types into the v1 surface

### New JVM-only module

Preferred:

- add a dedicated module, for example `ktkit-ktor-openapi`

Reason:

- keeps `ktkit` KMP-safe
- isolates Ktor OpenAPI experimental/runtime dependencies
- keeps OpenAPI export optional

## Core vs OpenAPI Module Responsibilities

The implementation should be split so that `ktkit` core can carry portable route contracts without forcing OpenAPI
generation, Redoc, Swagger UI, or JVM-only dependencies onto every application.

| Area | `ktkit` core | `ktkit-ktor-openapi` or JVM-only module |
| --- | --- | --- |
| Contract declaration | Owns `contract<T> { ... }`, `EndpointContract`, parameter/body/success/error specs, and error sets. | May add convenience config around export, but should not define the canonical contract model. |
| Route authoring | Keeps the existing `AbstractRestHandler` style and uppercase helpers like `GET`, `POST`, `PUT`, `PATCH`, and `DELETE`. | Does not replace route authoring. It only consumes registered contracts. |
| Contract registration | `GET/POST/...` call `RouteContractRegistry.register(...)` when a non-null contract is supplied. App code should not call `register()` manually. | Reads the finalized registry snapshot. It should not be required for registration to work. |
| Registry lifetime | Owns one app-scoped `RouteContractRegistry`, registered in application DI or otherwise held by the `Application` instance. Avoid a process-global Kotlin `object`. | Uses the app-scoped registry instance provided by core. |
| Registry synchronization | MVP can use `mutableListOf` because registration is startup-only. Add a finalize/freeze guard so writes after finalization fail fast. | Treats the registry as read-only. |
| Registry finalization | `Application.Configurer.configure()` calls `finalizeRoutes()` after the `routing { di.getAll<AbstractRestHandler>().forEach { with(it) { routes() } } }` block completes. | Runs after finalization through an extension hook and generates from the stable snapshot. |
| Application bootstrap integration | Keeps current `Application(..., configure = { ... }).start()` pattern. Do not require downstream apps to call a separate `installKtKit(...)`. | Provides optional `openApi { ... }` config as an extension when the module is imported. |
| Optional feature hook | Provides a generic post-route hook such as `afterRoutes { ... }` or an equivalent internal extension point, executed after route registration and registry finalization. | Implements `Application.Configurer.openApi { ... }` by registering an `afterRoutes` block that installs `/openapi.json` and optional docs UI. |
| When module is absent | Contracts can still compile and register in core. No OpenAPI generation, `/openapi.json`, `/docs`, Redoc, Swagger UI, or OpenAPI config API is available. | Not present. No dependency or runtime cost. |
| OpenAPI generation | No OpenAPI DTOs or Ktor OpenAPI classes in the core API surface. | Converts `RouteContractRegistry` snapshots into OpenAPI JSON. Prefer direct generation from KtKit IR first. |
| Ktor `.describe {}` | Core should not require it. It can optionally attach metadata later only as an implementation detail. | May bridge KtKit contracts into Ktor `.describe {}` later if it proves useful, but this is not the v1 source of truth. |
| `/openapi.json` hosting | Does not host by default. May expose enough hooks for optional modules to install routes after finalization. | Hosts `/openapi.json` when `openApi { enabled = true }` is configured. |
| Docs UI | No Redoc or Swagger UI dependency. | Can host Redoc for read-only docs and Swagger UI for interactive bearer-token testing. |
| Auth in docs | Core documents auth requirements through contract/security metadata only. | Emits OpenAPI security schemes, for example bearer auth. Swagger UI can provide an `Authorize` button; Redoc Community Edition should be treated as read-only. |
| User overrides | Core should expose stable hooks, not OpenAPI-specific config. | `openApi { ... }` should allow overriding title/version, JSON path, Redoc path, Swagger path, servers, auto route installation, and generator options. |

Recommended optional-module shape:

```kotlin
Application(
    name = "...",
    conf = Application.Conf(),
    configure = {
        openApi {
            enabled = true
            title = "Example API"
            version = "1.0.0"
            jsonPath = "/openapi.json"
            docs = OpenApiDocs.Redoc(path = "/docs")
        }
    }
).start()
```

If `ktkit-ktor-openapi` is not imported, `openApi { ... }` should not compile. This is preferable to a silent no-op
because it keeps optional dependencies explicit.

Recommended override shape:

```kotlin
openApi {
    enabled = true
    autoInstallRoutes = false
}
```

With `autoInstallRoutes = false`, application code can host the generated document manually through normal Ktor
configuration while still reusing the generator and finalized registry.

## Registry Lifecycle

Registration should follow the existing KtKit bootstrap rather than introducing a new application install style.

Current route installation happens in `Application.Configurer.configure()`:

```kotlin
routing {
    di.getAll<AbstractRestHandler>().forEach {
        log.info("Registering REST Handler: ${it::class.simpleName}")
        with(it) { routes() }
    }
}
```

The contract lifecycle should therefore be:

1. `Application` creates or owns one app-scoped `RouteContractRegistry`.
2. `Application.Configurer.configure()` registers that registry in DI or exposes it through a scoped route-registration context.
3. Each `AbstractRestHandler.GET/POST/...` helper calls `register(...)` when `contract` is supplied.
4. After all handler `routes()` calls complete, `Application.Configurer.configure()` calls `finalizeRoutes()`.
5. The OpenAPI module, if enabled, installs `/openapi.json` and docs UI after finalization.
6. The docs generator reads only the finalized snapshot.

This keeps downstream app setup minimal. The example app should continue to register handlers only through DI:

```kotlin
di {
    singleOf(::TestRestHandler) { bind<AbstractRestHandler>() }
}
```

Route authors opt in per endpoint by adding contract metadata:

```kotlin
GET("/items/{id}", contract = contract<ItemDto> { ... }) {
    ...
}
```

They should not manually call `register()` or `finalizeRoutes()`.

`postConfigure` should not be part of the registry write path. It runs after `Configurer.configure()` and is better suited
for startup side effects or reading already-finalized metadata. OpenAPI route installation should happen inside the
normal configure flow after route registration and before or through a dedicated post-route hook.

## Phased Implementation

### Phase 0. Spike the hard parts first

Before building the full DSL, prove the two highest-risk seams with a minimal throwaway prototype:

- capture schema/type information for one `@Serializable` success type and one parameter type in `commonMain`
- register one route contract through `GET(..., contract = ...)` and verify it can be discovered later from a KtKit-owned registry

Exit criteria:

- no JVM reflection is required for the prototype
- the prototype works without breaking an existing handler
- the chosen schema token shape is good enough to continue with the real IR

If this spike fails cleanly, revise the API before investing in the full DSL.

Milestone target:

- update the example app with one catalog-style endpoint
- focus the first milestone on one endpoint end to end

### Phase 1. Contract IR in commonMain

Implement:

- `EndpointContract<T>`
- `ParamSpec<T>` with location enum (`PATH`, `QUERY`, `HEADER`)
- `BodySpec<T>`
- `SuccessSpec<T>`
- `ErrorResponseSpec<E : ErrorSpec>`
- `ErrorSet`
- a KMP-safe schema token abstraction backed initially by `kotlinx.serialization`

Constraints:

- no OpenAPI classes
- no Ktor-specific classes
- phase 1 is not complete until all generic payload specs carry enough type/schema info to map later
- do not defer schema capture to a later phase; the IR shape depends on it

Preferred v1 approach:

- capture `kotlinx.serialization`-backed type/schema information at builder time
- store enough metadata to derive JSON Schema or OpenAPI schemas later without JVM reflection
- avoid introducing `kotlinx-schema` as a required dependency in the first ship

Delivery note:

- phase 1 should target only the subset of schema capture needed by the first example endpoints
- do not try to solve every serialization edge case before the first registry-to-OpenAPI path is working

### Phase 2. Reusable parameter handles

Implement handles that carry both:

- runtime extraction semantics
- reusable parameter metadata

Target helpers:

- `pathParam<T>(name, description = ...)`
- `queryParam<T>(name, description = ..., required = false)`
- `headerParam<T>(name, description = ..., required = false)`

Runtime usage target:

- `storeParam()`
- `originalTokenHeader.required()`
- `failQuery.orDefault(false)`

These helpers should reuse KtKit parsing semantics currently implemented by `HttpContext.Var`:

- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/HttpContext.kt:103`
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/HttpContext.kt:108`
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/HttpContext.kt:118`
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/HttpContext.kt:131`

Design constraint:

- parameter handles should be thin wrappers over existing `HttpContext` extraction and parsing behavior, not a parallel parsing system
- missing-parameter, enum parsing, and primitive conversion behavior must stay aligned with `HttpContext.Var`

### Phase 3. Route overloads and contract attachment

Extend `AbstractRestHandler` verb helpers so they can accept an optional contract object.

Affected code:

- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:354`
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:375`
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:396`
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:417`
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:438`

Target shape:

```kotlin
GET(
    "/{itemId}",
    contract = contract<CatalogItem> { ... }
) { ... }
```

Implementation detail:

- populate a KtKit-owned route contract registry during route registration
- attach contract metadata to Ktor `Route` only as an implementation detail if that helps debugging or later integration
- preserve all current runtime behavior
- make OpenAPI export read from the KtKit registry rather than depending on traversal of Ktor internals
- keep the registry snapshot-oriented in v1
- store base path and route-relative path separately in the registry, then export the combined final path

### Phase 4. Framework error defaults

Auto-add framework-level response metadata during OpenAPI export.

These should come from KtKit behavior, not route author boilerplate:

- auth missing -> `Unauthorized`
- permission/role failure -> `Forbidden`
- malformed request body -> `MalformedRequestBody`
- missing parameter -> `MissingParameter`
- unsupported enum value -> `UnsupportedEnumValue`
- common RFC 9457 response envelope -> `ApiError`

Export rules:

- apply framework defaults conditionally based on actual route behavior
- document `401` only when an auth extractor is configured and no default-user path satisfies the route
- document `403` only when KtKit role or permission checks are configured
- document request-body parse failures only when a request body is declared
- document missing-parameter and unsupported-enum failures only when declared params can produce them
- merge framework and declared public errors when they represent different causes for the same status
- perform best-effort deduplication when variants collapse to the same documented shape

Relevant code:

- auth/permission flow: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:122`
- permission failure: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:145`
- error response envelope: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/ApiError.kt:19`

### Phase 5. Explicit public error sets

Add reusable error groups for public endpoint contracts.

App ownership:

- KtKit owns `ErrorSet` and the contract DSL APIs.
- application code owns `PublicCatalogErrors`, translator wrappers, and app-specific public error hierarchies.

V1 rule:

- manually declared public error sets are sufficient
- translator-wrapper ergonomics belong later

### Phase 6. JVM OpenAPI adapter

Add a JVM-only adapter that converts KtKit route contracts into OpenAPI.

Options:

1. generate OpenAPI directly from the KtKit IR
2. attach Ktor runtime `.describe {}` metadata and let Ktor assemble/serve the document

Recommendation:

- phase 1 adapter can generate the OpenAPI document directly from the KtKit IR
- optional later integration can bridge to Ktor route annotations if that reduces duplication or improves UI integration

Delivery note:

- generate the document directly from the KtKit IR first
- do not spend time integrating with Ktor OpenAPI runtime metadata until the direct exporter is already useful

### Phase 7. Serving documentation

Expose:

- `/openapi.json`
- optional docs UI endpoint

These should live in JVM-only code or in a JVM-only module.

V1 rule:

- `/openapi.json` must be application opt-in
- docs UI is optional and should not block JSON export

### Phase 8. KSP support

Only after vanilla is complete.

KSP responsibilities:

- generate `ErrorSet` objects from annotations like `@PublicErrors(...)`
- generate reusable contract fragments from route/service annotations
- validate route templates against declared params
- optionally generate DTO/error schema registrations

KSP should not be responsible for parsing handler bodies to infer arbitrary runtime semantics.

Keep out of v1:

- `@PublicEndpoint`
- annotation-first contract authoring

### Phase 9. Optional compiler-plugin support

Only after the vanilla and KSP APIs are stable.

Compiler support can later infer parameter usage from KtKit DSL calls such as:

- `header("x-original-token").asString()`
- `pathVariable("store").asEnum<Store>()`
- `body<CreateRequest>()`

This is optional because it increases long-term maintenance cost and Kotlin compiler coupling.

## Detailed Technical Notes

### Error translation must be modeled at the route boundary

Do not infer public errors from service signatures alone.

Reason:

- `ServiceVariations` demonstrates multiple runtime failure styles and transaction behavior differences: `example/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/example/test/ServiceVariations.kt:25`
- a public translation wrapper such as `exposePublicly(log)` may convert internal `ErrorSpec` values into a smaller public error set

Therefore:

- public contract declaration belongs at the endpoint boundary
- service contracts can optionally contribute reusable fragments, but must not be treated as the full public contract

### Keep route authoring recognizable

Do not replace the current route style with a separate document builder API.

Current style to preserve:

- handler class + `Route.routes()`: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:74`
- uppercase verb helpers: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:354`

### Do not duplicate success metadata

Avoid `ok()` in the common path.

Preferred rule:

- in the vanilla API, success payload comes from `contract<T>`
- later inline-reified overloads may let `GET<T>` contribute the same metadata ergonomically
- success status defaults to the same values already used by `AbstractRestHandler`

### Keep reuse simple in v1

V1 should rely on plain Kotlin reuse instead of first-class contract-fragment semantics.

Recommended approach:

- shared params and shared error sets can be plain values
- common contract assembly can use normal helper functions
- defer first-class fragment merge semantics until real usage shows what must be composed

### Define the v1 contract scope explicitly

The first ship should target the response shapes KtKit already handles well and defer the rest.

Recommended v1 scope:

- one primary success payload schema per endpoint
- one primary success status code per verb helper, with explicit override support
- `Unit` responses represented as empty response bodies
- request bodies modeled for JSON payloads
- one catalog-style example endpoint updated end to end in `example/`
- RFC 9457 Problem Details envelope for errors, with typed `data` when known

Explicitly defer unless a real use case appears during implementation:

- streaming response schemas
- non-JSON or streaming OpenAPI response support, even if runtime support already exists
- multiple documented success payload variants for one endpoint
- automatic inference from arbitrary handler body code without explicit contract builders

### Estimate the actual cost drivers

The expensive parts are not evenly distributed across the phases.

Highest cost / highest uncertainty:

- schema token design in `commonMain`
- mapping `ErrorSpec` types into RFC 9457 Problem Details responses with typed `data` when known and opaque `data` fallback otherwise
- designing a registry lifecycle that works cleanly with Ktor route registration
- producing stable OpenAPI component names and references

Moderate cost:

- additive `GET/POST/...` overloads
- reusable parameter handles built on top of `HttpContext.Var`
- framework-default error response merging
- snapshot tests and example app coverage

Lower cost:

- serving `/openapi.json`
- optional docs UI wiring once JSON export already exists

Implication:

- the project cost is dominated by contract/schema design and export correctness, not by route overload plumbing
- the plan should be tracked by working milestones rather than by file-count or class-count

### Suggested delivery milestones

Use milestones that produce demonstrable value and surface risk early.

Milestone A:

- the example app contains one catalog-style endpoint using the contract DSL
- the route contract is captured in a registry
- no OpenAPI generation yet

Milestone B:

- the same endpoint exports valid OpenAPI JSON
- params, success payload, and one error set are represented
- framework-default errors are merged in
- `/openapi.json` is exposed through an explicit opt-in hook

Milestone C:

- multiple handlers can export through one application-level endpoint
- example app demonstrates translated public errors
- snapshot tests lock the output format

Milestone D:

- optional docs UI
- optional KSP ergonomics

If Milestone B proves more expensive than expected, stop there before adding KSP or UI work.

### Make non-goals explicit

To keep the plan honest about cost, the first implementation should not aim to:

- infer contracts from arbitrary handler code
- document every Ktor feature or content type
- perfectly model every Kotlin type shape on day one
- redesign the current KtKit routing style
- solve compiler-plugin ergonomics in parallel with the vanilla API
- introduce first-class contract-fragment composition before plain Kotlin reuse has been evaluated

### Module and dependency touchpoints

Current dependency versions:

- Ktor `3.4.2`: `gradle/libs.versions.toml:15`
- `kotlinx-serialization` `1.11.0`: `gradle/libs.versions.toml:13`

Current KtKit dependencies already include:

- `ktor-server-core`: `gradle/libs.versions.toml:44`
- `ktor-server-content-negotiation`: `gradle/libs.versions.toml:48`
- `ktor-serialization-kotlinx-json`: `gradle/libs.versions.toml:53`

Root modules today:

- `ktkit`: `settings.gradle.kts:13`
- `ktkit-ktor-httpclient`: `settings.gradle.kts:14`
- `example`: `settings.gradle.kts:18`

If a new JVM OpenAPI module is introduced, it should be added here.
This plan assumes that module is part of the first implementation.

## Testing Strategy

### Unit tests in commonTest

Add tests for:

- contract DSL construction
- parameter handle metadata
- reusable `ErrorSet`
- framework error default merging
- schema extraction from `@Serializable` types
- explicit serializer or schema-token capture for success, body, and parameter specs

### JVM tests

Add tests for:

- route metadata attachment during `GET/POST/...`
- route registry population during `GET/POST/...`
- OpenAPI generation from registered routes
- public error translation scenarios with a test translator similar to `exposePublicly`
- current success-status defaults for `GET`, `POST`, `PUT`, `PATCH`, and `DELETE`
- conditional framework-error inclusion during export
- best-effort deduplication for same-status framework and public errors

### Acceptance criteria

Do not call the implementation complete until all of the following are true:

- existing handlers still compile without contract declarations
- one catalog-style example handler uses the new contract API end to end
- `/openapi.json` is generated from the registry, not handwritten
- generated OpenAPI includes at least params, success payload, explicit public errors, and framework-default errors
- error docs preserve the RFC 9457 Problem Details envelope and include typed `data` when schema-known, otherwise opaque `data`
- snapshot tests protect the exported document from accidental regressions

### Golden tests

Add snapshot/golden tests for exported OpenAPI JSON.

Use at least:

- a route with path/query/header params
- a route with request body
- a route with public translated errors
- a route with framework-generated 400/401/403 docs

## Documentation Work

Update these project docs when implementation lands:

- `README.md:41` describe the new contract/OpenAPI capability in the "What it does" section
- `README.md:63` add a bullet under core module features for contract/OpenAPI support
- new usage examples under `example/` showing vanilla contracts first

Do not manually edit generated Dokka HTML under `docs/`; instead update KDoc on public APIs and regenerate docs.

## Handoff Checklist

- build the contract IR in `commonMain`
- make schema/type capture part of the initial IR, not a follow-up phase
- attach contracts to `Route` registration in `AbstractRestHandler`
- back contract discovery with a KtKit-owned route registry
- add reusable param handles
- add reusable error sets
- keep vanilla API fully usable without KSP
- keep the vanilla success-schema source explicit through `contract<T>`
- treat public error translation as endpoint-level contract semantics
- implement JVM-only OpenAPI export after the common contract model is stable
- update the catalog example under `example/` to demonstrate the first end-to-end contract/OpenAPI path
