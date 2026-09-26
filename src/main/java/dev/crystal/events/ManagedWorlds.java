package dev.crystal.events;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

/** Creates only uniquely named owned worlds; never replaces or deletes existing user worlds. */
public final class ManagedWorlds {
  private final EventsPlugin plugin;
  private final File file;
  private YamlConfiguration manifest;
  private boolean busy;
  private String status = "Not created";

  public ManagedWorlds(EventsPlugin plugin) {
    this.plugin = plugin;
    file = new File(plugin.getDataFolder(), "managed-worlds.yml");
    manifest = YamlConfiguration.loadConfiguration(file);
  }

  public boolean busy() {
    return busy;
  }

  public boolean ready() {
    return "ready".equals(manifest.getString("state"));
  }

  public boolean incomplete() {
    return file.exists() && !ready();
  }

  public String status() {
    return status;
  }

  private void save() throws IOException {
    File temp = new File(file.getParentFile(), "managed-worlds.tmp");
    manifest.save(temp);
    Files.move(
        temp.toPath(),
        file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
  }

  public static List<Region> sites(String world, int count) {
    if (count < 2 || count > 32)
      throw new IllegalArgumentException("locations-per-world must be 2..32");
    List<Region> sites = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int x = (i % 8) * 128, z = (i / 8) * 128;
      sites.add(new Region(world, x, 80, z, x + 31, 91, z + 31));
    }
    return sites;
  }

  private World load(String name) {
    if (!name.matches("cpvp_[a-f0-9]{12}_(main|gulag_[0-3])"))
      throw new IllegalStateException("Invalid managed world name");
    World world = Bukkit.getWorld(name);
    if (world == null)
      world =
          Bukkit.createWorld(
              new WorldCreator(name)
                  .generator(new EventTerrainGenerator())
                  .generateStructures(false));
    if (world == null) throw new IllegalStateException("Could not load managed world");
    world.setSpawnLocation(-25, 81, -25);
    world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
    world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
    world.setTime(6000);
    world.setStorm(false);
    world.setThundering(false);
    return world;
  }

  /** Must precede setup.yml Location deserialization so saved world UUIDs can resolve. */
  public void loadOwned() {
    for (String name : manifest.getStringList("worlds")) {
      if (!new File(Bukkit.getWorldContainer(), name).isDirectory()) {
        if (ready())
          throw new IllegalStateException(
              "Managed world missing: " + name + "; restore it from backup");
        continue;
      }
      load(name);
    }
    status = manifest.getString("state", "Not created");
  }

  public void autoStart() {
    if (plugin.getConfig().getBoolean("worlds.auto-create", true)
        && !ready()
        && (plugin.main == null || file.exists())) create(Bukkit.getConsoleSender());
  }

  public void create(CommandSender sender) {
    plugin.idle();
    if (busy) throw new IllegalStateException("World setup already running");
    if (ready()) {
      plugin.tell(sender, "Managed worlds already ready. /event worlds status");
      return;
    }
    if (plugin.main != null && !file.exists())
      throw new IllegalStateException(
          "Existing manual arena preserved; automatic setup only runs on an unconfigured"
              + " installation");
    try {
      if (!file.exists()) {
        int worlds = plugin.getConfig().getInt("worlds.gulag-worlds", 2);
        int locations = plugin.getConfig().getInt("worlds.locations-per-world", 32);
        if (worlds < 1 || worlds > 4)
          throw new IllegalArgumentException("gulag-worlds must be 1..4");
        sites("validation", locations);
        String prefix = "cpvp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<String> names = new ArrayList<>();
        names.add(prefix + "_main");
        for (int i = 0; i < worlds; i++) names.add(prefix + "_gulag_" + i);
        for (String n : names)
          if (Bukkit.getWorld(n) != null || new File(Bukkit.getWorldContainer(), n).exists())
            throw new IllegalStateException("Generated world name collision; retry");
        manifest.set("worlds", names);
        manifest.set("locations", locations);
        manifest.set("state", "preparing");
        save();
      }
      busy = true;
      plugin.terrainBusy = true;
      status = "Creating worlds";
      plugin.tell(
          sender,
          "Creating event/gulag worlds, spawn locations and backups. /event worlds status shows"
              + " progress.");
      createNext(manifest.getStringList("worlds"), 0, sender);
    } catch (Exception e) {
      failed(sender, e);
    }
  }

  private void createNext(List<String> names, int index, CommandSender sender) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              try {
                if (index < names.size()) {
                  load(names.get(index));
                  status = "Loaded " + (index + 1) + "/" + names.size() + " worlds";
                  createNext(names, index + 1, sender);
                  return;
                }
                World mainWorld = Bukkit.getWorld(names.getFirst());
                plugin.main = new Region(mainWorld.getName(), 0, 60, 0, 127, 95, 127);
                plugin.lobby = new Location(mainWorld, -24.5, 81, -24.5);
                plugin.spawns.clear();
                for (int i = 0; i < 50; i++)
                  plugin.spawns.add(
                      new Location(mainWorld, 8.5 + (i % 8) * 16, 81, 8.5 + (i / 8) * 16));
                plugin.gulags.clear();
                for (int w = 1; w < names.size(); w++) {
                  int i = 0;
                  for (Region r : sites(names.get(w), manifest.getInt("locations"))) {
                    String name = "g" + w + "_site" + (i++);
                    plugin.gulags.put(
                        name,
                        new EventsPlugin.GulagArena(
                            name,
                            r,
                            new Location(r.w(), r.x1() + 6.5, 81, r.z1() + 16.5, -90, 0),
                            new Location(r.w(), r.x1() + 25.5, 81, r.z1() + 16.5, 90, 0)));
                  }
                }
                plugin.getConfig().set("arena.deepslate-y", 70);
                plugin.getConfig().set("arena.bedrock-y", 60);
                plugin.saveConfig();
                plugin.saveSetup();
                List<String> arenas = new ArrayList<>();
                arenas.add("main");
                arenas.addAll(plugin.gulags.keySet());
                backupNext(arenas, 0, sender);
              } catch (Exception e) {
                failed(sender, e);
              }
            });
  }

  private void backupNext(List<String> names, int index, CommandSender sender) throws IOException {
    if (index == names.size()) {
      manifest.set("state", "ready");
      save();
      busy = false;
      plugin.terrainBusy = false;
      status =
          "Ready: "
              + manifest.getStringList("worlds").size()
              + " worlds, "
              + plugin.gulags.size()
              + " gulag sites";
      plugin.tell(
          sender,
          status
              + ". Save your event kit with /event kit save, then /event preflight and /event"
              + " open.");
      return;
    }
    String name = names.get(index);
    status = "Backup " + (index + 1) + "/" + names.size() + ": " + name;
    if (plugin.arenas.hasBackup(name, plugin.region(name))) {
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                try {
                  backupNext(names, index + 1, sender);
                } catch (Exception e) {
                  failed(sender, e);
                }
              });
      return;
    }
    plugin.arenas.snapshot(
        name,
        plugin.region(name),
        error -> {
          if (error != null) {
            failed(sender, new IllegalStateException(error));
            return;
          }
          try {
            backupNext(names, index + 1, sender);
          } catch (Exception e) {
            failed(sender, e);
          }
        });
  }

  private void failed(CommandSender sender, Exception error) {
    manifest.set("state", "preparing");
    busy = false;
    plugin.terrainBusy = false;
    status = "Setup incomplete: " + error.getMessage();
    plugin.tell(
        sender,
        status
            + ". Fix cause, then /event worlds create to resume. Existing worlds are preserved.");
    plugin.getLogger().warning(status);
  }
}
