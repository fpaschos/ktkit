# KtKit Current State References

This file captures the current KtKit code references and the downstream usage pattern that the implementation should preserve, without tying the plan to any internal project names.

## KtKit repo references

- `README.md:41` KtKit already advertises a bootstrap around Ktor with auto-registered REST handlers.
- `README.md:42` KtKit already standardizes auth, permissions, and API errors.
- `README.md:63` `Application` wrapper is a core feature.
- `README.md:64` `AbstractRestHandler` is already the route authoring abstraction.

## Route authoring and registration

- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:72` handler subclasses define URI base logic through `String.uri()`.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:74` handler subclasses own `Route.routes()`.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:112` request handling already normalizes execution through `handle(...)`.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:354` `GET` helper is the route authoring entry point today.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:375` `POST` helper is the route authoring entry point today.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:396` `PUT` helper is the route authoring entry point today.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:417` `PATCH` helper is the route authoring entry point today.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:438` `DELETE` helper is the route authoring entry point today.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/Application.kt:302` all handlers are auto-registered from DI.

## Request extraction helpers

- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/HttpContext.kt:103` path parameters are currently extracted through `pathVariable(name)`.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/HttpContext.kt:108` query parameters are currently extracted through `queryParam(name)`.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/HttpContext.kt:118` headers are currently extracted through `header(name)`.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/HttpContext.kt:131` request bodies are currently extracted through `body<T>()`.

These are the helpers a future compiler plugin would recognize if inference is ever added.

## Runtime error normalization

- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:148` handlers return through a normalized execution path.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:172` KtKit already wraps handler execution in an `Either<ErrorSpec, Any?>`.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:199` returned `Either` values are flattened by `normalizeHandlerValue`.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:248` successful `Result` values are unwrapped before response.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/AbstractRestHandler.kt:305` failures are rendered through the common `ApiError` path.
- `ktkit/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/api/rest/ApiError.kt:19` API errors are exposed through a common RFC 9457 style envelope.

## Example app error behavior

- `example/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/example/test/ServiceVariations.kt:25` `Result` variation.
- `example/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/example/test/ServiceVariations.kt:38` `Either<ErrorSpec, T>` variation.
- `example/src/commonMain/kotlin/io/github/smyrgeorge/ktkit/example/test/ServiceVariations.kt:47` `Raise<ErrorSpec>` variation.

These references explain why public API contracts must not rely on one error propagation mechanism.

## Downstream pattern to preserve

The implementation should support a common downstream pattern even though the public docs and examples should stay domain-neutral:

- a route reads headers and path params directly inside the route body
- a route-local wrapper such as `exposePublicly(log)` wraps the body
- that wrapper accepts a block raising generic `ErrorSpec`
- that wrapper translates internal `ErrorSpec` values into a smaller public error hierarchy through `withError`
- infrastructure or upstream client failures may collapse into stable public errors such as `ServiceUnavailable` or `UpstreamUnavailable`

Implementation implication:

- public errors should be declared at the endpoint boundary
- translator wrappers such as `exposePublicly` should be treated as public contract boundaries
