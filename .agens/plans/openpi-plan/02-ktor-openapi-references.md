# Ktor OpenAPI References

References below are from Ktor docs fetched during planning. They should guide integration choices, but KtKit should still own its contract model.

Related docs:

- [`00-implementation-plan.md`](./00-implementation-plan.md): canonical implementation and rollout plan
- [`01-ktkit-current-state.md`](./01-ktkit-current-state.md): current KtKit route/runtime constraints
- [`03-kotlinx-schema-references.md`](./03-kotlinx-schema-references.md): schema-model separation guidance

## 1. Runtime assembly model

Source:

- <https://ktor.io/docs/server-openapi.html>
- <https://ktor.io/docs/openapi-spec-generation.html>

Key lines:

- `server-openapi.html` lines `3-12`: Ktor serves OpenAPI either from a static document or by generating the specification at runtime from routing metadata.
- `openapi-spec-generation.html` lines `3-10`: runtime generation is built from compiler-generated metadata and runtime route annotations.
- `openapi-spec-generation.html` lines `39-46`: Ktor's compiler extension does not define the final document; it only collects route metadata.

Implementation implication:

- KtKit can safely own the contract/source model and transform into OpenAPI later.
- Ktor OpenAPI should be treated as a renderer/adapter, not as the primary authoring model.
- The current plan chooses direct export from the KtKit IR first, with any Ktor runtime metadata bridge deferred.

## 2. What Ktor can infer today

Source:

- <https://ktor.io/docs/openapi-spec-generation.html>

Key lines:

- lines `61-72`: routing structure analysis infers merged paths, methods, and path params from the routing tree only.
- lines `75-140`: code inference recognizes common raw Ktor patterns:
  - `call.receive<T>()`
  - `call.respond<T>()`
  - `call.parameters["id"]`
  - `call.queryParameters["name"]`
  - `call.request.headers["X-Foo"]`

Implementation implication:

- these inference rules do not naturally see KtKit wrappers such as `body<T>()`, `pathVariable("id")`, or `header("x-original-token")`.
- KtKit should not depend on Ktor inference for correctness.

## 3. Runtime route annotations

Source:

- <https://ktor.io/docs/openapi-spec-generation.html>

Key lines:

- lines `147-155`: Ktor supports route enrichment through comments or runtime `.describe {}` metadata.
- lines `251-261`: runtime route annotations attach OpenAPI operation data directly to routes, and runtime values take precedence when metadata conflicts.

Implementation implication:

- a later JVM adapter may map the KtKit contract model to `.describe {}` if that is the easiest bridge into Ktor's OpenAPI/Swagger serving stack.
- KtKit should still keep its own abstraction so docs are not tied to Ktor-specific OpenAPI DSL shapes.

## 4. Schema inference hooks

Source:

- <https://ktor.io/docs/openapi-spec-generation.html>

Key lines:

- lines `270-277`: Ktor schema generation normally uses `kotlinx.serialization`.
- lines `278-290`: Ktor also supports reflection-based schema inference on JVM with custom adapters.

Implementation implication:

- KtKit should start with `kotlinx.serialization`-based schema extraction in `commonMain`.
- reflection-based alternatives should remain optional and JVM-only.

## 5. Serving the generated document

Source:

- <https://ktor.io/docs/openapi-spec-generation.html>
- <https://ktor.io/docs/server-openapi.html>

Key lines:

- lines `291-324`: Ktor can assemble an `OpenApiDoc` at runtime and serve it directly or via OpenAPI/Swagger UI plugins.
- `server-openapi.html` lines `34-47`: the `openAPI(path = "openapi")` route can render generated docs from the routing tree.

Implementation implication:

- KtKit should provide `/openapi.json` and optionally UI endpoints in JVM-only code.
- `/openapi.json` should be application opt-in in the first ship.
- phase 1 should generate JSON directly from KtKit's own contract IR even before wiring the Ktor UI plugins.
