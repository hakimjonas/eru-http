# Hostile testing guide

This repo includes an opt-in suite of **hostile tests** — adversarial workloads
that subject the server/client to attack traffic (Slowloris, OOM floods, stream
exhaustion, fast shutdown under load, etc.) and assert survival.

They are gated because they are expensive (thousands of concurrent sockets,
sustained floods, resource snapshotting) and would slow the default dev loop.

## Running

```bash
# All hostile tests, all modules (testAll forces the run; plain test skips
# suites whose cached results are fresh)
HOSTILE=true sbt testAll

# Just the HTTP/2 exhaustion specs
HOSTILE=true sbt 'coreJVM/testOnly *hostile*'

# Just the server-level Slowloris + shutdown specs
HOSTILE=true sbt 'server/testOnly *hostile*'
```

Without `HOSTILE=true`, every hostile test is **skipped** (MUnit reports
"Skipped" — not "Passed"). This keeps the signal honest: a greenlight on
`sbt test` does not imply hostile-suite coverage.

The flag travels through the environment because sbt 2 starts a background
server on its first invocation and that server does not inherit the launching
client's `-D` system properties. `HostileTestBase` reads `HOSTILE` from the
forked test JVM's own environment (inherited from the sbt server) and falls
back to the `-Dhostile` system property. If the sbt server was already running
when `HOSTILE` was set, restart it (`sbt --client shutdown`) so the flag
reaches the forked test JVM.

## Layout

```
eru-http-core/jvm/src/test/scala/net/ghoula/eru/http/hostile/
  HostileTestBase.scala              — FunSuite subclass with requireHostileMode() gate
  ResourceSnapshot.scala             — heap/FD/thread counts with GC hint before capture
  H2StreamExhaustionSpec.scala       — HTTP/2 stream-count enforcement
  H2ContinuationFloodSpec.scala      — CVE-2024-27316 validation
  H2RapidResetSpec.scala             — CVE-2023-44487 validation

eru-http-server/src/test/scala/net/ghoula/eru/http/server/hostile/
  SlowlorisAttackSpec.scala              — Slowloris stall + byte-drip attack
  ConcurrentShutdownSpec.scala           — server shutdown resource cleanup
  ContentLengthAttackSpec.scala          — concurrent Content-Length OOM
  KeepAlivePipelineSpec.scala            — HTTP/1.1 pipelining via reset()
  ConnectionFloodSpec.scala              — maxConnections actual bound
  IpConnectionLimitSpec.scala            — per-IP concurrent cap
  RateLimitSpec.scala                    — per-IP request rate
  ProxyProtocolIntegrationSpec.scala     — PROXY v2 end-to-end
  XForwardedForSpec.scala                — XFF + trusted proxies

eru-http-client/src/test/scala/net/ghoula/eru/http/client/hostile/
  TlsProtocolDowngradeSpec.scala     — TLS protocol + cipher handshake rejection
```

Server-level specs import `HostileTestBase` and `ResourceSnapshot` from the
core test tree. This is enabled by `dependsOn(coreJVM % "compile->compile;test->test")`
in `build.sbt`.

## Current coverage

| Spec                      | Notes |
|---------------------------|-------|
| ContentLengthAttack       | Pins the `maxRequestSize` bound and the documented silent-truncation behavior for chunked-over-limit bodies. |
| SlowlorisAttack           | Both stalled and byte-drip variants covered. |
| ConnectionFlood           | The `maxConnections` bound: handler-active count, recovery, FD bound under sustained load. |
| H2ContinuationFlood       | CVE-2024-27316: flood bounded, GOAWAY sent, valid multi-CONTINUATION still works. |
| H2StreamExhaustion        | HTTP/2 stream-count enforcement (two scenarios). |
| TlsProtocolDowngrade      | Real TLS handshakes: TLS 1.3-only rejects 1.1/1.2 clients; weak CBC ciphers rejected by the allowlist. |
| KeepAlivePipeline         | 10- and 100-request pipelines, plus the partial-request-survives-reset() edge case. |
| ConcurrentShutdown        | Shutdown resource cleanup under load (three scenarios). |
| IpConnectionLimit         | Per-IP concurrent cap: isolation across source IPs, decrement on release. |
| RateLimit                 | Per-IP burst budget and 429 shape; keep-alive preserved across 429; slow drip succeeds. |
| ProxyProtocolIntegration  | PROXY v2 Required/Optional/Off modes against a real server; preamble feeds per-IP governance. |
| XForwardedFor             | Trusted isolation, untrusted ignored, malformed fallback, all-trusted fallback, mixed chains. |
| H2RapidReset              | CVE-2023-44487: 100 resets/10s budget; reset streams removed from the connection map. |

## Writing new hostile tests

1. Extend `HostileTestBase`:

   ```scala
   import net.ghoula.eru.http.hostile.{HostileTestBase, ResourceSnapshot}

   class MyAttackSpec extends HostileTestBase {
     test("some attack") {
       requireHostileMode()  // Must be the first line in the test body
       // ... actual hostile workload ...
     }
   }
   ```

2. Always call `requireHostileMode()` before any setup. Otherwise the test runs
   under default `sbt test` and slows the dev loop.

3. Prefer **exact** assertions (specific stream IDs, specific byte counts) over
   "more than X%" thresholds. A hostile test that tolerates 10% attack success
   is not a hostile test.

4. When measuring resources with `ResourceSnapshot`:
   - Capture `baseline` after a warmup round so JIT / class-init noise is out.
   - Sleep briefly after the attack completes so VTs unwind before the `after`
     snapshot.
   - Bound heap assertions in MB, not bytes. Expected tolerance is `<50MB`
     over an attack, not `<1024`.

5. Tests that want to observe network behavior at the byte level should build
   raw `java.net.Socket` connections in dedicated threads (`new Thread(...)`).
   Don't use `SimpleHttpClient` for attack traffic — it abstracts away the
   misbehavior we want to simulate.

## CI integration

The hostile suite is **not** run in CI by default. To run it in CI:

```yaml
# .github/workflows/conformance.yml (example)
- name: Hostile tests
  run: HOSTILE=true sbt testAll
  timeout-minutes: 10
```

Run separately from the main CI so a hostile flake doesn't block merges. Treat
results as advisory until the suite is stable across runner configurations.


