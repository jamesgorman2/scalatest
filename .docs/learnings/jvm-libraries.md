---
kind: jvm-libraries
description: Where third-party JVM libraries, such as JSON parsers and schema validators, impose limits or requirements their APIs do not show. Read before adding a JVM library dependency or relying on one to check large or deeply nested input.
---

<learning>
date: 2026-11-26
---
`com.networknt:json-schema-validator` 3.0.0 validates JSON Schema draft 2020-12 (`SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)`, then `Schema.validate(text, InputFormat.JSON)`). It parses with Jackson 3 (`tools.jackson.*`, needs Java 17), not Jackson 2, so its types do not mix with `com.fasterxml.jackson` code.

Jackson 3's default `StreamReadConstraints` allow a nesting depth of 500. `validate` on a document nested deeper throws `tools.jackson.core.exc.StreamConstraintsException: Document nesting depth (501) exceeds the maximum allowed (500 …)` instead of returning violations. A throwable's cause chain written as nested objects reaches that depth.

Do not route every JSON output through the validator by default: an output built to test a long cause chain then fails on the parser's depth limit, not the schema. Validate outputs of bounded depth, and test deep nesting separately.
</learning>
