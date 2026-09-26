# CrystalPvPEvents host guide

## Setup

Fresh installations automatically create a plains-style main world and two gulag worlds, including arena bounds, 50 main spawns, a lobby and snapshots. Run `/event worlds status`, wait until ready, `/event worlds lobby`, then save the kit and run preflight. `/event worlds create` starts or resumes preparation. Existing manually configured arenas are preserved. See [GETTING-STARTED.md](GETTING-STARTED.md) for world settings, folder migration and the full automatic walkthrough. The instructions below are for optional manual setup.

Use dedicated event regions. Reset removes **all non-player entities inside each selected cuboid**, including decorative entities, then restores saved blocks and block entities. Keep unrelated builds, villagers, item frames, armor stands and storage outside the selection. World folders are never deleted/replaced. Main and gulag regions must not overlap.

Selections use inclusive integer block corners, including Y. X/Z 0..127 and Y -60..131 select 128 × 128 × 192 = 3,145,728 blocks, fitting the default 4,000,000-block limit. The initial border is a square centered in the selection, with the smaller X/Z dimension as its **full width**, not radius. Select the entire vertical range that participants may modify.

1. At the corners, `/event pos1` and `/event pos2`, then `/event arena`. Saving a new main selection clears its spawn list.
2. At a safe lobby outside all arenas, `/event lobby`.
3. At two or more safe positions inside the initial square, `/event spawn add`. `/event spawn clear` removes all spawns. Add enough separated spawns for the player count; excess players share saved locations cyclically.
4. Arrange the desired host inventory, armor and offhand, then `/event kit save`. Empty kits, bundles and block-container items are rejected to prevent hidden supplies.
5. Configure `arena.deepslate-y` and `arena.bedrock-y` inside the main bounds. Build solid floors wide enough for the intended border. The selection minimum Y and both configured floor layers are protected during events.
6. Move everyone outside the main arena and `/event backup main`. Wait for completion; `/event status` reports progress.

Gulag is enabled by default. Configure one or more cells, or set `gulag.enabled: false` before `/event reload`:

1. Select another region with `/event pos1` and `/event pos2`.
2. `/event gulag add cell1` (letters/digits/underscore/hyphen; maximum 128 locations).
3. At safe opposing positions, `/event gulag spawn cell1 a` and `/event gulag spawn cell1 b`.
4. Move everyone outside it and `/event backup cell1`.
5. Repeat for additional locations. One duel runs per world, including while a finished duel resets; distinct worlds can run simultaneously. Every location is used at most once per event. Manually supplied worlds must already be loaded; owned automatic worlds load on startup.

Gulag uses the main kit by default. To use a separate kit, `/event kit save gulag` and set `gulag.separate-kit: true`. Personal main layouts do not apply to a separate gulag kit.

After config edits, `/event reload` while idle. `/event preflight` reports missing kits, backups, safe lobby/spawns, gulag setup and drop floors. `/event open` also requires all players outside the selected regions. Reset dirty arenas before replacing/removing their setup or backing up again. Change saved world identities only while the server is stopped and preserve the corresponding setup/backups.

## Player commands and kit enforcement

| Command | Effect |
| --- | --- |
| `/event join` | Join registration; save original state first |
| `/event leave` | Leave registration, cancel layout editing, or forfeit a running event; restore original state |
| `/event status` | Phase, living/potential players, queue/matches, border, rekit, stage and progress |
| `/event rekit` | Replace the living main fighter's kit if enabled and cooldown/limit permit |
| `/event layout edit` | Outside the event, borrow the exact main kit in a frozen layout editor |
| `/event layout save` | Save only if all item metadata and total quantities match, then restore original items |
| `/event layout cancel` | Restore original items without saving |

Layouts cover inventory, hotbar, armor and offhand. A changed host kit invalidates mismatching layouts; the host arrangement is used until the player saves a compatible one. During participation/editing, external commands, containers, crafting, item drops and pickups are blocked. Normal inventory rearrangement remains available. Event block breaks drop no extra supplies.

Rekit replenishes an already living main fighter; it never revives. `rekit.cooldown-seconds` controls spacing; `rekit.per-player-limit: 0` means unlimited requests. Zero cooldown permits immediate repeat requests. Initial kits and return kits do not consume requests. `/event rekit-toggle` does not affect gulag revival. Other plugins must respect cancelled events and avoid directly replacing participant inventories or teleporting them; arbitrary console/admin/plugin mutations are outside this enforcement boundary.

## Host controls

All controls require `crystal.host` (default op). `/event gui` shows living names, queue/match status, full border width, rekit, current/next stage and action consequences in hover text. Reopen the menu to refresh its snapshot. Player bossbar/sidebar update once per second.

| Command | Effect |
| --- | --- |
| `/event open` | Validate setup and open registration; reserve arena until reset |
| `/event start` | Close registration, count down, equip/teleport players and start fighting |
| `/event stop` | Abort, save local report and restore players; never post an aborted Discord result |
| `/event reset` | Stop if needed; restore main then gulags; unlock only when all finish |
| `/event border 20 120` | Shrink square to **20 blocks full width** over 120 game seconds |
| `/event border pause` / `resume` | Pause/resume shrink progression |
| `/event border center` | Center at host position only if the entire current square fits the selection |
| `/event rekit-toggle` | Toggle living-main-player replenishment |
| `/event rekit on` / `/event rekit off` | Set main rekit and save its default; gulag rekit always stays disabled |
| `/event worlds create` | Prepare owned worlds on an unconfigured install, or resume interrupted setup |
| `/event worlds status` | Show preparation status and arena operation progress |
| `/event worlds lobby` | Teleport a host who is outside the event to the saved lobby |
| `/event gulag close` | Eliminate waiting/active duel players; preserve already earned pending returns |
| `/event drop deepslate` | Warn, pause combat, relocate, clear above configured floor and safely return players |
| `/event drop bedrock` | Close gulag, disable rekit, then warn and perform the bedrock-floor drop |
| `/event stage next` | Execute next stage, subject to any outstanding delay |
| `/event stage skip` | Skip next stage without its effect |
| `/event stage delay 30` | Delay next stage 30 seconds; repeated delays accumulate |
| `/event results` | Latest session summary and report-folder location |
| `/event reload` | Reload config/timeline while idle |

Border commands shrink rather than expand. They use per-player borders, not the world's global border. Outside the shrinking square, fighters take plugin damage once per second. The global world border should contain all arenas. Event seconds follow server ticks; they can run slower than wall-clock time under lag. Selected cuboids constrain movement/editing independently of the shrink.

## Timelines

Registration/countdown are fixed host-controlled lifecycle phases. Editable stages begin at fighting time zero. Defaults: Fighting announce (0s), Shrink (120s), Rekit off (300s), Gulag close (420s), Deepslate drop (host), Bedrock finale (host).

```text
/event timeline list
/event timeline add time 180 shrink Second shrink
/event timeline add players 4 gulag-close Final four
/event timeline add host 0 bedrock-finale Final floor
/event timeline remove 3
/event timeline preset
```

Changes are saved for subsequent events. Remove indices are 1-based. Triggers: `time` (elapsed fighting seconds), `players` (at most N potential contenders, including pending revivals), `host`. Actions: `announce`, `shrink`, `rekit-off`, `gulag-close`, `deepslate-drop`, `bedrock-finale`. Stages are strictly ordered; a waiting host/player-count stage blocks later stages until triggered/skipped. At most one automatic stage executes per game second. Delays apply to host execution too; skip discards a stage's delay. Terrain operations pause fighting/gulag timers and shrink until complete.

## Gulag rules

Defaults: one comeback opportunity, 120-second duels, 60-second maximum queue wait, final duel width 5 blocks, 5 seconds of return protection. Each free clean cell pairs the first two alive queued players. Sidebar shows position/time. Used cells reset before reuse.

Eligible main deaths join the queue. Duel winners get the main kit and a safe location inside the current main border. Protection ends on attacking, projectile launch or crystal placement. If no safe location is found, the earned return waits as a contender; the host may need to stop/reset damaged ground. Search work is bounded and retried once per second.

Disconnect/leave is always a forfeit; immediate reconnect does not reverse it. Both dead/disconnected duelists are eliminated. Timer draws eliminate both. Queue expiry eliminates unmatched players rather than granting free wins. Cutoff eliminates waiting/active duel players; already earned pending returns stay eligible. Losses are processed together before winner checks. A final simultaneous wipe produces a draw and shared elimination rank. No winner is declared while waiting/duelling/returning players remain potential contenders.

## Backups, terrain and recovery

Templates are versioned structure cubes under `arenas/<name>/snapshot-<uuid>`. The manifest is published only after all cubes save. Old generations remain available; archive unreferenced ones only while stopped. Failed backups can be retried after storage repair. Interrupted/failed reset or drop keeps a persistent dirty marker and requires reset. Progress is visible in status; completed operation timing is written to `arenas/<name>/last-operation.txt` and console.

A live drop clears only above the configured Y through the selected upper bound. It preserves the existing floor material and does not paste a template. Clearing proceeds top-down in bounded batches. Main fighters retain current inventory, consumables and health through relocation and receive landing protection. Final reset restores saved blocks/block entities, including explosion holes and added fluids/blocks, and removes all non-player entities in the selection. It does not recreate original decorative entities.

Original inventory/armor/offhand, location, mode, health, food, XP, potion effects and flight state are saved before entry. Normal exits restore them; pending files recover on join after a restart. Session scoreboards and virtual borders restore when available; external plugin UI/border assignments cannot be reconstructed after process restart. A crash aborts the event rather than resuming it: recover players, reset arenas, then reopen. Do not delete pending `players/*.yml` recovery files. Keep normal server backups too.

## Results and Discord

UUID-named text/JSON reports in `results` include player names/UUIDs, placement, credited eliminations, gulag wins and elapsed survival seconds (including gulag/wait time until final elimination). Only directly lethal melee/projectile hits receive player credit. Crystal chains and environmental deaths remain unattributed; there is no nearest-player guess. Ties share ranks. Unresolved aborted placements use zero/em dash.

Discord is off by default with a blank URL. The build includes no real URL and sends no real message. To enable later, set your own standard HTTPS Discord webhook URL and `discord.enabled: true`, then reload while idle. Keep that config secret. Only Discord webhook hosts are accepted. Mentions are disabled; Discord text is truncated to fit, while local reports remain complete.

HTTP runs off the server tick, with connection/request timeouts, bounded 429 retries honoring `Retry-After`, and no redirects. A persistent claim precedes delivery. An ambiguous timeout/crash is not replayed automatically, avoiding duplicate posts at the cost of possible missed delivery. Inspect `<event-uuid>.delivery` and the channel before any manual resend. Logs omit the webhook secret. Aborted events are never posted.
