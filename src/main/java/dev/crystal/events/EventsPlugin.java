package dev.crystal.events;

import static dev.crystal.events.Tournament.Phase.*;
import static dev.crystal.events.Tournament.State.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

public class EventsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
  final Tournament game = new Tournament();
  ArenaStore arenas;
  Kits kits;
  PlayerBackup backups;
  Results results;
  public ManagedWorlds managedWorlds;
  final GulagRotation rotation = new GulagRotation();
  Region main;
  Location lobby;
  final List<Location> spawns = new ArrayList<>();
  final LinkedHashMap<String, GulagArena> gulags = new LinkedHashMap<>();
  final Map<UUID, Location> pos1 = new HashMap<>(), pos2 = new HashMap<>();
  final Set<UUID> editors = new HashSet<>(), internalTeleport = new HashSet<>();
  final Map<UUID, Long> protection = new HashMap<>();
  final Map<UUID, Scoreboard> oldBoards = new HashMap<>();
  final Map<UUID, WorldBorder> oldBorders = new HashMap<>();
  final Set<UUID> losses = new LinkedHashSet<>(), disconnects = new HashSet<>();
  final Map<UUID, UUID> directKillers = new HashMap<>();
  Timeline timeline;
  WorldBorder border;
  BossBar bar;
  BukkitTask clock;
  int countdown, dropWarning = -1, dropFloor;
  boolean terrainBusy, needsReset;
  long tick;
  double targetWidth, shrinkRate;
  boolean borderPaused;
  int activeFloor = Integer.MIN_VALUE;
  String lastResult = "No completed event this session";

  record GulagArena(String name, Region region, Location a, Location b) {}

  static final class Match {
    final GulagArena arena;
    final UUID a, b;
    final WorldBorder border;
    int left;
    boolean ready;
    final Set<Chunk> heldChunks = new HashSet<>();

    Match(GulagArena arena, UUID a, UUID b, WorldBorder border, int left) {
      this.arena = arena;
      this.a = a;
      this.b = b;
      this.border = border;
      this.left = left;
    }
  }

  final Map<String, Match> matches = new HashMap<>();

  @Override
  public void onEnable() {
    saveDefaultConfig();
    arenas = new ArenaStore(this);
    kits = new Kits(new File(getDataFolder(), "kits"));
    backups = new PlayerBackup(new File(getDataFolder(), "players"));
    results = new Results(new File(getDataFolder(), "results"));
    managedWorlds = new ManagedWorlds(this);
    managedWorlds.loadOwned();
    var rotationFile =
        YamlConfiguration.loadConfiguration(new File(getDataFolder(), "gulag-rotation.yml"));
    for (String world : rotationFile.getKeys(false))
      rotation.lastByWorld.put(world, rotationFile.getString(world));
    loadSetup();
    needsReset = main != null && arenas.dirty("main");
    timeline = loadTimeline();
    bar = Bukkit.createBossBar("CrystalPvP • Closed", BarColor.PURPLE, BarStyle.SOLID);
    getCommand("event").setExecutor(this);
    getCommand("event").setTabCompleter(this);
    Bukkit.getPluginManager().registerEvents(new EventRules(this), this);
    clock = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
    Bukkit.getScheduler().runTaskLater(this, () -> managedWorlds.autoStart(), 20);
    for (Player p : Bukkit.getOnlinePlayers()) if (backups.exists(p)) backups.restore(p);
    getLogger().info("CrystalPvPEvents loaded; Paper 1.21.11 / Java 21 target. Use /event help.");
  }

  @Override
  public void onDisable() {
    if (clock != null) clock.cancel();
    if (bar != null) bar.removeAll();
    for (Match m : matches.values()) releaseGulagChunks(m);
    for (Player p : Bukkit.getOnlinePlayers()) if (backups != null && backups.exists(p)) restore(p);
    if (results != null) results.close();
  }

  void loadSetup() {
    var c = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "setup.yml"));
    main = Region.load(c.getConfigurationSection("main"));
    lobby = c.getLocation("lobby");
    spawns.clear();
    for (Object o : c.getList("spawns", List.of())) if (o instanceof Location l) spawns.add(l);
    gulags.clear();
    var g = c.getConfigurationSection("gulags");
    if (g != null)
      for (String n : g.getKeys(false)) {
        var s = g.getConfigurationSection(n);
        gulags.put(
            n,
            new GulagArena(
                n,
                Region.load(s.getConfigurationSection("region")),
                s.getLocation("a"),
                s.getLocation("b")));
      }
  }

  void saveSetup() throws IOException {
    var c = new YamlConfiguration();
    if (main != null) main.save(c.createSection("main"));
    c.set("lobby", lobby);
    c.set("spawns", spawns);
    for (var g : gulags.values()) {
      g.region.save(c.createSection("gulags." + g.name + ".region"));
      c.set("gulags." + g.name + ".a", g.a);
      c.set("gulags." + g.name + ".b", g.b);
    }
    c.save(new File(getDataFolder(), "setup.yml"));
  }

  Timeline loadTimeline() {
    List<Timeline.Stage> stages = new ArrayList<>();
    for (Map<?, ?> m : getConfig().getMapList("timeline"))
      stages.add(
          new Timeline.Stage(
              String.valueOf(m.get("name")),
              String.valueOf(m.get("trigger")),
              Long.parseLong(String.valueOf(m.get("value"))),
              String.valueOf(m.get("action"))));
    return new Timeline(stages);
  }

  void tell(CommandSender p, String s) {
    p.sendMessage(ChatColor.LIGHT_PURPLE + "[Event] " + ChatColor.WHITE + s);
  }

  void announce(String s) {
    Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[CrystalPvP] " + ChatColor.WHITE + s);
  }

  boolean running() {
    return game.phase == FIGHTING;
  }

  boolean reserved() {
    return terrainBusy
        || game.phase == REGISTRATION
        || game.phase == COUNTDOWN
        || game.phase == FIGHTING
        || needsReset;
  }

  Tournament.Entry entry(Player p) {
    return game.entries.get(p.getUniqueId());
  }

  boolean managed(Player p) {
    return backups.exists(p) && (entry(p) != null || editors.contains(p.getUniqueId()));
  }

  Player player(CommandSender s) {
    if (!(s instanceof Player p)) throw new IllegalArgumentException("Player-only command");
    return p;
  }

  void host(CommandSender s) {
    if (!s.hasPermission("crystal.host"))
      throw new IllegalArgumentException("Host permission required");
  }

  void idle() {
    if (game.phase != CLOSED && game.phase != FINISHED)
      throw new IllegalStateException("Close/reset the event before editing setup");
    if (!arenas.locked.isEmpty() || terrainBusy)
      throw new IllegalStateException("Arena operation running");
  }

  void requireMain() {
    if (main == null)
      throw new IllegalArgumentException("Select arena first: /event pos1, pos2, arena");
  }

  Region selection(Player p) {
    if (!pos1.containsKey(p.getUniqueId()) || !pos2.containsKey(p.getUniqueId()))
      throw new IllegalArgumentException("Set /event pos1 and pos2 first");
    return Region.between(pos1.get(p.getUniqueId()), pos2.get(p.getUniqueId()));
  }

  int integer(String s, int min, int max) {
    int n = Integer.parseInt(s);
    if (n < min || n > max) throw new IllegalArgumentException("Value must be " + min + ".." + max);
    return n;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
    try {
      if (a.length == 0) {
        if (sender.hasPermission("crystal.host") && sender instanceof Player p) gui(p);
        else status(sender);
        return true;
      }
      String sub = a[0].toLowerCase(Locale.ROOT);
      switch (sub) {
        case "help" ->
            tell(
                sender,
                "Players: /event join|leave|status|rekit|layout edit|layout save|layout cancel."
                    + " Hosts: pos1, pos2, arena, lobby, spawn add|clear, kit save [gulag], backup"
                    + " [name], gulag add <name>|spawn <name> a|b|remove <name>|close, preflight,"
                    + " open, start, stop, reset, gui, border <width>"
                    + " <seconds>|pause|resume|center, stage next|skip|delay <seconds>, timeline"
                    + " list|add <trigger> <value> <action> <name>|remove <index>|preset, drop"
                    + " deepslate|bedrock, worlds create|status|lobby, rekit on|off, results,"
                    + " reload.");
        case "status" -> status(sender);
        case "join" -> join(player(sender));
        case "leave" -> leave(player(sender));
        case "rekit" -> {
          if (a.length > 1) {
            host(sender);
            if (!Set.of("on", "off").contains(a[1]))
              throw new IllegalArgumentException(
                  "/event rekit on|off (host), or /event rekit (player)");
            setRekit(a[1].equals("on"));
            return true;
          }
          Player p = player(sender);
          if (terrainBusy
              || dropWarning >= 0
              || !game.requestRekit(
                  p.getUniqueId(),
                  getConfig().getInt("rekit.cooldown-seconds"),
                  getConfig().getInt("rekit.per-player-limit")))
            throw new IllegalStateException(
                "Rekit unavailable: fighting/living only; check cooldown, limit and host toggle");
          kits.give(p, false);
          tell(p, "Kit replenished. Rekits used: " + entry(p).rekits);
        }
        case "layout" -> layout(player(sender), a);
        default -> {
          host(sender);
          switch (sub) {
            case "worlds" -> {
              if (a.length < 2 || a[1].equals("status"))
                tell(sender, managedWorlds.status() + " | " + arenas.progress);
              else if (a[1].equals("create")) managedWorlds.create(sender);
              else if (a[1].equals("lobby")) {
                if (lobby == null) throw new IllegalStateException("No lobby saved yet");
                if (managed(player(sender)))
                  throw new IllegalStateException("Leave the event first");
                teleport(player(sender), lobby);
              } else throw new IllegalArgumentException("/event worlds create|status|lobby");
            }
            case "gui" -> gui(player(sender));
            case "pos1", "pos2" -> {
              idle();
              Player p = player(sender);
              (sub.equals("pos1") ? pos1 : pos2).put(p.getUniqueId(), p.getLocation());
              tell(p, sub + " saved");
            }
            case "arena" -> {
              idle();
              if (needsReset) throw new IllegalStateException("Reset old arena first");
              Region r = selection(player(sender));
              arenas.validate(r);
              if (r.volume() > getConfig().getLong("arena.max-blocks"))
                throw new IllegalArgumentException("Selection exceeds arena.max-blocks");
              for (var g : gulags.values())
                if (r.overlaps(g.region))
                  throw new IllegalArgumentException("Main/gulag arenas must not overlap");
              main = r;
              spawns.clear();
              saveSetup();
              tell(
                  sender,
                  "Main arena saved: "
                      + r.volume()
                      + " blocks. Add safe spawns, lobby and backup.");
            }
            case "lobby" -> {
              idle();
              lobby = player(sender).getLocation();
              saveSetup();
              tell(sender, "Lobby saved");
            }
            case "spawn" -> {
              idle();
              requireMain();
              if (a.length < 2) throw new IllegalArgumentException("spawn add|clear");
              if (a[1].equals("clear")) spawns.clear();
              else if (a[1].equals("add")) {
                Location l = player(sender).getLocation();
                if (!main.contains(l) || !safe(l))
                  throw new IllegalArgumentException(
                      "Stand at safe feet position inside main arena");
                spawns.add(l);
              } else throw new IllegalArgumentException("spawn add|clear");
              saveSetup();
              tell(sender, "Saved spawns: " + spawns.size());
            }
            case "kit" -> {
              idle();
              if (a.length < 2 || !a[1].equals("save"))
                throw new IllegalArgumentException("kit save [gulag]");
              kits.saveKit(player(sender), a.length > 2 && a[2].equals("gulag"));
              tell(sender, "Kit saved; incompatible personal layouts are automatically ignored");
            }
            case "backup" -> {
              idle();
              String n = a.length > 1 ? a[1] : "main";
              Region r = region(n);
              ensureEmpty(r);
              arenas.snapshot(
                  n, r, error -> tell(sender, error == null ? "Backup complete: " + n : error));
              tell(sender, "Backup started. Arena locked; /event status shows progress.");
            }
            case "gulag" -> gulagCommand(sender, a);
            case "preflight" -> {
              var errors = preflight();
              tell(sender, errors.isEmpty() ? "Preflight passed" : String.join("; ", errors));
            }
            case "open" -> open();
            case "start" -> {
              if (game.phase != REGISTRATION)
                throw new IllegalStateException("Open registration first");
              if (game.entries.size() < getConfig().getInt("minimum-players", 2))
                throw new IllegalStateException("Not enough players");
              game.phase = COUNTDOWN;
              countdown = getConfig().getInt("countdown-seconds", 20);
              announce("Registration closed. Fighting in " + countdown + " seconds.");
            }
            case "stop" -> stop(true);
            case "reset" -> reset(sender);
            case "border" -> borderCommand(player(sender), a);
            case "stage" -> {
              if (!running() || terrainBusy || dropWarning >= 0)
                throw new IllegalStateException("Stages require fighting and no terrain work");
              if (a.length < 2)
                throw new IllegalArgumentException("stage next|skip|delay <seconds>");
              switch (a[1]) {
                case "next" -> {
                  var s = timeline.poll(game.seconds, game.remaining(), true);
                  if (s != null) stage(s);
                }
                case "skip" -> timeline.skip();
                case "delay" -> timeline.delay(game.seconds, integer(a[2], 1, 86400));
                default -> throw new IllegalArgumentException("Unknown stage action");
              }
              status(sender);
            }
            case "timeline" -> timelineCommand(sender, a);
            case "drop" -> {
              if (a.length != 2 || !Set.of("deepslate", "bedrock").contains(a[1]))
                throw new IllegalArgumentException("drop deepslate|bedrock");
              scheduleDrop(a[1].equals("bedrock"));
            }
            case "results" ->
                tell(sender, lastResult + ". Full reports: plugins/CrystalPvPEvents/results/");
            case "reload" -> {
              idle();
              reloadConfig();
              timeline = loadTimeline();
              tell(sender, "Config and timeline reloaded");
            }
            case "rekit-toggle" -> setRekit(!game.rekit);
            default -> throw new IllegalArgumentException("Unknown command. /event help");
          }
        }
      }
    } catch (Exception e) {
      tell(
          sender,
          e instanceof ArrayIndexOutOfBoundsException
              ? "Missing arguments; /event help"
              : Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
    }
    return true;
  }

  Region region(String n) {
    if (n.equals("main")) {
      requireMain();
      return main;
    }
    var g = gulags.get(n);
    if (g == null) throw new IllegalArgumentException("Unknown arena");
    return g.region;
  }

  void ensureEmpty(Region r) {
    if (r.w().getPlayers().stream().anyMatch(p -> r.contains(p.getLocation())))
      throw new IllegalStateException("Move all players outside the selected area first");
  }

  void gulagCommand(CommandSender s, String[] a) throws IOException {
    if (a.length < 2)
      throw new IllegalArgumentException("gulag add <name>|spawn <name> a|b|remove <name>|close");
    if (a[1].equals("close")) {
      closeGulag();
      return;
    }
    idle();
    String n = a[2];
    if (n.equals("main") || !n.matches("[A-Za-z0-9_-]{1,32}"))
      throw new IllegalArgumentException("Invalid gulag name");
    switch (a[1]) {
      case "add" -> {
        if (gulags.size() >= 128) throw new IllegalArgumentException("Maximum 128 gulag locations");
        if (gulags.containsKey(n)) throw new IllegalArgumentException("Name exists");
        Region r = selection(player(s));
        arenas.validate(r);
        if (r.volume() > getConfig().getLong("arena.max-blocks"))
          throw new IllegalArgumentException("Arena too large");
        if (main != null && main.overlaps(r)
            || gulags.values().stream().anyMatch(g -> r.overlaps(g.region)))
          throw new IllegalArgumentException("Arenas must not overlap");
        gulags.put(n, new GulagArena(n, r, null, null));
      }
      case "spawn" -> {
        var g = gulags.get(n);
        if (g == null) throw new IllegalArgumentException("Unknown gulag");
        Location l = player(s).getLocation();
        if (!g.region.contains(l) || !safe(l) || !Set.of("a", "b").contains(a[3]))
          throw new IllegalArgumentException("Stand safely inside gulag, use a or b");
        gulags.put(
            n, new GulagArena(n, g.region, a[3].equals("a") ? l : g.a, a[3].equals("b") ? l : g.b));
      }
      case "remove" -> {
        if (arenas.dirty(n))
          throw new IllegalArgumentException("Reset dirty gulag before removing");
        gulags.remove(n);
      }
      default -> throw new IllegalArgumentException("Unknown gulag action");
    }
    saveSetup();
    tell(s, "Gulag setup saved. Take /event backup " + n + " after setting spawns.");
  }

  void timelineCommand(CommandSender s, String[] a) {
    idle();
    if (a.length < 2 || a[1].equals("list")) {
      int i = 1;
      for (var n : timeline.stages) tell(s, (i++) + ": " + n);
      return;
    }
    List<Timeline.Stage> list = new ArrayList<>(timeline.stages);
    switch (a[1]) {
      case "add" ->
          list.add(
              new Timeline.Stage(
                  String.join(" ", Arrays.copyOfRange(a, 5, a.length)),
                  a[2],
                  Long.parseLong(a[3]),
                  a[4]));
      case "remove" -> list.remove(integer(a[2], 1, list.size()) - 1);
      case "preset" -> {
        list.clear();
        list.addAll(
            List.of(
                new Timeline.Stage("Fighting", "time", 0, "announce"),
                new Timeline.Stage("Shrink", "time", 120, "shrink"),
                new Timeline.Stage("Rekit off", "time", 300, "rekit-off"),
                new Timeline.Stage("Gulag close", "time", 420, "gulag-close"),
                new Timeline.Stage("Deepslate drop", "host", 0, "deepslate-drop"),
                new Timeline.Stage("Bedrock finale", "host", 0, "bedrock-finale")));
      }
      default ->
          throw new IllegalArgumentException(
              "timeline list|add <time|players|host> <value> <action> <name>|remove <1-based"
                  + " index>|preset");
    }
    getConfig()
        .set(
            "timeline",
            list.stream()
                .map(
                    n ->
                        Map.of(
                            "name",
                            n.name(),
                            "trigger",
                            n.trigger(),
                            "value",
                            n.value(),
                            "action",
                            n.action()))
                .toList());
    saveConfig();
    timeline = new Timeline(list);
    tell(s, "Timeline saved");
  }

  List<String> preflight() {
    List<String> e = new ArrayList<>();
    if (managedWorlds.busy()) e.add("automatic world preparation running");
    else if (managedWorlds.incomplete())
      e.add("automatic world setup incomplete; /event worlds create to resume");
    if (main == null) {
      e.add("main arena missing");
      return e;
    }
    if (kits.main == null) e.add("event kit missing");
    if (!arenas.hasBackup("main", main)) e.add("main backup missing/mismatched");
    if (needsReset || arenas.dirty("main") || !arenas.locked.isEmpty())
      e.add("arena reset/operation required");
    if (lobby == null
        || !safe(lobby)
        || main.contains(lobby)
        || gulags.values().stream().anyMatch(g -> g.region.contains(lobby)))
      e.add("safe lobby outside arenas required");
    if (spawns.size() < 2
        || spawns.stream()
            .anyMatch(
                l ->
                    !main.contains(l) || !safe(l) || !insideSquare(l, main.center(), main.width())))
      e.add("at least 2 safe spawns inside initial square required");
    if (main.width() < 5) e.add("main width must be at least 5");
    if (getConfig().getInt("minimum-players") < 2
        || getConfig().getInt("maximum-players") < getConfig().getInt("minimum-players"))
      e.add("invalid player limits");
    if (getConfig().getBoolean("gulag.enabled")) {
      if (gulags.isEmpty()) e.add("gulag pool empty");
      for (var g : gulags.values())
        if (!arenas.hasBackup(g.name, g.region)
            || arenas.dirty(g.name)
            || g.a == null
            || g.b == null
            || !safe(g.a)
            || !safe(g.b)
            || g.a.distanceSquared(g.b) < 4
            || !insideSquare(g.a, g.region.center(), g.region.width())
            || !insideSquare(g.b, g.region.center(), g.region.width()))
          e.add("gulag " + g.name + " needs backup/safe separated spawns/reset");
      if (getConfig().getBoolean("gulag.separate-kit") && kits.gulag == null)
        e.add("separate gulag kit missing");
    }
    for (var n : timeline.stages)
      if (n.action().equals("deepslate-drop") || n.action().equals("bedrock-finale")) {
        int y =
            getConfig()
                .getInt(
                    n.action().equals("deepslate-drop") ? "arena.deepslate-y" : "arena.bedrock-y");
        if (y < main.y1() || y > main.y2() - 2 || floorLanding(y) == null)
          e.add("safe preserved floor missing at Y=" + y);
      }
    if (getConfig().getBoolean("discord.enabled")
        && !Results.validUrl(getConfig().getString("discord.webhook-url", "")))
      e.add("invalid Discord URL (withheld)");
    return e;
  }

  void open() {
    idle();
    var errors = preflight();
    if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
    ensureEmpty(main);
    for (var g : gulags.values()) ensureEmpty(g.region);
    arenas.dirty("main", true);
    needsReset = true;
    game.open(getConfig().getBoolean("gulag.enabled"), getConfig().getBoolean("rekit.enabled"));
    rotation.newEvent();
    timeline = loadTimeline();
    activeFloor = Integer.MIN_VALUE;
    border = Bukkit.createWorldBorder();
    border.setCenter(main.center());
    border.setSize(main.width());
    border.setWarningDistance(3);
    targetWidth = border.getSize();
    shrinkRate = 0;
    borderPaused = false;
    announce(
        "Registration open! /event join — one enforced kit; gulag "
            + (game.gulagOpen ? "enabled" : "disabled"));
  }

  void join(Player p) throws IOException {
    if (game.phase != REGISTRATION || game.entries.size() >= getConfig().getInt("maximum-players"))
      throw new IllegalStateException("Registration closed/full");
    if (managed(p) || game.entries.containsKey(p.getUniqueId()))
      throw new IllegalStateException("Already registered this event or editing a layout");
    if (p.isDead() || p.isInsideVehicle())
      throw new IllegalArgumentException("Be alive and leave your vehicle first");
    backups.capture(p);
    oldBoards.put(p.getUniqueId(), p.getScoreboard());
    oldBorders.put(p.getUniqueId(), p.getWorldBorder());
    game.join(p.getUniqueId(), p.getName());
    p.getInventory().clear();
    clean(p);
    p.setGameMode(GameMode.ADVENTURE);
    teleport(p, lobby);
    bar.addPlayer(p);
    tell(
        p,
        "Registered. Countdown begins when host starts; /event leave returns your original items.");
  }

  void leave(Player p) {
    if (editors.remove(p.getUniqueId())) {
      restore(p);
      return;
    }
    if (entry(p) == null) {
      if (backups.exists(p)) restore(p);
      return;
    }
    losses.add(p.getUniqueId());
    disconnects.add(p.getUniqueId());
    if (!running()) {
      game.entries.remove(p.getUniqueId());
      restore(p);
    } else tell(p, "You forfeited. Original state will be restored.");
  }

  void layout(Player p, String[] a) throws IOException {
    if (a.length < 2) throw new IllegalArgumentException("layout edit|save|cancel");
    if (a[1].equals("edit")) {
      if (managed(p) || kits.main == null || p.isDead() || p.isInsideVehicle())
        throw new IllegalStateException("Leave event first; a saved kit is required");
      backups.capture(p);
      editors.add(p.getUniqueId());
      kits.give(p, false);
      tell(
          p,
          "Rearrange inventory, armor and offhand, then /event layout save. All kit quantities must"
              + " match; /event layout cancel restores original items.");
    } else if (editors.contains(p.getUniqueId())) {
      if (a[1].equals("save")) kits.saveLayout(p);
      else if (!a[1].equals("cancel")) throw new IllegalArgumentException("layout save|cancel");
      editors.remove(p.getUniqueId());
      restore(p);
      tell(p, "Layout editor closed; original inventory restored");
    } else throw new IllegalStateException("Use /event layout edit first");
  }

  void clean(Player p) {
    p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
    p.setFireTicks(0);
    p.setFallDistance(0);
    p.setFoodLevel(20);
    p.setSaturation(5);
    p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
    p.setLevel(0);
    p.setExp(0);
    p.setTotalExperience(0);
    p.setAllowFlight(false);
  }

  void teleport(Player p, Location l) {
    internalTeleport.add(p.getUniqueId());
    try {
      if (!p.teleport(l)) throw new IllegalStateException("Teleport rejected by another plugin");
    } finally {
      internalTeleport.remove(p.getUniqueId());
    }
  }

  void restore(Player p) {
    internalTeleport.add(p.getUniqueId());
    try {
      if (backups.restore(p)) {
        bar.removePlayer(p);
        protection.remove(p.getUniqueId());
        Scoreboard b = oldBoards.remove(p.getUniqueId());
        if (b != null) p.setScoreboard(b);
        if (oldBorders.containsKey(p.getUniqueId()))
          p.setWorldBorder(oldBorders.remove(p.getUniqueId()));
      }
    } finally {
      internalTeleport.remove(p.getUniqueId());
    }
  }

  void begin() {
    if (game.entries.size() < getConfig().getInt("minimum-players", 2)) {
      game.phase = REGISTRATION;
      announce("Countdown cancelled: too few players");
      return;
    }
    if (spawns.stream().anyMatch(l -> !safe(l))) {
      game.phase = REGISTRATION;
      announce("Countdown cancelled: unsafe spawn");
      return;
    }
    int i = 0;
    for (var e : game.entries.values()) {
      Player p = Bukkit.getPlayer(e.id);
      if (p == null) continue;
      teleport(p, spawns.get(i++ % spawns.size()));
      clean(p);
      kits.give(p, false);
      p.setGameMode(GameMode.SURVIVAL);
      p.setWorldBorder(border);
    }
    game.start();
    announce(
        "Fight! Last player standing wins. "
            + (game.gulagOpen ? "First death offers a gulag chance." : "No gulag returns."));
  }

  void tick() {
    tick++;
    try {
      processLosses();
      if (running() && !terrainBusy) {
        resolveMatches();
        returnPlayers();
        if (game.settle()) {
          finish(false);
          return;
        }
      }
      if (tick % 20 != 0) return;
      if (game.phase == COUNTDOWN) {
        if (--countdown <= 0) begin();
        else if (countdown <= 5 || countdown % 10 == 0) announce("Fighting in " + countdown + "s");
      }
      if (running() && !terrainBusy) {
        game.seconds++;
        if (dropWarning >= 0) {
          if (dropWarning-- == 0) performDrop();
          else if (dropWarning < 5 || dropWarning % 5 == 0)
            announce(
                "Terrain clears above Y="
                    + dropFloor
                    + " in "
                    + dropWarning
                    + "s. Players will be relocated safely.");
        }
        if (!terrainBusy) {
          advanceBorder();
          gulagTick();
          if (dropWarning < 0) {
            var stage = timeline.poll(game.seconds, game.remaining(), false);
            if (stage != null) stage(stage);
          }
        }
      }
      hud();
    } catch (Exception e) {
      getLogger()
          .severe(
              "Event tick halted safely: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      if (game.phase != CLOSED && game.phase != FINISHED) stop(true);
    }
  }

  void processLosses() {
    if (losses.isEmpty()) return;
    Set<UUID> activeBeforeBatch =
        game.entries.values().stream()
            .filter(e -> e.state == ACTIVE)
            .map(e -> e.id)
            .collect(Collectors.toSet());
    for (UUID id : new ArrayList<>(losses)) {
      var e = game.entries.get(id);
      if (e == null) continue;
      boolean quit = disconnects.contains(id);
      UUID killer = directKillers.remove(id);
      if (e.state == ACTIVE && killer != null) {
        var k = game.entries.get(killer);
        if (k != null && activeBeforeBatch.contains(killer) && !id.equals(killer)) k.kills++;
      }
      game.lose(id, quit, getConfig().getInt("gulag.chances", 1));
      Player p = Bukkit.getPlayer(id);
      if (p != null && !p.isDead()) {
        if (e.state == WAITING) waiting(p);
        else if (e.state == OUT) restore(p);
      }
    }
    losses.clear();
    disconnects.clear();
  }

  void waiting(Player p) {
    p.closeInventory();
    p.setItemOnCursor(null);
    p.getInventory().clear();
    p.setWorldBorder(null);
    p.setGameMode(GameMode.SPECTATOR);
    teleport(p, lobby);
  }

  void resolveMatches() {
    for (var m : new ArrayList<>(matches.values())) {
      var a = game.entries.get(m.a);
      var b = game.entries.get(m.b);
      if (a.state != GULAG || b.state != GULAG) {
        if (a.state == GULAG && b.state == OUT) game.comeback(a.id);
        if (b.state == GULAG && a.state == OUT) game.comeback(b.id);
        endMatch(m);
      }
    }
  }

  void endMatch(Match m) {
    matches.remove(m.arena.name);
    releaseGulagChunks(m);
    for (UUID id : List.of(m.a, m.b)) {
      Player p = Bukkit.getPlayer(id);
      if (p != null && !p.isDead() && backups.exists(p)) {
        waiting(p);
        if (game.entries.get(id).state == OUT) restore(p);
      }
    }
    if (arenas.dirty(m.arena.name))
      arenas.restore(
          m.arena.name,
          m.arena.region,
          error -> {
            if (error != null) announce("Gulag " + m.arena.name + " locked after reset failure");
          });
  }

  void returnPlayers() {
    if (tick % 20 != 0) return;
    for (var e : game.entries.values())
      if (e.state == RETURNING) {
        Player p = Bukkit.getPlayer(e.id);
        if (p == null) {
          game.eliminate(e);
          continue;
        }
        if (p.isDead()) continue;
        Location l = safeReturn();
        if (l == null) {
          if (tick % 200 == 0)
            tell(p, "Return waiting: no safe location inside border; host must repair terrain");
          continue;
        }
        teleport(p, l);
        clean(p);
        kits.give(p, false);
        p.setGameMode(GameMode.SURVIVAL);
        p.setWorldBorder(border);
        e.state = ACTIVE;
        protection.put(e.id, tick + 20L * getConfig().getInt("gulag.protection-seconds", 5));
        tell(p, "Gulag victory! Returned with brief protection, ending when you attack.");
      }
  }

  void gulagTick() {
    for (var m : new ArrayList<>(matches.values())) {
      if (!m.ready) continue;
      m.left--;
      double min =
          Math.max(
              3, Math.min(m.arena.region.width(), getConfig().getDouble("gulag.final-width", 5)));
      m.border.setSize(
          Math.max(
              min,
              min
                  + (m.arena.region.width() - min)
                      * m.left
                      / Math.max(1d, getConfig().getInt("gulag.match-seconds", 120))));
      for (UUID id : List.of(m.a, m.b)) {
        Player p = Bukkit.getPlayer(id);
        if (p != null && !p.isDead()) borderDamage(p, m.border);
      }
      if (m.left <= 0) {
        game.eliminate(game.entries.get(m.a));
        game.eliminate(game.entries.get(m.b));
        announce("Gulag draw: both players eliminated");
        endMatch(m);
      }
    }
    if (!game.gulagOpen) return;
    Set<String> rejected = new HashSet<>();
    while (true) {
      List<Tournament.Entry> ready =
          game.queue().stream()
              .filter(
                  e -> {
                    Player p = Bukkit.getPlayer(e.id);
                    return p != null && !p.isDead();
                  })
              .toList();
      if (ready.size() < 2) break;
      Set<String> busyWorlds =
          matches.values().stream().map(m -> m.arena.region.world()).collect(Collectors.toSet());
      Set<String> unavailable = new HashSet<>(rejected);
      for (var site : gulags.values()) {
        if (arenas.locked.contains(site.name)) busyWorlds.add(site.region.world());
        if (arenas.dirty(site.name)) unavailable.add(site.name);
      }
      var choice =
          rotation.choose(
              gulags.values().stream()
                  .map(g -> new GulagRotation.Site(g.name, g.region.world()))
                  .toList(),
              busyWorlds,
              unavailable);
      if (choice.isEmpty()) break;
      var g = gulags.get(choice.get().id());
      var a = ready.get(0);
      var b = ready.get(1);
      WorldBorder gb = Bukkit.createWorldBorder();
      gb.setCenter(g.region.center());
      gb.setSize(g.region.width());
      Match m =
          new Match(g, a.id, b.id, gb, Math.max(5, getConfig().getInt("gulag.match-seconds", 120)));
      matches.put(g.name, m);
      rotation.claim(choice.get());
      // Persistence failure must not leave a half-created match; in-memory uniqueness still holds.
      try {
        saveRotation();
      } catch (IllegalStateException error) {
        getLogger().warning(error.getMessage());
      }
      a.state = GULAG;
      b.state = GULAG;
      prepareGulag(m);
    }
    for (var e : new ArrayList<>(game.queue()))
      if (game.seconds - e.queuedAt
          >= Math.max(1, getConfig().getInt("gulag.unmatched-seconds", 60))) {
        game.eliminate(e);
        Player p = Bukkit.getPlayer(e.id);
        if (p != null && !p.isDead()) restore(p);
        announce(e.name + " eliminated: gulag queue wait expired");
      }
  }

  void prepareGulag(Match m) {
    // Paper completes successful chunk futures on the main thread. Tickets keep the
    // first spawn chunk loaded while the second one finishes loading.
    Location a = m.arena.a, b = m.arena.b;
    CompletableFuture.allOf(
            a.getWorld()
                .getChunkAtAsync(a.getBlockX() >> 4, a.getBlockZ() >> 4, true)
                .thenAccept(chunk -> holdGulagChunk(m, chunk)),
            b.getWorld()
                .getChunkAtAsync(b.getBlockX() >> 4, b.getBlockZ() >> 4, true)
                .thenAccept(chunk -> holdGulagChunk(m, chunk)))
        .orTimeout(10, TimeUnit.SECONDS)
        .whenComplete(
            (chunks, error) -> {
              if (!isEnabled()) return;
              if (Bukkit.isPrimaryThread()) finishGulagPreparation(m, error);
              else Bukkit.getScheduler().runTask(this, () -> finishGulagPreparation(m, error));
            });
  }

  void holdGulagChunk(Match m, Chunk chunk) {
    if (matches.get(m.arena.name) == m
        && chunk.getWorld().addPluginChunkTicket(chunk.getX(), chunk.getZ(), this))
      m.heldChunks.add(chunk);
  }

  void releaseGulagChunks(Match m) {
    for (Chunk chunk : m.heldChunks)
      chunk.getWorld().removePluginChunkTicket(chunk.getX(), chunk.getZ(), this);
    m.heldChunks.clear();
  }

  void finishGulagPreparation(Match m, Throwable error) {
    try {
      finishLoadedGulag(m, error);
    } catch (Exception failure) {
      getLogger().severe("Gulag " + m.arena.name + " start failed: " + failure.getMessage());
      stop(true);
    } finally {
      releaseGulagChunks(m);
    }
  }

  void finishLoadedGulag(Match m, Throwable error) {
    if (matches.get(m.arena.name) != m || !running() || !game.gulagOpen) return;
    Player a = Bukkit.getPlayer(m.a), b = Bukkit.getPlayer(m.b);
    if (error != null
        || a == null
        || b == null
        || a.isDead()
        || b.isDead()
        || game.entries.get(m.a) == null
        || game.entries.get(m.b) == null
        || game.entries.get(m.a).state != GULAG
        || game.entries.get(m.b).state != GULAG
        || !safe(m.arena.a)
        || !safe(m.arena.b)) {
      matches.remove(m.arena.name);
      for (UUID id : List.of(m.a, m.b)) {
        var entry = game.entries.get(id);
        if (entry != null && entry.state == GULAG) entry.state = WAITING;
      }
      getLogger().warning("Gulag " + m.arena.name + " could not prepare; players remain queued");
      return;
    }
    startPreparedGulag(m, a, b);
  }

  void startPreparedGulag(Match m, Player a, Player b) {
    arenas.dirty(m.arena.name, true);
    for (Player p : List.of(a, b)) {
      teleport(p, p.getUniqueId().equals(m.a) ? m.arena.a : m.arena.b);
      clean(p);
      kits.give(p, getConfig().getBoolean("gulag.separate-kit"));
      p.setGameMode(GameMode.SURVIVAL);
      p.setWorldBorder(m.border);
      tell(
          p,
          "Gulag 1v1 in "
              + m.arena.region.world()
              + " / "
              + m.arena.name
              + ": "
              + m.left
              + "s. No rekitting. Draw eliminates both.");
    }
    m.ready = true;
  }

  void saveRotation() {
    var c = new YamlConfiguration();
    rotation.lastByWorld.forEach(c::set);
    try {
      c.save(new File(getDataFolder(), "gulag-rotation.yml"));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot persist gulag rotation", e);
    }
  }

  void setRekit(boolean enabled) {
    game.rekit = enabled;
    getConfig().set("rekit.enabled", enabled);
    saveConfig();
    announce(
        "Main-event rekit "
            + (enabled ? "enabled" : "disabled")
            + ". Gulag rekitting is always disabled.");
  }

  void closeGulag() {
    game.closeGulag();
    for (var m : new ArrayList<>(matches.values())) endMatch(m);
    for (var e : game.entries.values())
      if (e.state == OUT) {
        Player p = Bukkit.getPlayer(e.id);
        if (p != null && !p.isDead()) restore(p);
      }
    announce(
        "Gulag closed. Queued and active gulag players eliminated; earned pending returns remain"
            + " valid.");
  }

  void finish(boolean aborted) {
    for (var e : game.entries.values()) if (e.state != OUT) e.survived = game.seconds;
    try {
      results.save(
          game,
          aborted,
          getConfig().getBoolean("discord.enabled"),
          getConfig().getString("discord.webhook-url", ""),
          getConfig().getInt("discord.timeout-seconds", 10),
          m -> getLogger().warning(m));
    } catch (IOException e) {
      getLogger().severe("Could not save result report: " + e.getClass().getSimpleName());
    }
    lastResult =
        aborted
            ? "Event stopped"
            : game.entries.values().stream()
                .filter(e -> e.placement == 1 && e.state == ACTIVE)
                .map(e -> "Winner: " + e.name)
                .findFirst()
                .orElse("Event drawn: no survivors");
    announce(lastResult + ". Host: /event reset, then /event open.");
    game.phase = FINISHED;
    dropWarning = -1;
    shrinkRate = 0;
    for (Player p : Bukkit.getOnlinePlayers()) if (entry(p) != null) restore(p);
    bar.removeAll();
    for (Match m : matches.values()) releaseGulagChunks(m);
    matches.clear();
  }

  void stop(boolean aborted) {
    if (game.phase == CLOSED || game.phase == FINISHED) return;
    finish(aborted);
  }

  void reset(CommandSender sender) {
    if (terrainBusy || !arenas.locked.isEmpty())
      throw new IllegalStateException("Wait for current arena work to finish");
    requireMain();
    stop(true);
    ensureEmpty(main);
    for (var g : gulags.values()) ensureEmpty(g.region);
    List<String> names = new ArrayList<>();
    names.add("main");
    names.addAll(gulags.keySet());
    for (String n : names)
      if (!arenas.hasBackup(n, region(n)))
        throw new IllegalArgumentException("Backup missing for " + n);
    terrainBusy = true;
    resetNext(names, 0, sender);
  }

  void resetNext(List<String> names, int i, CommandSender sender) {
    if (i == names.size()) {
      terrainBusy = false;
      needsReset = false;
      game.phase = CLOSED;
      game.entries.clear();
      protection.clear();
      losses.clear();
      disconnects.clear();
      directKillers.clear();
      matches.clear();
      dropWarning = -1;
      timeline = loadTimeline();
      tell(sender, "All arenas restored. /event open is available.");
      return;
    }
    String n = names.get(i);
    arenas.restore(
        n,
        region(n),
        error -> {
          if (error != null) {
            terrainBusy = false;
            tell(sender, error);
            return;
          }
          resetNext(names, i + 1, sender);
        });
  }

  boolean safe(Location l) {
    if (l == null || l.getWorld() == null) return false;
    Block feet = l.getBlock(), head = feet.getRelative(0, 1, 0), floor = feet.getRelative(0, -1, 0);
    return feet.isPassable()
        && !feet.isLiquid()
        && head.isPassable()
        && !head.isLiquid()
        && floor.getType().isSolid()
        && !Set.of(
                Material.MAGMA_BLOCK,
                Material.CACTUS,
                Material.CAMPFIRE,
                Material.SOUL_CAMPFIRE,
                Material.FIRE,
                Material.SOUL_FIRE,
                Material.POWDER_SNOW,
                Material.SWEET_BERRY_BUSH)
            .contains(floor.getType())
        && !feet.getType().name().contains("FIRE")
        && feet.getType() != Material.POWDER_SNOW
        && feet.getType() != Material.SWEET_BERRY_BUSH;
  }

  boolean insideSquare(Location l, Location c, double width) {
    return (c.getWorld() == null || l.getWorld().equals(c.getWorld()))
        && Math.abs(l.getX() - c.getX()) <= width / 2 - .5
        && Math.abs(l.getZ() - c.getZ()) <= width / 2 - .5;
  }

  Location safeReturn() {
    for (Location l : spawns)
      if (safe(l)
          && insideSquare(l, border.getCenter(), border.getSize())
          && (activeFloor == Integer.MIN_VALUE || l.getY() <= activeFloor + 2)) return l;
    Location c = border.getCenter();
    int radius = (int) Math.min(32, border.getSize() / 2 - 1);
    int inspected = 0;
    for (int r = 0; r <= radius; r++)
      for (int dx = -r; dx <= r; dx++)
        for (int dz = -r; dz <= r; dz++) {
          if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
          int x = c.getBlockX() + dx, z = c.getBlockZ() + dz;
          for (int y = activeFloor == Integer.MIN_VALUE ? main.y2() - 1 : activeFloor + 1;
              y >= main.y1() + 1;
              y--) {
            if (++inspected > 32768) return null;
            Location l = new Location(main.w(), x + .5, y, z + .5);
            if (main.contains(l) && insideSquare(l, c, border.getSize()) && safe(l)) return l;
          }
        }
    return null;
  }

  Location floorLanding(int y) {
    if (main == null) return null;
    Location c = !running() || border == null ? main.center() : border.getCenter();
    double width = !running() || border == null ? main.width() : border.getSize();
    int radius = (int) Math.min(32, width / 2 - 1);
    for (int r = 0; r <= radius; r++)
      for (int x = -r; x <= r; x++)
        for (int z = -r; z <= r; z++) {
          if (Math.abs(x) != r && Math.abs(z) != r) continue;
          Location l =
              new Location(main.w(), c.getBlockX() + x + .5, y + 1, c.getBlockZ() + z + .5);
          Material floor = l.clone().add(0, -1, 0).getBlock().getType();
          if (main.contains(l)
              && insideSquare(l, c, width)
              && floor.isSolid()
              && floor != Material.MAGMA_BLOCK
              && floor != Material.CACTUS
              && floor != Material.CAMPFIRE
              && floor != Material.SOUL_CAMPFIRE) return l;
        }
    return null;
  }

  void borderCommand(Player p, String[] a) {
    if (!running()) throw new IllegalStateException("Border controls require fighting");
    if (a.length < 2)
      throw new IllegalArgumentException("border <full-width> <seconds>|pause|resume|center");
    switch (a[1]) {
      case "pause" -> borderPaused = true;
      case "resume" -> borderPaused = false;
      case "center" -> {
        Location c = p.getLocation();
        double half = border.getSize() / 2;
        if (!main.world().equals(c.getWorld().getName())
            || c.getX() - half < main.x1()
            || c.getX() + half > main.x2() + 1
            || c.getZ() - half < main.z1()
            || c.getZ() + half > main.z2() + 1)
          throw new IllegalArgumentException("Entire border must fit inside selected arena");
        border.setCenter(c);
      }
      default -> shrink(integer(a[1], 3, (int) border.getSize()), integer(a[2], 1, 86400));
    }
    tell(
        p,
        "Border width "
            + Math.round(border.getSize())
            + " blocks"
            + (borderPaused ? " (paused)" : ""));
  }

  void shrink(double width, int seconds) {
    targetWidth = Math.max(3, Math.min(border.getSize(), width));
    shrinkRate = (border.getSize() - targetWidth) / Math.max(1, seconds);
    borderPaused = false;
    announce(
        "Square border shrinking to " + targetWidth + " blocks wide over " + seconds + " seconds");
  }

  void advanceBorder() {
    if (!borderPaused && shrinkRate > 0)
      border.setSize(Math.max(targetWidth, border.getSize() - shrinkRate));
    for (var e : game.entries.values())
      if (e.state == ACTIVE) {
        Player p = Bukkit.getPlayer(e.id);
        if (p != null && !p.isDead()) borderDamage(p, border);
      }
  }

  void borderDamage(Player p, WorldBorder b) {
    if (!insideSquare(p.getLocation(), b.getCenter(), b.getSize())) p.damage(2);
  }

  void stage(Timeline.Stage s) {
    announce("Stage: " + s.name() + " (" + s.action() + ")");
    switch (s.action()) {
      case "shrink" ->
          shrink(
              getConfig().getDouble("border.final-width", 10),
              getConfig().getInt("border.shrink-seconds", 300));
      case "rekit-off" -> {
        game.rekit = false;
        announce("Rekit disabled. Gulag revival is controlled separately.");
      }
      case "gulag-close" -> closeGulag();
      case "deepslate-drop" -> scheduleDrop(false);
      case "bedrock-finale" -> scheduleDrop(true);
      default -> {}
    }
  }

  void scheduleDrop(boolean bedrock) {
    if (!running() || terrainBusy || dropWarning >= 0)
      throw new IllegalStateException("Drop requires fighting with no pending terrain work");
    int y = getConfig().getInt(bedrock ? "arena.bedrock-y" : "arena.deepslate-y");
    if (y < main.y1()
        || y > main.y2() - 2
        || y >= activeFloor && activeFloor != Integer.MIN_VALUE
        || floorLanding(y) == null)
      throw new IllegalStateException(
          "No safe preserved floor inside border, or stage would move upward");
    if (bedrock) {
      closeGulag();
      game.rekit = false;
    }
    dropFloor = y;
    dropWarning = Math.max(3, getConfig().getInt("arena.drop-warning-seconds", 10));
    announce(
        "Terrain drop in "
            + dropWarning
            + "s: clearing only above Y="
            + y
            + ", preserving floor. Combat pauses during clearing.");
  }

  void performDrop() {
    Location landing = floorLanding(dropFloor);
    if (landing == null) {
      dropWarning = -1;
      announce("Drop cancelled: safe floor no longer available");
      return;
    }
    terrainBusy = true;
    dropWarning = -1;
    for (var e : game.entries.values())
      if (e.state == ACTIVE) {
        Player p = Bukkit.getPlayer(e.id);
        if (p != null && !p.isDead()) {
          p.closeInventory();
          p.setGameMode(GameMode.SPECTATOR);
          p.setWorldBorder(null);
          teleport(p, lobby);
        }
      }
    arenas.drop(
        "main",
        main,
        dropFloor,
        error -> {
          terrainBusy = false;
          if (error != null) {
            announce(error);
            stop(true);
            return;
          }
          activeFloor = dropFloor;
          if (!running()) return;
          for (var e : game.entries.values())
            if (e.state == ACTIVE) {
              Player p = Bukkit.getPlayer(e.id);
              if (p != null && !p.isDead()) {
                Location l = safeReturn();
                if (l == null) {
                  stop(true);
                  return;
                }
                teleport(p, l);
                p.setGameMode(GameMode.SURVIVAL);
                p.setWorldBorder(border);
                p.setFallDistance(0);
                protection.put(e.id, tick + 100);
              }
            }
          announce("Terrain drop complete. Fighting resumes.");
        });
  }

  String nextText() {
    var n = timeline.next();
    return n == null
        ? "none"
        : n.name()
            + " ("
            + n.trigger()
            + " "
            + n.value()
            + ")"
            + (timeline.delayedUntil > game.seconds
                ? " delayed " + (timeline.delayedUntil - game.seconds) + "s"
                : "");
  }

  void status(CommandSender s) {
    tell(
        s,
        game.phase
            + " | living "
            + game.living()
            + " | potential "
            + game.remaining()
            + " | queue "
            + game.queue().size()
            + " | matches "
            + matches.size()
            + " | width "
            + (border == null ? "—" : Math.round(border.getSize()))
            + " | rekit "
            + game.rekit
            + " | current "
            + timeline.current
            + " | next "
            + nextText()
            + " | operations "
            + arenas.progress);
  }

  void hud() {
    String state = game.phase == COUNTDOWN ? "Starts in " + countdown + "s" : game.phase.toString();
    bar.setTitle(
        state
            + " | Living "
            + game.living()
            + " | Gulag "
            + game.queue().size()
            + " queued | Rekit "
            + (game.rekit ? "ON" : "OFF")
            + " | Next "
            + nextText());
    bar.setProgress(1);
    for (var e : game.entries.values()) {
      Player p = Bukkit.getPlayer(e.id);
      if (p == null || !backups.exists(p)) continue;
      Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
      Objective o =
          board.registerNewObjective("cpvp", Criteria.DUMMY, ChatColor.LIGHT_PURPLE + "CrystalPvP");
      o.setDisplaySlot(DisplaySlot.SIDEBAR);
      List<String> lines =
          new ArrayList<>(
              List.of(
                  "State: " + e.state,
                  "Living: " + game.living(),
                  "Can win: " + game.remaining(),
                  "Time: " + game.seconds + "s",
                  "Width: " + (border == null ? "—" : Math.round(border.getSize())) + " blocks",
                  "Rekit: " + (game.rekit ? "ON" : "OFF"),
                  "Stage: " + timeline.current,
                  "Next: " + nextText()));
      if (e.state == WAITING) lines.add("Queue position: " + (game.queue().indexOf(e) + 1));
      for (var m : matches.values())
        if (m.a.equals(e.id) || m.b.equals(e.id)) lines.add("Gulag timer: " + m.left + "s");
      if (terrainBusy) lines.add("Terrain: " + arenas.progress);
      int i = lines.size();
      for (String l : lines) o.getScore(l).setScore(i--);
      p.setScoreboard(board);
    }
  }

  static final class Menu implements InventoryHolder {
    Inventory inv;
    final Map<Integer, String> actions = new HashMap<>();

    public Inventory getInventory() {
      return inv;
    }
  }

  void gui(Player p) {
    Menu m = new Menu();
    m.inv = Bukkit.createInventory(m, 27, "CrystalPvP Host");
    button(
        m,
        0,
        Material.PAPER,
        "Status: " + game.phase,
        "Living " + game.living() + "; potential " + game.remaining(),
        "status");
    button(
        m,
        1,
        Material.PLAYER_HEAD,
        "Living players",
        game.entries.values().stream()
            .filter(e -> e.state == ACTIVE)
            .map(e -> e.name)
            .collect(Collectors.joining(", ")),
        "status");
    button(
        m,
        2,
        Material.IRON_SWORD,
        "Gulag queue",
        game.queue().stream().map(e -> e.name).collect(Collectors.joining(", "))
            + "; matches "
            + matches.size(),
        "status");
    button(
        m,
        3,
        Material.GLASS,
        "Border " + (border == null ? "—" : Math.round(border.getSize())) + " blocks",
        borderPaused ? "Click to resume shrink" : "Click to pause shrink",
        borderPaused ? "border resume" : "border pause");
    button(
        m,
        4,
        Material.CHEST,
        "Rekit " + game.rekit,
        "Toggle replenishing living players; does not revive",
        "rekit-toggle");
    button(
        m,
        9,
        Material.EMERALD,
        "Open registration",
        "Requires clean backup, kit, spawns and gulag",
        "open");
    button(
        m,
        10,
        Material.CLOCK,
        "Start countdown",
        "Closes registration; starts fighting after countdown",
        "start");
    button(
        m,
        11,
        Material.REDSTONE_BLOCK,
        "Stop event",
        "Abort, save local report, restore players; reset required",
        "stop");
    button(
        m,
        12,
        Material.GRASS_BLOCK,
        "Reset all arenas",
        "Restores saved blocks; locks arenas until complete",
        "reset");
    button(m, 13, Material.COMPASS, "Preflight", "Check setup without starting", "preflight");
    button(
        m,
        18,
        Material.REPEATER,
        "Stage: " + timeline.current,
        "Next: " + nextText() + "; click to execute next stage",
        "stage next");
    button(m, 19, Material.ARROW, "Skip next stage", "Does not execute its effect", "stage skip");
    button(m, 20, Material.CLOCK, "Delay stage 30s", "Defers next stage trigger", "stage delay 30");
    button(
        m,
        21,
        Material.IRON_DOOR,
        "Close gulag",
        "Eliminates queue and active duels; earned returns stay",
        "gulag close");
    button(
        m,
        22,
        Material.DEEPSLATE,
        "Deepslate drop",
        "Warning then clear ABOVE configured floor; safe relocation",
        "drop deepslate");
    button(
        m,
        23,
        Material.BEDROCK,
        "Bedrock finale",
        "Closes gulag, disables rekit, warns then clears above floor",
        "drop bedrock");
    button(m, 26, Material.BOOK, "Results / progress", arenas.progress.toString(), "results");
    p.openInventory(m.inv);
  }

  void button(Menu m, int slot, Material material, String title, String lore, String command) {
    ItemStack item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.setDisplayName(ChatColor.LIGHT_PURPLE + title);
    meta.setLore(List.of(ChatColor.GRAY + lore));
    item.setItemMeta(meta);
    m.inv.setItem(slot, item);
    m.actions.put(slot, command);
  }

  @Override
  public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
    if (a.length == 1)
      return List.of(
              "join",
              "leave",
              "status",
              "rekit",
              "layout",
              "help",
              "gui",
              "worlds",
              "preflight",
              "open",
              "start",
              "stop",
              "reset",
              "pos1",
              "pos2",
              "arena",
              "lobby",
              "spawn",
              "kit",
              "backup",
              "gulag",
              "border",
              "stage",
              "timeline",
              "drop",
              "results",
              "reload")
          .stream()
          .filter(x -> x.startsWith(a[0]))
          .toList();
    if (a.length == 2 && s.hasPermission("crystal.host")) {
      List<String> options =
          switch (a[0]) {
            case "worlds" -> List.of("create", "status", "lobby");
            case "rekit" -> List.of("on", "off");
            default -> List.of();
          };
      return options.stream().filter(x -> x.startsWith(a[1])).toList();
    }
    return List.of();
  }
}
