# OpenAPI Plan For KtKit

## Goal

Implement a KtKit-native API contract layer that can export OpenAPI for JVM/Ktor without breaking KMP support or the current `AbstractRestHandler` authoring style.

The plan must preserve these existing properties:

- KtKit routes are authored through handler classes and uppercase helpers like `GET` and `POST`, not through a separate routing DSL.
- Request extraction currently lives in `HttpContext` helpers such as `pathVariable`, `queryParam`, `header`, and `body`.
- KtKit already normalizes multiple failure styles at runtime: `Raise<ErrorSpec>`, returned `Either<ErrorSpec, T>`, `Result<T>`, and thrown `RuntimeError`.
- Downstream apps may translate internal errors to public errors at the route boundary through a route-local wrapper such as `exposePublicly(log)`.
- Core contract and schema logic must stay in `commonMain`. OpenAPI rendering can be JVM-only.

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

### 3. Error model

Public API errors are not always identical to service/internal errors.

Example:

- an application may translate internal `ErrorSpec` values into a smaller public error hierarchy through a wrapper like `exposePublicly(log)`
- public endpoints should expose the translated public errors, not raw infrastructure or third-party client errors

Therefore:

- `ktkit` should own the contract mechanism
- application code should own app-specific public error sets and translator wrappers

### 4. Plugin strategy

Implementation must be usable without KSP or compiler plugins.

Recommended progression:

1. Vanilla API first
2. KSP second for boilerplate reduction
3. Compiler-plugin inference later only for recognized KtKit DSL patterns

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
- no duplicate `ok()` declaration; success schema comes from `GET<CatalogItem>` or `contract<CatalogItem>`
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
- A compiler plugin would be needed if the body itself should implicitly contribute parameter metadata from calls like `pathVariable("store").asEnum<Store>()`.
- This final form is the aspiration, not the required first ship.

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
- `PublicEndpoint.kt` only if runtime-visible annotations are kept in the vanilla layer

### Optional new JVM package or module

Preferred:

- add a dedicated module later, for example `ktkit-ktor-openapi`

Reason:

- keeps `ktkit` KMP-safe
- isolates Ktor OpenAPI experimental/runtime dependencies
- keeps OpenAPI export optional

If a separate module is too much for phase 1, start in:

- `ktkit/src/jvmMain/kotlin/io/github/smyrgeorge/ktkit/api/openapi/`

and extract later.

## Phased Implementation

### Phase 1. Contract IR in commonMain

Implement:

- `EndpointContract<T>`
- `ParamSpec<T>` with location enum (`PATH`, `QUERY`, `HEADER`)
- `BodySpec<T>`
- `SuccessSpec<T>`
- `ErrorResponseSpec<E : ErrorSpec>`
- `ErrorSet`

Constraints:

- no OpenAPI classes
- no Ktor-specific classes
- all generic payload specs must carry enough type/schema info to map later

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

- attach route contract metadata to the Ktor `Route` during registration
- preserve all current runtime behavior

### Phase 4. Framework error defaults

Auto-add framework-level response metadata during contract export or route registration.

These should come from KtKit behavior, not route author boilerplate:

- auth missing -> `Unauthorized`
- permission/role failure -> `Forbidden`
- malformed request body -> `MalformedRequestBody`
- missing parameter -> `MissingParameter`
- unsupported enum value -> `UnsupportedEnumValue`
- common RFC 9457 response envelope -> `ApiError`

Relevant code:

- auth/permission flow: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:122`
- permission failure: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:145`
- error response envelope: `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/ApiError.kt:19`

### Phase 5. Explicit public error sets

Add reusable error groups for public endpoint contracts.

App ownership:

- KtKit owns `ErrorSet` and `@PublicEndpoint` / contract APIs.
- application code owns `PublicCatalogErrors`, translator wrappers, and app-specific public error hierarchies.

### Phase 6. Schema abstraction in commonMain

Implement a KMP-safe schema abstraction first using `kotlinx.serialization` descriptors.

Do not make `kotlinx-schema` a hard dependency for v1.

Why:

- KtKit already depends on `kotlinx.serialization`: `ktkit/build.gradle.kts:24`
- KtKit is multiplatform: `ktkit/build.gradle.kts:8`
- `kotlinx-schema` architecture is useful as a model for separating introspection from output format, but KtKit should not block on it

### Phase 7. JVM OpenAPI adapter

Add a JVM-only adapter that converts KtKit route contracts into OpenAPI.

Options:

1. generate OpenAPI directly from the KtKit IR
2. attach Ktor runtime `.describe {}` metadata and let Ktor assemble/serve the document

Recommendation:

- phase 1 adapter can generate the OpenAPI document directly from the KtKit IR
- optional later integration can bridge to Ktor route annotations if that reduces duplication or improves UI integration

### Phase 8. Serving documentation

Expose:

- `/openapi.json`
- optional docs UI endpoint

These should live in JVM-only code or in a JVM-only module.

### Phase 9. KSP support

Only after vanilla is complete.

KSP responsibilities:

- generate `ErrorSet` objects from annotations like `@PublicErrors(...)`
- generate reusable contract fragments from route/service annotations
- validate route templates against declared params
- optionally generate DTO/error schema registrations

KSP should not be responsible for parsing handler bodies to infer arbitrary runtime semantics.

### Phase 10. Optional compiler-plugin support

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

- success payload comes from `GET<T>` or `contract<T>`
- success status defaults to the same values already used by `AbstractRestHandler`

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

## Testing Strategy

### Unit tests in commonTest

Add tests for:

- contract DSL construction
- parameter handle metadata
- reusable `ErrorSet`
- framework error default merging
- schema extraction from `@Serializable` types

### JVM tests

Add tests for:

- route metadata attachment during `GET/POST/...`
- OpenAPI generation from registered routes
- public error translation scenarios with a test translator similar to `exposePublicly`

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
- attach contracts to `Route` registration in `AbstractRestHandler`
- add reusable param handles
- add reusable error sets
- keep vanilla API fully usable without KSP
- treat public error translation as endpoint-level contract semantics
- implement JVM-only OpenAPI export after the common contract model is stable
- add a new example under `example/` that demonstrates public error translation with a domain-neutral API surface
