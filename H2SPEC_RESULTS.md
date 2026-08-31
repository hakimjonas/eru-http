# h2spec conformance results

HTTP/2 conformance (`scripts/run-h2spec.sh`) against the code on `main`.
The suite pins h2spec v2.6.0 by checksum and starts
`H2SpecServer` (TLS + ALPN, ephemeral self-signed keystore).

## Latest run

**Date:** 2026-08-28 · **h2spec:** v2.6.0 · **Command:** `./scripts/run-h2spec.sh`

| Result | Count |
|--------|-------|
| Passed | 145 |
| Skipped | 1 (optional test) |
| Failed | 0 |

`146 tests, 145 passed, 1 skipped, 0 failed` — matches the number recorded
in the changelog for the initial public release.

## Notes

- This run exercises the TLS front door end to end: `SSLSocketChannel` with
  the per-read deadline machinery and the PROXY pre-read changes in this release.
- h2spec 5.1.2.1 (max concurrent streams) requires
  `H2ComplianceServer` via `scripts/run-h2-compliance.sh`; the default
  `H2SpecServer` run above skips it.
- The pinned tarball SHA-256 is recorded in `scripts/run-h2spec.sh`; the
  upstream release publishes no digests, so the hash was taken from the
  fetched release artifact on 2026-08-28.

## Reproduce

```bash
./scripts/run-h2spec.sh              # default: verbose off, port 8443
./scripts/run-h2spec.sh --strict     # show all tests
./scripts/run-h2-compliance.sh       # max-concurrent-streams tuning
```
