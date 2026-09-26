# Validation and operational limits

## Build evidence

43 automated unit/server-mock tests pass with zero failures, errors or skips. See `target/surefire-reports`. Build environment: Windows 11 amd64, Eclipse Adoptium Java 21.0.12.1, Maven 3.9.9. Java target is release 21. API: Paper `1.21.11-R0.1-SNAPSHOT`, resolved snapshot `1.21.11-R0.1-20260511.115010-91`. Tests: JUnit Jupiter 6.0.3, MockBukkit 4.116.3, Surefire 3.5.4. Formatter: google-java-format 1.35.0. Runtime dependencies are Paper-provided; no shading or FAWE is needed.

Verified Paper server: `paper-1.21.11-132.jar`, commit `c5eb079`, SHA-256 `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`. On September 9, 2026 the owner accepted the EULA, and the real Paper smoke test subsequently passed on Java 21. Plugin enable, small-region NBT restoration, fluids, entity cleanup, terrain-drop bounds, and async gulag spawn chunk loading with temporary chunk tickets are now tested. See [LIVE-TEST-REPORT.md](LIVE-TEST-REPORT.md). Real-player teleports, gameplay and loaded/full-size arena performance remain untested.

## Automated coverage

- World rotation excludes busy worlds and unavailable sites, never repeats a claimed site within an event, and continues from the previous cursor across events. Generated sites do not overlap. Host rekit on/off updates the saved setting; ordinary players cannot toggle it, and gulag players cannot obtain rekits even when enabled.
- Gulag matches wait for both destination chunks before starting; a load failure returns players to the queue without dirtying the arena. The real Paper smoke runner checks async loading of both saved spawn chunks.

- Simultaneous final deaths/shared ranks; pending gulag/return winner guards; single-use chances; disconnect forfeits; double gulag disconnect; cutoff; rekit cooldown/limits; duplicate loss idempotence; reopen state.
- Ordered exactly-once time/player/host stages, delay/skip and invalid-stage handling.
- Layout item/metadata/quantity checks, replacement rather than additive kits, original inventory/location recovery, editor command/drop blocking and released-player restrictions.
- Plugin/menu load in MockBukkit; GUI click cancellation/action dispatch; registered death/quit/rejoin listeners; arena boundary/floor enforcement; virtual-border coordinate math.
- Exact cube coverage/edges/negative coordinates, overlaps, persistent dirty locks, failed backup handling, top-down drop floor/outside-block preservation.
- Discord JSON escaping, disabled mentions, URL validation, local reports with webhook off and persistent delivery claims. No test sends Discord messages.

MockBukkit lacks virtual player borders, player.dat saving and structure NBT serialization. The test player stores border assignments and makes `saveData` a no-op; orchestration tests use a world-border mock. The failed-backup test intentionally hits unsupported serialization and verifies no manifest is published. Its expected logged exception does not represent a failed test. These checks do not establish real client behavior or actual structure restoration.

## Opt-in Paper smoke runner

`integration/src` contains a separate compiled runner. It activates only with `-Dcpvp.integration=true`, creates its own test world and 32 × 32 × 16 region, snapshots blocks/chest/sign, mutates missing blocks/fluids, restores them, checks entity cleanup/locks, verifies bounded terrain clearing, records timings and shuts down. Never install this runner on production.

After independently reviewing and accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA), set `integration/server/eula.txt` to `eula=true` and run:

```powershell
.\integration\run-smoke.ps1 -JavaHome 'C:\path\to\jdk-21'
```

The script refuses without pre-existing acceptance and never accepts it for you. It verifies Paper's checksum, copies the built plugin/test-runner JARs and starts a localhost-only server on port 25578. Report: `integration/server/plugins/CrystalPvPSmoke/smoke-report.txt`. Repeated runs create separate test worlds and do not delete folders. The smoke test has now passed; this covers only the synthetic region/server, not multiplayer gameplay or production load.

## Capacity and performance

Default selected-region limit: **4,000,000 blocks including air**; configurable up to a hard ceiling of **16,000,000**. World height bounds are enforced. Maximum 128 gulag locations, with one active duel per world. Automatic defaults create two gulag worlds with 32 sites each. These are configured capacity limits, not measured player/TPS capacity.

Default cube: 8 × 8 × 8 = 512 blocks. The global budget processes one cube or one 1,000-block drop batch per server tick across jobs. `cubes-per-tick` is clamped to 1..8; cube size must be 1..16. Main then gulag resets run sequentially. Concurrent gulag jobs share the budget. All world/structure operations, compression and file I/O run on the server thread through supported APIs. A block-count limit does not guarantee a millisecond bound, particularly for unloaded chunks or large block-entity NBT.

These are **scheduling estimates only**, assuming 20 TPS, 8-block cubes and one cube per tick. They exclude chunk loads, compression, disk stalls, block updates and other jobs:

| Region X × Z × Y | Blocks | Cubes | Nominal scheduled time |
| --- | ---: | ---: | ---: |
| 32 × 32 × 16 | 16,384 | 32 | 1.6 seconds |
| 64 × 64 × 64 | 262,144 | 512 | 25.6 seconds |
| 128 × 128 × 192 | 3,145,728 | 6,144 | 307.2 seconds |
| 256 × 256 × 128 | 8,388,608 | 16,384 | 819.2 seconds; requires raised limit |

Small-region live timings now appear in LIVE-TEST-REPORT.md. No full-size or loaded-server benchmark exists yet. Server-mock timings are not Paper performance measurements. Pregenerate arena chunks, measure full-size backup/reset/drop on your staging server, and inspect TPS/MSPT while players are online elsewhere before raising budgets. Each completed operation logs elapsed seconds and longest batch and writes `arenas/<name>/last-operation.txt`. No lag-free claim is made.

## Remaining manual acceptance

1. Run the real smoke suite and a 6+ player event with two gulags. Check starting kits, layouts, UI, scoreboard and bossbar rendering.
2. Exercise crystal/explosion/fluid boundaries, outside-player isolation, return protection, pearl limits, inventory/container/crafting/drop/pickup restrictions and rekit limits with your other plugins installed.
3. Test simultaneous deaths, two concurrent gulags, disconnect/reconnect during death/duel/return, draws, unmatched queue and cutoff; confirm no premature winner.
4. Trigger/pause/resume shrink; delay/skip each timeline trigger. Drop both floors while holding partly consumed kits. Verify inventory/health and safe landing. Stop during a drop and confirm players remain restored.
5. Compare restored chest/sign/fluid/exploded blocks with templates. Restart during reset and check persistent locks and original-player recovery. Measure full-size restore duration and TPS/MSPT.
6. Inspect local placements and survival times. Enable Discord only with your own authorized webhook if you want to test external delivery; no live webhook testing was performed.

## Sources

Original implementation uses public APIs. Supplied plugin references informed features, not copied code.

- [Paper Java requirements](https://docs.papermc.io/paper/getting-started/) and [project setup](https://docs.papermc.io/paper/dev/project-setup/)
- [Paper 1.21.11 release](https://papermc.io/news/1-21-11/) and [Downloads Service](https://docs.papermc.io/misc/downloads-service/)
- [Structure](https://jd.papermc.io/paper/1.21.11/org/bukkit/structure/Structure.html) and [StructureManager](https://jd.papermc.io/paper/1.21.11/org/bukkit/structure/StructureManager.html) APIs
- [MockBukkit documentation](https://docs.mockbukkit.org/docs/en/user_guide/introduction/getting_started)
- Supplied feature/arena references: [CPVPEvent+](https://heuxil.com/cpvpevent-plus), [ArenaRegenerator](https://github.com/Woodstop/ArenaRegenerator), [Phoenix regeneration](https://www.phoenixplugins.com/docs/phoenix-duels/main-features/create-a-map/arena-regeneration), [FAWE API](https://intellectualsites.gitbook.io/fastasyncworldedit/api/api-usage). FAWE is not required by this plugin.
