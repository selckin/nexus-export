# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A standalone Java 21 CLI that bulk-downloads selected **Nexus 3 hosted Maven repositories** over the
Nexus REST API into standard on-disk Maven layouts (one directory per source repo), for migrating off
Nexus 3 to another Maven repository manager. Downloads are SHA-1-verified, `.sha1`/`.md5` sidecars are generated, and re-runs
skip files already present whose SHA-1 matches (resumable). `--no-verify-checksums` downgrades a source-vs-download
mismatch from a per-asset failure to a warning that keeps the bytes (for source repos with bad recorded checksums).

## Build / test / run

Self-contained Maven module with its own wrapper (`./mvnw`). No parent POM.

```bash
./mvnw -T1C clean package                 # build + test -> target/nexus-export.jar (executable uber-jar)
./mvnw -T1C test                          # full test suite (JUnit 5)
./mvnw -T1C test -Dtest=RepoExporterTest  # single test class
./mvnw -T1C test -Dtest=ProgressTest#formatsProgressLine   # single test method

# Run the tool (password via env, kept off argv):
NEXUS_PASSWORD=… java -jar target/nexus-export.jar --url https://<nexus> --user admin --list
NEXUS_PASSWORD=… java -jar target/nexus-export.jar --url https://<nexus> --user admin --out ./export --dry-run
NEXUS_PASSWORD=… java -jar target/nexus-export.jar --url https://<nexus> --user admin --out ./export
```

`--repo` (repeatable) defaults to `releases`+`snapshots`; `--list`/`--dry-run` are discovery modes;
`--threads` and `--progress-interval` tune parallelism and heartbeat cadence. See `README.md`.

## Packaging / release

Two distributable forms, both driven off the shade uber-jar (`target/nexus-export.jar`):

- **Portable uber-jar** — runnable anywhere with Java 21 (`java -jar`).
- **Self-contained app-image** — `package/build-appimage.sh [version]` runs `jdeps` to compute the
  required JDK modules, adds `jdk.crypto.ec` (EC/ECDHE TLS) + `jdk.charsets` (these are loaded
  reflectively/via ServiceLoader and so are invisible to `jdeps` — dropping them breaks HTTPS),
  then `jpackage --type app-image` bundles a trimmed JRE under `dist/`. No Java needed at runtime;
  the native launcher loads the bundled `libjvm` directly (the runtime's `bin/java` is stripped).
  `dist/` is gitignored. Per-OS output layout differs: `dist/nexus-export/` (Linux/Windows) vs
  `dist/nexus-export.app/` (macOS) — the tar step globs `nexus-export*` to catch both.

Gotcha: macOS's bundler rejects an app-version whose first integer is 0 (or empty), so the script
passes `--app-version` to jpackage **only** when it's `1-3` dotted integers with a first number `>= 1`
(e.g. a `v0.x` tag would otherwise fail only the macOS jobs); otherwise jpackage's default is used.
The version is bundle metadata only — the tool reports its own `--version` from the jar.

`.github/workflows/release.yml` runs on **every branch push** and on `v*` **tags**: a per-OS matrix
(linux-x64, macos-x64, macos-arm64, windows-x64) builds the app-image and uploads it as a run artifact;
on a tag it additionally publishes a Release with a `.tar.gz` per OS plus the portable jar. Three gotchas:
branch builds have no version, so the build id is `<branch-name-with-slashes-dashed>-<short-sha>`
(non-numeric → jpackage default app-version, per the macOS rule above); runner labels are pinned
(`macos-15`/`macos-15-intel`, not the migrating `macos-latest`); and publishing uses the GitHub CLI only —
**no third-party actions** (only GitHub's own `actions/checkout`, `actions/setup-java`,
`actions/upload-artifact`), with ref-derived values flowing through `env:` into `$VARS`, never
`${{ }}`-interpolated into a `run:` body.

## Architecture

Request pipeline, each stage in its own file with an explicit interface:

`Main` (picocli; resolves `--url`/`--user` and the `NEXUS_PASSWORD` env, builds `HttpNexusClient`)
→ `ExportRunner` (orchestrates: lists repos, validates each requested repo against the **live maven2 set**,
runs one `RepoExporter` per repo on a shared bounded thread pool, runs the progress heartbeat, prints the
report, maps the exit code)
→ `RepoExporter` (per repo: paginates assets, schedules per-asset download tasks, skip/verify/sidecar)
→ `NexusClient` interface / `HttpNexusClient` impl (REST calls + file downloads over `java.net.http.HttpClient`).

Cross-cutting collaborators: `ExportReport` (thread-safe per-repo + total tallies — written by `RepoExporter`,
read by the heartbeat and the final render); the Jackson record DTOs `Repository`/`Asset`/`AssetPage`/`Checksum`;
`PathMapper` (asset path → on-disk path); `ChecksumUtil` (SHA-1/MD5); `NexusException` (fatal/auth).

Behaviors that span files and are easy to break:

- **Resumability + integrity (`RepoExporter`):** an asset is skipped if the file exists and its SHA-1 matches
  the recorded checksum; otherwise it downloads to a `<name>.part` temp, verifies SHA-1, atomic-moves into
  place, and writes `.sha1`/`.md5` sidecars. Sidecars are **always generated** from the verified checksums for
  non-metadata files (extensions `.sha1/.md5/.sha256/.sha512/.asc` excluded), and also written on the skip
  path if missing — so the tree is a self-describing Maven repo regardless of how Nexus enumerates sidecars.
- **`--no-verify-checksums` (opt-in, `RepoExporter.verifyChecksums`):** downgrades a SHA-1 mismatch from a
  per-asset failure to a warning that keeps the bytes, recorded via `ExportReport.keptMismatch` (surfaced as
  `kept-mismatch=N` plus a path list). Sidecars for a kept file are computed **from the downloaded file**, not
  the bogus recorded checksum. Two sharp edges, both **by design**, not bugs:
    - *Global, not per-asset:* the flag suppresses the SHA-1 check for **every** asset in the run, so genuinely
      corrupt-in-transit bytes for an otherwise-good asset are also kept. The `kept-mismatch` list is the
      mitigation — inspect it after a run.
    - *Never overlay a strict re-run:* the flag does **not** touch the skip logic, so a kept mismatching file
      re-downloads each run (idempotent under the flag) — but a later **strict** run into the same `--out`
      re-downloads it, mismatches, and marks it *failed* while only deleting the `.part`, leaving the
      previously-kept file and its (valid-looking) sidecars on disk. Treat a `--no-verify-checksums` output
      tree as terminal.
- **Memory bounding (`RepoExporter.export`):** each page's download futures are joined **before** fetching the
  next page. Do not refactor back to one `allOf` over all pages — that retains a future per asset and OOMs on
  large repos. The pagination loop terminates on a null **or blank** continuation token.
- **Retry/auth (`HttpNexusClient.sendWithRetry`):** retries only network IO / 429 / 5xx (with backoff);
  401/403 throw `NexusException` immediately (fatal); any other 4xx and any unfollowed 3xx throw `IOException`
  immediately (never silently treated as a successful download). Redirects are followed via `Redirect.NORMAL`.
- **Exit codes:** `0` ok, `1` completed with per-asset failures, `2` fatal. Per-asset failures are recorded in
  the report and never abort a repo; `ExportRunner` maps `NexusException` and other `RuntimeException`
  (e.g. wrapped connection errors) to `2`.

## Project constraints (respect these)

- **Standalone build:** no parent POM; every dependency and plugin version is pinned explicitly in `pom.xml`.
  Do not add a parent or rely on a BOM.
- **No mock frameworks** (Mockito/etc.). Tests use the JDK `com.sun.net.httpserver.HttpServer`
  (`HttpNexusClientTest`) and hand-rolled in-memory `NexusClient` fakes (`FakeNexusClient`, inline impls).
  Keep new tests in that style. The progress-heartbeat scheduler is intentionally not unit-tested (timing);
  its formatter (`ExportRunner.formatProgress`) and the export wiring are.
- **Git author** is pinned in this repo's local config to `github@selckin.be` (a workspace-level `includeIf`
  would otherwise apply a different email). Commit with the repo's configured identity — do not pass author
  overrides.
- Base package `be.selckin.nexus.export`; groupId `be.selckin`.
