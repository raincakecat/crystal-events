# CrystalPvPEvents

Original Paper 1.21.11 / Java 21 Crystal PvP tournament plugin. Install `jar/CrystalPvPEvents-1.0.0.jar`. Source, automated tests, build scripts and an isolated Paper smoke runner are included.

## Start here

Read **[GETTING-STARTED.md](GETTING-STARTED.md)** for a complete first-run guide: installation, a worked practice-arena example, saving the kit/spawns/gulag, running a tournament, trying every major feature, troubleshooting, and arranging gameplay/performance testing with Codex. It distinguishes this build folder from your actual Minecraft server folder.

Then use [ADMIN.md](ADMIN.md) as the command reference and [VALIDATION.md](VALIDATION.md) for the exact tested scope. Do not install the separate `CrystalPvPSmoke` test runner on your normal tournament server.

## Install

1. Run Paper 1.21.11 with Java 21.
2. Copy the JAR into the server's `plugins` folder and restart.
3. Configure `plugins/CrystalPvPEvents/config.yml`.
4. Give hosts `crystal.host` (operators receive it by default). Fresh installs automatically prepare a plains-style main world and two gulag worlds. Wait for `/event worlds status` to report ready, visit `/event worlds lobby`, arrange your kit and `/event kit save`.
5. Run `/event preflight`, `/event open`, then `/event start` after players join. After a winner/draw or host stop, run `/event reset`, wait for completion, and reopen.

The plugin has no required runtime dependency. Arena snapshots use Paper's structure API and are restored in bounded batches. Test large arenas on a staging server before production use.

## Host setup

Automatic setup includes the main arena, 50 spawns, lobby, gulag sites and backups. Each gulag world runs one duel at a time and rotates through 32 different locations, with no location reused during an event. Winners revive in the main event world. Existing manual setups are preserved; the guide also explains manual selection and backups.

Useful host commands include `/event worlds create|status|lobby`, `/event open`, `/event start`, `/event stop`, `/event gui`, `/event border <full-width> <seconds>`, `/event rekit on`, `/event rekit off`, `/event stage next|skip|delay <seconds>`, `/event drop deepslate|bedrock`, `/event reset`, and `/event results`. Gulag rekitting is always disabled, regardless of the main-event toggle.

Players use `/event join`, `/event leave`, `/event rekit`, and `/event status`. `/event layout edit` lets a player save a personal arrangement while retaining the exact enforced kit quantities.

## Discord results

Webhook posting is disabled by default. Set `discord.enabled: true` and a Discord webhook URL in `config.yml`. URLs are validated against Discord webhook hosts, requests run asynchronously, mentions are disabled, and a completed event is deduplicated by event ID. A local JSON/text report is always written under `plugins/CrystalPvPEvents/results/`.

## Validation

41 automated unit/server-mock tests pass, with no failures or skips. After the owner accepted the EULA, the plugin enabled and passed the real Paper 1.21.11 build 132 smoke test on Java 21. That test checks block/block-entity restoration, fluids, entity cleanup and bounded terrain drops. A 16,384-block, zero-player reset took about 1.34 seconds in the first successful run. Real-player gameplay and full-size loaded-server performance are still untested. See [LIVE-TEST-REPORT.md](LIVE-TEST-REPORT.md) and [VALIDATION.md](VALIDATION.md) for the scope and evidence.

## Build and deliverables

Install Java 21 and Maven 3.9.9 or newer, then run `.\build.ps1 -JavaHome 'C:\path\to\jdk-21'` from this folder. The script runs tests, builds the plugin and separate smoke runner, and copies the plugin/source JARs into `jar`. It uses a local bundled Maven copy when present, otherwise Maven on `PATH`. You can also use `mvn clean verify` to build the main plugin. First builds require dependency downloads.

GitHub Actions builds and tests the plugin and packages the isolated smoke runner on pushes and pull requests. The `.gitignore` excludes local servers, generated worlds, logs and downloaded build tools. The `jar` folder contains the installable plugin and source JARs. Never commit a live server's `plugins/CrystalPvPEvents/config.yml`, which may contain a Discord webhook URL.

On this Windows machine, a repository certificate chain required Java to use the Windows root certificate store. TLS validation remained enabled. If needed, set `$env:MAVEN_OPTS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'` in the build shell before running the script.

| Path | Purpose |
| --- | --- |
| `jar/CrystalPvPEvents-1.0.0.jar` | Installable plugin |
| `jar/CrystalPvPEvents-1.0.0-sources.jar` | Main source archive |
| `src/main`, `src/test`, `pom.xml` | Full source, resources and tests |
| `ADMIN.md` | Setup, commands, timelines, gulag and recovery rules |
| `GETTING-STARTED.md` | Detailed first-event walkthrough, troubleshooting and how to arrange testing |
| `LIVE-TEST-REPORT.md` | Real Paper smoke results and measured small-region timings |
| `VALIDATION.md` | Tested scope, size limits, pending live checks |
| `integration` | Separate test runner and localhost-only Paper server |
| `build-validation.log`, `target/surefire-reports` | Local build/test evidence; excluded from Git |

This is original code using supported Paper APIs, with no NMS or copied proprietary implementation. FAWE is not required. Layer-by-layer clearing is implemented top-to-bottom in bounded batches while players are safely relocated. Watching terrain collapse during active combat is deferred. Teams, voting and cosmetics remain future features.
