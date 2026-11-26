# CrystalPvPEvents: installation and first tournament

This guide takes you from the downloaded plugin to a practice tournament, then explains how to test gameplay and performance. Read this first; use [ADMIN.md](ADMIN.md) for the complete command reference and [VALIDATION.md](VALIDATION.md) for test evidence and limitations.

## 1. Understand the folders

### Your proposed hardware and hosting

An 8–10 GB memory budget is a reasonable starting point for testing this setup, but it does not establish 50-player capacity. Leave memory for the operating system, Java's non-heap allocations and other services. If 8 GB is the entire hosting/container limit, start testing around a 5–6 GB Java heap; if 10 GB, around 7–8 GB, then measure total process memory. These are starting estimates, not validated limits. Local hosting while also running a Minecraft client needs additional headroom.

CPU model, single-thread speed and whether the advertised cores are shared vCPUs matter more than the core count alone. [Paper recommends strong single-thread performance and at least four CPU threads](https://docs.papermc.io/paper/basic-troubleshooting/). A 2–3 vCPU plan is a tighter budget; test 20 players first, then 30, 40 and 50. The plugin uses a fixed world pool and batched terrain work, but that cannot guarantee combat TPS on a particular host.

Local and rented hosting both work; the plugin uses the same server-side setup. Use a separate staging folder and your intended plugin stack for testing. Start with view distance 6 and simulation distance 4 as trial settings, then measure combat MSPT/TPS, memory, disk stalls and ping. Run world preparation before the event. A useful request is: “Test this staging server at [folder], using the generated worlds, an [8/10] GB total memory limit and [CPU details], first with 20 players and then 40–50. Save gameplay failures and performance measurements in a report.” Real-player combat coverage requires clients/test participants; automated block tests alone do not provide it.

This repository is the **development/build folder**. Your Minecraft server has a separate folder containing its Paper JAR, `server.properties`, worlds and `plugins` directory. Installing the plugin means copying one JAR into that server's `plugins` folder.

| File or folder | What to do with it |
| --- | --- |
| `jar\CrystalPvPEvents-1.0.0.jar` | Copy this into your Minecraft server's `plugins` folder |
| `jar\CrystalPvPEvents-1.0.0-sources.jar` | Source archive; do not install it as a plugin |
| `src`, `pom.xml`, `build.ps1` | Development files; not needed to host the already-built JAR |
| `integration\server` | Dedicated automated test server; not your normal tournament server |
| `integration\run-smoke.ps1` | Runs an automated block-restoration test and stops the test server |
| `ADMIN.md` | All commands, configuration behavior and recovery rules |

**Do not copy `CrystalPvPSmoke.jar` into your normal server.** It is an automated test runner, not a tournament feature. It creates a test world and shuts the server down after testing. The normal event plugin does not do that.

## 2. Install on your server

Use **Minecraft Java Edition 1.21.11**, **Paper 1.21.11**, and **Java 21**. This build does not target Minecraft Bedrock Edition or Folia. FAWE, WorldEdit, a database and a Discord bot are not required.

1. Stop your Minecraft server cleanly with `stop` in its console. Back up the server before installing a new plugin.
2. Copy `jar\CrystalPvPEvents-1.0.0.jar` from this repository into your server's `plugins` folder. Keep only one installed copy/version of the event plugin.
3. Start your Paper server normally. Look for `CrystalPvPEvents loaded` in the console. If it does not load, read the first related error in `logs\latest.log`.
4. Join using your Java 1.21.11 client. As an operator, type `/event help` and `/event gui`.
5. The server creates `plugins\CrystalPvPEvents\config.yml`. That is the live config to edit. Changing `src\main\resources\config.yml` in the build folder does not update an already-installed plugin.

Hosts need `crystal.host`. Operators have it by default. In the server console, `op YourMinecraftName` grants operator status; console commands do not use a leading slash. Give operator access only to trusted hosts. Regular players can join without operator access.

If you do not already have a normal Paper server, set up a **separate local practice server** in its own folder. The integration server is designed for automated checks rather than interactive hosting.

## 3. Automatic worlds: recommended first-event setup

On a fresh installation, the plugin automatically creates a flat plains-style event world and two separate gulag worlds. It saves a 128 × 128 main arena, 50 main spawns, a lobby, two duel spawns for every gulag location, and terrain backups. No world-management plugin is required. Initial preparation takes several minutes; finish it before inviting players.

1. Run `/event worlds status` until it reports ready. If creation was interrupted, `/event worlds create` resumes it.
2. Use `/event worlds lobby` to visit the generated lobby.
3. Arrange the inventory, armor and offhand that fighters should receive. Run `/event kit save`.
4. Run `/event preflight`. When it passes, `/event open`, have players `/event join`, then `/event start`.
5. After the event, `/event reset`, wait for completion, then open again.

Default generated terrain has grass at Y=80, deepslate at Y=70 and bedrock at Y=60. Main arena bounds are X/Z 0..127 and Y=60..95. The lobby is outside the arena. The generated map is intentionally plain; customize it while idle and take fresh backups before playing.

Each gulag world runs **one duel at a time**. Different gulag worlds can host duels simultaneously. Each world has 32 separate 32 × 32 locations spaced 128 blocks apart. A location is used at most once per event, and a used arena finishes resetting before its world accepts another duel. Rotation remembers the last location across restarts and continues from there for the next event; the finite pool cycles across events. If every site is used or blocked, players wait rather than reuse a site during that event. Winners return to a safe point inside the main event's current border.

`worlds.gulag-worlds` accepts 1..4 and `worlds.locations-per-world` accepts 2..32. These settings choose the pool when it is first created; changing them does not rebuild existing worlds. To choose custom values before first creation, prepare the config before starting or disable `worlds.auto-create` initially and run `/event worlds create` after configuring it. Existing manual setups are preserved. Keep `managed-worlds.yml`, `setup.yml`, arena backups and the generated `cpvp_*` world folders together when moving servers; missing ready worlds cause a startup error instead of silent regeneration.

The default capacity is 50 registered players, intended for your usual 20 and occasional 30–40. This is a registration limit, not a proven hardware capacity. The default timeline turns rekit off after five minutes and closes gulag after seven minutes, even if players are queued. Increase/delay that stage if you want longer gulag availability.

## Optional: build your own practice arena

For manual setup, disable `worlds.auto-create` before the first automatic run, then use an existing dedicated map, Creative mode, or building tools. Skip this section if you use automatic worlds.

A useful practice example is a square with block corners X/Z `0..31`, bottom Y `60`, top Y `90`. Its selection contains 31,744 blocks. Build:

- A continuous lower floor at Y=60, for the bedrock finale.
- A continuous intermediate floor at Y=70, for the deepslate stage.
- An initial playable surface at Y=80, with at least two clear air blocks above it.
- A safe lobby outside this region, such as near X=100, Z=100.

These are example coordinates, not commands to paste into an existing world without checking what is there. Floor material is your choice; the stage name does not automatically convert the floor. Main floors are preserved by their configured Y levels. Avoid unrelated structures or entities inside the region: reset restores the saved blocks and clears non-player entities there.

In the live config, set:

```yaml
arena:
  deepslate-y: 70
  bedrock-y: 60
```

Change just those values in the existing `arena` section; do not add a second `arena:` section or delete the other arena settings. YAML uses spaces, not tabs. Run `/event reload` while the event is closed after saving.

### Select the main region

1. Stand at the first block corner at X=0, Y=60, Z=0; use `/event pos1`.
2. Stand at the opposite corner at X=31, Y=90, Z=31; use `/event pos2`.
3. Use `/event arena`.

The selection includes both corner blocks and all blocks between them, including air. You can use Creative flight or operator teleport commands to reach the corner coordinates. `/event pos1` and `/event pos2` use your feet position, not the block you are looking at.

The initial border is centered in the region and is 32 blocks **wide**, not a radius of 32. A non-square selection uses the smaller horizontal dimension for its square border.

### Save lobby, player spawns and kit

1. Stand safely on the lobby floor, outside every event region. Use `/event lobby`.
2. Stand on the initial main floor with clear space above you. Use `/event spawn add`. For a full block floor at Y=80, your standing/feet Y will normally be 81.
3. Add another separated spawn, and ideally one for each planned participant. If players outnumber saved spawns, positions repeat. `/event spawn clear` lets you replace the list.
4. Arrange the intended CrystalPvP kit in your inventory, armor slots and offhand. Use `/event kit save`. The host's current inventory becomes the kit; this command does not supply a premade kit.
5. Include whatever consumables/equipment your rules require. Check armor, offhand and amounts carefully. Bundles and block-container items are rejected to prevent hidden supplies.

## 4. Configure gulag, or disable it for your first practice

For a quick two-player practice without revival, change `gulag.enabled` to `false` and `/event reload`. Rekit remains separately configurable.

For gulag testing, build a separate small 1v1 arena with two safe spawns. It must not overlap the main arena or contain the lobby. Then:

```text
/event pos1
/event pos2
/event gulag add cell1
```

Run the corner commands at that gulag's corners. Stand at the first safe duel position and run `/event gulag spawn cell1 a`; stand at the second and run `/event gulag spawn cell1 b`.

Gulag uses the main kit by default. To use a different duel kit, arrange it, run `/event kit save gulag`, and set `gulag.separate-kit: true`. Up to 128 named locations are supported. Only one duel runs per world; add multiple locations in each world for sequential fights and use different worlds for simultaneous fights. A single manually configured cell can host only one duel per event.

## 5. Take backups and run preflight

Move **everyone outside all selected arenas**, including the host. Save each template:

```text
/event backup main
/event backup cell1
```

Wait for each operation to finish before starting another setup operation. If gulag is disabled and you did not configure a cell, omit its command. `/event status` reports progress.

Once complete, run `/event preflight`. Resolve every reported problem. Frequent causes are missing kit, fewer than two safe main spawns, unsafe lobby, missing gulag spawn/backup, or configured drop floors outside the selection. Your main region must include both configured drop Y levels if you keep those stages in the timeline.

Do not capture a new template from a damaged tournament arena to make an error go away. Reset from the original template first. Saving a new main region clears its spawn list and requires a matching backup.

## 6. Run your first event

For a two-player event, each player needs their own online Minecraft Java account/session. A host who joins counts as a participant. An observing host does not.

1. Host: `/event open`.
2. Players: `/event join`.
3. Host: `/event status` to check registration, then `/event start`.
4. Registration closes. The default countdown is 20 seconds. Participants receive the saved kit and teleport to the main spawns when fighting starts.
5. Players fight. `/event rekit` replaces a living main fighter's kit if permitted. Default cooldown is 30 seconds; default request count is unlimited.
6. Watch the bossbar/sidebar for the phase and next stage. Hosts can reopen `/event gui` for the control panel.
7. After the winner or draw, original player states are restored and results are saved.
8. Host: `/event results`, then `/event reset`. Wait for all arenas to finish before `/event open` for the next event.

To stop early, `/event stop` aborts the event, restores players and saves a local aborted report. You still need `/event reset` before reopening. A player using `/event leave` during fighting forfeits. Disconnecting also forfeits; reconnecting does not restore tournament eligibility.

## 7. Walk through the major controls

### Personal layouts

Before joining an event, a player runs `/event layout edit`, rearranges the borrowed kit using their inventory screen, then `/event layout save`. Armor/offhand are included. They must retain exactly the same items, metadata and total quantities. `/event layout cancel` exits without saving. Their original inventory is restored either way. A host kit change invalidates incompatible saved layouts.

### Rekit versus revival

Hosts use `/event rekit on` and `/event rekit off`; `/event rekit-toggle` also works. These commands update the current main-event setting and save the default for future events. Timeline stages can turn it off later. **Rekitting is always disabled in gulag**, including when main-event rekit is on. The starting duel kit and winner's revival kit still apply. Rekit clears the current event inventory before applying the saved kit. `rekit.per-player-limit: 0` means unlimited requests.

### Border

`/event border 10 60` shrinks the current square to 10 blocks full width over 60 game seconds. `/event border pause` and `/event border resume` control shrink progression. `/event border center` centers it at the host's current position only if the entire current border still fits the main region.

### Timeline

Default automatic stages announce fighting, begin shrink at 120 seconds, disable rekit at 300 seconds and close gulag at 420 seconds. The two floor drops wait for a host trigger. Stages are strictly ordered: a waiting stage blocks later stages.

`/event stage next` executes the next stage; `/event stage delay 30` delays it; `/event stage skip` discards it without performing the action. Configure future event stages through `/event timeline ...` or the live config while idle; see ADMIN.md for exact syntax.

### Gulag

An eligible main death joins the queue. Two queued players teleport to a fresh location in a free gulag world. By default, each player gets one chance, a duel lasts at most 120 seconds and queue wait expires after 1,800 seconds (the default seven-minute gulag-close stage usually acts first). Draws eliminate both. Winners return safely inside the active main border with brief protection that ends when they attack. Waiting/duelling/returning players prevent a premature tournament winner. Existing configs retain their saved queue timeout; update `gulag.unmatched-seconds` if upgrading from the earlier 60-second default.

`/event gulag close` eliminates waiting/active duel players, but keeps already earned pending returns valid. Use four or more participants to explore queues and two simultaneous matches comfortably.

### Terrain drops

`/event drop deepslate` warns, relocates main fighters, clears above the configured intermediate Y, and safely returns them. `/event drop bedrock` also closes gulag and disables rekit. Their current inventory/health are preserved through relocation. The existing floor is preserved, not converted to another material. Combat/timers pause during clearing.

A drop changes the live arena. A reset pastes the saved original template. They are different operations. Always test both drops on your practice arena before a tournament.

## 8. Results and recovery

Reports live in your server's `plugins\CrystalPvPEvents\results` directory as UUID-named text and JSON. They include placements, eliminations, gulag wins and elapsed survival time. Crystal/environment deaths can be unattributed; the plugin does not guess a nearby killer.

Discord starts disabled with a blank webhook URL. Leave it off during local testing. Enabling it later requires your own private webhook URL in the live config. Do not paste that URL into a public guide or upload the live config publicly.

If the server crashes, pending original-player backups recover when players join. The event is aborted rather than resumed. Run reset before reopening. Never delete pending `plugins\CrystalPvPEvents\players\*.yml` files to resolve an error. Failed/interrupted arena restoration retains a dirty lock; fix the cause and reset again. Completed operation timings appear in console and `arenas\<name>\last-operation.txt`.

## 9. Troubleshooting

| Symptom | Check |
| --- | --- |
| Unknown `/event` command | Correct JAR installed once? Paper/Java version correct? First error in latest.log? |
| Host command denied | Operator status or `crystal.host` permission |
| Cannot open registration | `/event preflight`; all players outside selected arenas; prior reset complete |
| Main/gulag backup missing | Backup was taken after saving that region; correct arena name; operation finished |
| Unsafe spawn | Stand on solid ground, clear feet/head blocks, inside initial square, then re-save |
| Missing safe floor | Correct configured Y within bounds; solid usable ground near border center |
| Layout will not save | Same items, metadata and quantities as current main kit; nothing consumed/added |
| Rekit unavailable | Alive in main fight? Toggle on? Cooldown expired? Limit remaining? No terrain work? |
| Stage does not happen | Earlier stage waiting? Host trigger? Outstanding delay? Time/player threshold reached? |
| No winner yet | Check gulag queue, active matches and earned returns in status |
| Arena remains locked | Current job still running or prior job failed; inspect console and reset after fixing storage |
| Reset is slow | Large volume including air; conservative per-tick budget; inspect recorded times before tuning |

Use `/event reload` to reload this plugin's config while idle. Avoid the server-wide `/reload` command. To replace the JAR, stop the server and restart after copying the new version.

## 10. Gameplay and performance testing

You have accepted the EULA, so the isolated Paper smoke runner can now run. That checks real structure backup/restoration and terrain clearing; it does not replace client gameplay or loaded-server testing.

Use this template to brief the person running the gameplay test.

> Set up a separate local practice server for CrystalPvPEvents. Use [folder path]. I will join with [number] Minecraft Java 1.21.11 players. Prepare the test arena, guide us through registration, layouts, rekit, gulag, borders, drops and resets, inspect the logs, and fix failures.

Keep the PC on and the server running during the session. Record the server folder, whether it is a staging copy or a live server, which plugins are installed, and how many players will join. The test operator can inspect logs and configuration, run console commands, and record measurements. Human players can verify combat feel, client UI and visual behavior. Test clients may automate selected scenarios where feasible; that requires a defined player-count/scenario plan and still does not establish human gameplay quality. Share access through available tools rather than posting account passwords or tokens.

Use this template to define the performance test.

> Benchmark CrystalPvPEvents on my staging server in [folder]. Use a [width × depth × height] arena and target [player count] players. Measure idle and combat TPS/MSPT, backup/reset/drop duration, worst batch time, CPU and memory. Test with these other plugins: [list]. Save a report and fix regressions. Do not modify my live server.

Useful measurements include a quiet-server baseline, active combat, simultaneous gulag resets, main reset and both drops. Run multiple trials and record hardware, JVM settings, world size and player count. A tiny zero-player smoke test is only a functional baseline. It cannot support a claim that a full-size 50- or 100-player tournament will be lag-free. Use the actual map or a staging copy and the intended player count for representative results.
