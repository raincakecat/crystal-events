# Real Paper smoke tests

## September 26, 2026 verification

**Result: PASS.** A fresh Java 21 build passed all 41 automated tests with no failures, errors, or skips. The rebuilt plugin and smoke runner then passed on the isolated Paper 1.21.11 build 132 server. Snapshot, reset, and terrain drop took 1.345 s, 1.344 s, and 0.804 s respectively for the 16,384-block synthetic region. The longest batches were 30.324 ms, 34.721 ms, and 3.475 ms. Automatic world setup and reload also passed. The server shut down cleanly. There were no connected players; the real-player and production-size checks below remain open.

The tested runtime JAR is `jar/CrystalPvPEvents-1.0.0.jar` (SHA-256 `AED82152A7186B2DEA0CD4F61D4F36B97F32664755344413C0A62253C1D9544F`). The source JAR is `jar/CrystalPvPEvents-1.0.0-sources.jar` (SHA-256 `FB22AAF6F9E0A78B1DAF617814D71581EF689AEAFADE6493EA1B2579FEEDB0D8`).

## September 24, 2026 rerun

**Result: PASS.** Rebuilt with Eclipse Adoptium Java 21.0.12.1: 41 tests passed with zero failures, errors, or skips. The isolated Paper 1.21.11 build 132 server then passed the structure, reset, drop, and automatic-world smoke checks with zero connected players. It shut down cleanly. The current runtime and sources JAR hashes are recorded in `dist/BUILD-INFO.txt`.

| Operation | Elapsed | Longest batch |
| --- | ---: | ---: |
| Snapshot (32 batches) | 1.370 s | 34.720 ms |
| Reset (32 batches) | 1.314 s | 28.930 ms |
| Drop (16 batches) | 0.791 s | 12.327 ms |

The runner restored missing blocks, air, fluids, chest contents and sign text; cleaned up the test entity and reset lock; preserved the drop floor and outside bounds; and kept the dropped arena dirty for its next reset. Automatic main and gulag world creation, terrain, 50 saved spawns, two test sites, backups, and owned-world reload also passed. Details are in `integration/server/plugins/CrystalPvPSmoke/smoke-report.txt`. These are synthetic zero-player checks; the live-player and full-arena work listed below remains.

## September 9, 2026 runs (historical)

**Result: PASS on two successful runs.** The owner accepted the Minecraft EULA, and the isolated server's existing `eula=true` setting was verified before running. The tournament plugin enabled successfully on actual Paper 1.21.11 build 132 with Java 21. No production server or real Discord webhook was used.

## Test environment

- Windows 11 amd64; AMD Ryzen 7 5700X, 8 cores / 16 logical processors.
- Eclipse Adoptium Java 21.0.12.1, heap `-Xms512M -Xmx2G`.
- Paper `1.21.11-132-c5eb079`; only CrystalPvPEvents and its separate smoke runner.
- Localhost-only server on port 25578; **zero connected players**.
- Newly created synthetic world; region 32 × 32 × 16 = **16,384 blocks**, predominantly air over an obsidian floor, with a chest/sign fixture.
- Structure cube size 8; global budget one cube/batch per tick; drop batch 1,000 blocks. Four footprint chunks explicitly loaded before capture.

## Checks that passed

1. Capture/persist a real Paper structure template.
2. Restore a removed floor block and replace added water/lava with the original air.
3. Restore chest inventory (seven diamonds) and sign text through block-entity NBT.
4. Remove an event item entity and clear the persistent dirty marker on successful reset.
5. Clear above the live drop floor, preserve the floor and preserve an outside-region block.
6. Leave the dropped arena dirty so it requires a later template reset.

## Observed operation timings

| Operation | Successful run 1 elapsed | Run 1 longest batch | Successful run 2 elapsed | Run 2 longest batch |
| --- | ---: | ---: | ---: | ---: |
| Snapshot (32 cubes) | 1.383 s | 66.813 ms | 1.354 s | 48.860 ms |
| Reset (32 cubes) | 1.338 s | 23.516 ms | 1.315 s | 17.692 ms |
| Drop (16 batches) | 0.793 s | 1.398 ms | 0.793 s | 1.940 ms |

These are measured wall-clock operation durations, not throughput guarantees. The runs occurred shortly after test-world generation; startup and tick catch-up can influence elapsed timings. The snapshot's 66.813 ms batch exceeded the usual 50 ms budget for a 20 TPS tick, illustrating why even a small-region pass is not a lag-free claim. These results do not include sustained TPS/MSPT sampling, steady-state CPU/memory profiling, large-map trials or combat load.

## Test-runner corrections

The initial attempt failed the chest check because the fixture changed the live chest inventory and then called `update` on an earlier empty block-state snapshot, clearing its own contents before capture. The runner now populates the snapshot inventory and asserts the live chest contains items before backing up. Both subsequent runs passed. The runtime tournament plugin JAR did not need a change for this failure.

The runner also now uses unique arena names per run, supplies explicit flat-world generator settings and suppresses noisy terminal prompts. These changes make repeated isolated tests more reliable. No test worlds or old reports/logs were deleted.

## Evidence

Automatic-world update, September 9: `integration/smoke-worlds.log` passes the existing structure tests plus actual creation of one main world and one gulag world with two test sites. It verifies grass/deepslate/bedrock terrain, spawn headroom, 50 serialized main spawns, saved duel sites, preparation/backups completing, and reloading owned worlds without replacement. The smaller gulag pool is an integration fixture; the shipped default is two worlds with 32 sites each. This remains a zero-player test. The full generated main arena is snapshotted during preparation; its combat load and reset/drop performance are not benchmarked here.

A second process run, `integration/smoke-worlds-restart.log`, also passed with the final build. Owned worlds and saved locations loaded after a full server restart, and the ready setup was reused without creating replacement worlds. The test server shut down cleanly after both runs.

This run measured the synthetic 16,384-block snapshot at 1.371 seconds (maximum batch 69.346 ms), reset at 1.329 seconds (26.386 ms), and drop at 0.793 seconds (1.829 ms). Those batch peaks reinforce that a bounded block count does not guarantee every tick stays under 50 ms. Earlier measurements below refer to the previous build; current artifact hashes are in `dist/BUILD-INFO.txt`.

- First successful run: `integration/smoke-run-2.log`.
- Second successful run: `integration/smoke-run-3.log`.
- Latest concise report: `integration/server/plugins/CrystalPvPSmoke/smoke-report.txt`.
- Runner build: `integration/smoke-build.log`.
- Earlier failed fixture run, retained for transparency: `integration/smoke-run.log`.
- Runtime JAR SHA-256: `0961BF959FD818130672ED28FE6A0645B6A55630109FB97DF7FEAC9C8105F9A2`.

## Still needed before a production tournament

Real clients must exercise combat, GUI/sidebar appearance, gulag returns, simultaneous deaths, disconnect/rejoin and kit protection under the intended plugin stack. Performance work needs the actual arena dimensions/map, target player count and staging-server folder. Measure baseline, combat, multiple gulag resets, full main reset and both drops over repeated trials. See GETTING-STARTED.md section 10 for ready-to-use requests to arrange those sessions with Codex.
