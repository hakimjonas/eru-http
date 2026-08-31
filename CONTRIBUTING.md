# Contributing

eru-http is developed by Hakim Jonas Ghoula and licensed under the GNU General Public License v3.0 or later.

## Build

The build uses sbt 2.0.7, Scala 3.8.4, and JDK 25.

- `sbt check` — scalafix and scalafmt checks
- `sbt fmt` — apply formatting
- `sbt testAll` — run every test suite (sbt 2 caches test runs; `testAll` forces them)
- `sbt docs` — compile and run the documentation samples in `docs-src/` with mdoc, then copy the generated files to the repository root

## Workflow

1. Branch from `main`.
2. Write the change with tests. Public API additions need a caller and a test.
3. Run `sbt check testAll docs` before pushing.
4. CI runs the same checks on every push and pull request.

## Hostile test suite

The hostile specs send deliberately malformed traffic at the server. They are opt-in:

```
HOSTILE=true sbt testAll
```

See [HOSTILE_TESTING.md](HOSTILE_TESTING.md) for what the suite covers.

## Repository layout

- `eru-http-core` — the validated HTTP model, parser, writer, codecs
- `eru-http-client` — the client and connection pool
- `eru-http-server` — the server and middleware
- `examples` — compliance test servers (h2spec, Autobahn) and integration tests
- `docs-src` — the mdoc sources for the root documentation

## Releasing

Releases are published from tags. Pushing a `v*` tag runs the release workflow: `sbt check`, the full test suite, and `publishSigned` + `sonaRelease` to Maven Central. Version numbers follow early-semver.
