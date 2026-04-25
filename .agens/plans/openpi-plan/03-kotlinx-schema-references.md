# kotlinx-schema References

These references are not instructions to depend on `kotlinx-schema` immediately. They explain the architectural pattern worth reusing.

## 1. README overview

Source:

- <https://raw.githubusercontent.com/Kotlin/kotlinx-schema/main/README.md>

Key lines:

- line `2`: `kotlinx-schema` positions itself as generation from Kotlin code with multiple backends and mentions the JSON Schema DSL module.
- line `4`: it explicitly separates KSP, serialization-based generation, and reflection-based generation as different approaches with different constraints.
- lines `9-15`: runtime generation from `@Serializable` metadata and reflection are separate modes, and JSON Schema output is a transformation result rather than the source model.

Implementation implication:

- KtKit should mirror the separation of:
  - source introspection
  - common intermediate contract/schema model
  - output transformation

## 2. Architecture doc

Source:

- <https://raw.githubusercontent.com/Kotlin/kotlinx-schema/main/docs/architecture.md>

Key lines:

- line `0`: `kotlinx-schema` uses a shared internal representation across KSP, reflection, and serialization.
- line `1`: its explicit goals include unified IR, multiplatform generation, extensibility, and third-party support.
- lines `20-24`: the pipeline is `Sources -> Introspectors -> TypeGraph -> Transformers -> Output`.

Implementation implication:

- KtKit should build:
  - endpoint contract/source metadata in `commonMain`
  - schema extraction from serializers in `commonMain`
  - OpenAPI transformation in JVM code

This is the single most important design lesson from `kotlinx-schema`.

## 3. What to borrow and what not to borrow

Borrow:

- internal representation first
- output transformation second
- multiple acquisition strategies later (`vanilla`, KSP, compiler plugin)

Do not borrow as a hard requirement for phase 1:

- direct dependency on `kotlinx-schema`
- JVM reflection as a primary path
- full code inference before vanilla authoring exists

## 4. Practical recommendation for KtKit

Phase 1:

- use `kotlinx.serialization` directly because KtKit already depends on it:
  - `ktkit/build.gradle.kts:24`
  - `ktkit/build.gradle.kts:25`

Phase 2:

- consider optional adapters or experiments with `kotlinx-schema` only if:
  - third-party type schemas become a requirement
  - a reusable JSON Schema DSL becomes clearly beneficial
  - KSP generation needs a broader schema backend than KtKit's first-pass contract model
