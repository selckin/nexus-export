# nexus-export

Standalone Java 21 CLI that bulk-downloads selected **Nexus 3 hosted Maven repositories**
into standard on-disk Maven layouts — one directory per source repo — for migrating to
another Maven repository manager. Downloads are SHA-1-verified, `.sha1`/`.md5` sidecars are generated, and re-runs
skip files already present whose SHA-1 matches (safe to resume after an interruption). A mismatch between
the downloaded bytes and the checksum Nexus recorded fails that asset; pass `--no-verify-checksums` to keep
the downloaded bytes anyway (for source repos with bad checksums on the server), with the sidecars recording
the actual downloaded bytes so the exported tree stays self-consistent.

## Download

Each [GitHub Release](https://github.com/selckin/nexus-export/releases) ships a
self-contained app-image per OS — every zip bundles a trimmed Java 21 runtime, so
**no Java install is required**:

| Asset | Platform |
|-------|----------|
| `nexus-export-<version>-linux-x64.zip`   | Linux x86-64 |
| `nexus-export-<version>-macos-x64.zip`   | macOS Intel |
| `nexus-export-<version>-macos-arm64.zip` | macOS Apple Silicon |
| `nexus-export-<version>-windows-x64.zip` | Windows x86-64 |

Unzip and run the launcher:

```bash
# Linux
unzip nexus-export-<version>-linux-x64.zip
NEXUS_PASSWORD=… ./nexus-export/bin/nexus-export \
  --url https://nexus.example.com --user admin --list
```

```bash
# macOS (the app-image is a .app bundle)
unzip nexus-export-<version>-macos-arm64.zip
xattr -dr com.apple.quarantine ./nexus-export.app     # clear the download quarantine
NEXUS_PASSWORD=… ./nexus-export.app/Contents/MacOS/nexus-export \
  --url https://nexus.example.com --user admin --list
```

```powershell
# Windows (PowerShell)
Expand-Archive nexus-export-<version>-windows-x64.zip -DestinationPath .
$env:NEXUS_PASSWORD = "…"
.\nexus-export\nexus-export.exe --url https://nexus.example.com --user admin --list
```

> The binaries are unsigned: macOS quarantines them on download (clear it with the `xattr`
> line above); on Windows, SmartScreen may warn on first run.

Already have **Java 21** and want the smallest artifact? Each release also attaches the
portable uber-jar `nexus-export-<version>.jar` — run it with `java -jar nexus-export-<version>.jar …`.

## Build

```bash
./mvnw -T1C clean package
# -> target/nexus-export.jar  (executable uber-jar)
```

Build a self-contained app-image locally (bundles a trimmed JRE into `dist/nexus-export/`):

```bash
./mvnw -T1C clean package
package/build-appimage.sh 1.0.0          # arg = app version
./dist/nexus-export/bin/nexus-export --help
```

Tagged releases (`git tag vX.Y.Z && git push --tags`) build these app-images for
Linux/macOS/Windows in CI and attach them to a GitHub Release — see
`.github/workflows/release.yml`.

## Usage

Credentials: pass `--user`, set the password via the `NEXUS_PASSWORD` env var (kept out of
argv). `--url`/`--user` also accept `NEXUS_URL`/`NEXUS_USER`.

```bash
# List the maven2 repositories
NEXUS_PASSWORD=… java -jar target/nexus-export.jar \
  --url https://nexus.example.com --user admin --list

# Estimate size (downloads nothing)
NEXUS_PASSWORD=… java -jar target/nexus-export.jar \
  --url https://nexus.example.com --user admin --out ./export --dry-run

# Full export (defaults to repos: releases, snapshots)
NEXUS_PASSWORD=… java -jar target/nexus-export.jar \
  --url https://nexus.example.com --user admin --out ./export

# Specific repos / more parallelism
NEXUS_PASSWORD=… java -jar target/nexus-export.jar \
  --url https://nexus.example.com --user admin --out ./export \
  --repo releases --repo snapshots --threads 10
```

Output: `./export/releases/…`, `./export/snapshots/…` — each a standard Maven tree. Drop each
into the target repository manager's corresponding repository data directory.

## Options

| Option | Env | Default | Meaning |
|--------|-----|---------|---------|
| `--url` | `NEXUS_URL` | — (required) | Nexus base URL |
| `--user` | `NEXUS_USER` | anonymous | Nexus user |
| `--password` | `NEXUS_PASSWORD` | anonymous | Nexus password (prefer the env var) |
| `--out` | — | `./export` | Output root directory |
| `--repo` | — | `releases`, `snapshots` | Repository to export (repeatable) |
| `--threads` | — | `6` | Parallel downloads per repository |
| `--progress-interval` | — | `10` | Seconds between progress log lines (`0` = off) |
| `--list` | — | off | List maven2 repos and exit |
| `--dry-run` | — | off | Enumerate + report, download nothing |
| `--no-verify-checksums` | — | off | Keep downloaded files even when they don't match the source's recorded checksum (sidecars record the actual bytes) instead of failing the asset |

While exporting, a progress line is logged every `--progress-interval` seconds:

```
progress: repo=releases downloaded=1234 skipped=56 failed=0, 245.3 MB (8.1 MB/s), elapsed 02:30
```

Exit codes: `0` success, `1` completed with asset failures, `2` fatal (config/auth/connection).

### `--no-verify-checksums`

By default a downloaded file whose SHA-1 doesn't match the checksum Nexus recorded fails that asset
(exit `1`) and nothing is written. Pass `--no-verify-checksums` when a source repo has bad checksums
recorded on the server but serves intact bytes: the mismatch is logged and the bytes are kept, with the
`.sha1`/`.md5` sidecars computed from the downloaded file so the exported tree stays self-consistent.
Kept files are counted separately and reported as `kept-mismatch=N` with the list of paths, so you have
an audit trail of exactly which artifacts the source disagreed with itself about. Two caveats:

- The flag disables the SHA-1 check for **every** asset in the run, not only the known-bad ones — so
  genuinely corrupt-in-transit bytes are also kept. Review the `kept-mismatch` list after a run.
- Don't re-run a **strict** (default) export into an `--out` directory produced with
  `--no-verify-checksums`: the strict run re-downloads the kept file, fails it, and leaves the old
  bad-checksum file in place. Treat a `--no-verify-checksums` output tree as final.
