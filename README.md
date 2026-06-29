# nexus-export

Standalone Java 21 CLI that bulk-downloads selected **Nexus 3 hosted Maven repositories**
into standard on-disk Maven layouts — one directory per source repo — for migrating to
another Maven repository manager. Downloads are SHA-1-verified, `.sha1`/`.md5` sidecars are generated, and re-runs
skip files already present whose SHA-1 matches (safe to resume after an interruption).

## Build

```bash
./mvnw -T1C clean package
# -> target/nexus-export.jar  (executable uber-jar)
```

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

While exporting, a progress line is logged every `--progress-interval` seconds:

```
progress: repo=releases downloaded=1234 skipped=56 failed=0, 245.3 MB (8.1 MB/s), elapsed 02:30
```

Exit codes: `0` success, `1` completed with asset failures, `2` fatal (config/auth/connection).
