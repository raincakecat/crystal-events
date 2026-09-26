package dev.crystal.smoke;

import dev.crystal.events.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Opt-in integration runner. It only operates its own world on an isolated localhost server. */
public final class PaperSmoke extends JavaPlugin {
  EventsPlugin events;
  ArenaStore store;
  Region region;
  World world;
  final String arenaName = "paper-smoke-" + System.currentTimeMillis();
  final List<String> report = new ArrayList<>();

  @Override
  public void onEnable() {
    if (!Boolean.getBoolean("cpvp.integration")) {
      getLogger()
          .warning(
              "Smoke runner inactive: -Dcpvp.integration=true is required on an isolated test"
                  + " server.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    events = (EventsPlugin) getServer().getPluginManager().getPlugin("CrystalPvPEvents");
    events.getConfig().set("worlds.auto-create", false);
    events.getConfig().set("worlds.gulag-worlds", 1);
    events.getConfig().set("worlds.locations-per-world", 2);
    Bukkit.getScheduler()
        .runTaskLater(
            this,
            () -> {
              try {
                begin();
              } catch (Throwable error) {
                fail(error);
              }
            },
            40);
  }

  void begin() {
    report.add("Server: " + Bukkit.getVersion() + "; Java " + System.getProperty("java.version"));
    world =
        Bukkit.createWorld(
            new WorldCreator("cpvp_smoke_" + System.currentTimeMillis())
                .type(WorldType.FLAT)
                .generatorSettings(
                    "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\"}")
                .generateStructures(false));
    // Pregenerate the small footprint. Region contains 32*16*32=16,384 blocks.
    for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) world.getChunkAt(x, z).load();
    region = new Region(world.getName(), 0, 80, 0, 31, 95, 31);
    for (int y = 80; y <= 95; y++)
      for (int z = 0; z < 32; z++)
        for (int x = 0; x < 32; x++)
          world.getBlockAt(x, y, z).setType(y == 80 ? Material.OBSIDIAN : Material.AIR, false);
    world.getBlockAt(1, 81, 1).setType(Material.CHEST, false);
    Chest chest = (Chest) world.getBlockAt(1, 81, 1).getState();
    chest.getSnapshotInventory().setItem(0, new ItemStack(Material.DIAMOND, 7));
    chest.update(true, false);
    require(
        ((Chest) world.getBlockAt(1, 81, 1).getState()).getBlockInventory().getItem(0) != null,
        "Chest fixture contains items before backup");
    world.getBlockAt(2, 81, 1).setType(Material.OAK_SIGN, false);
    Sign sign = (Sign) world.getBlockAt(2, 81, 1).getState();
    sign.getSide(Side.FRONT).setLine(0, "Crystal snapshot");
    sign.update(true, false);
    store = new ArenaStore(events);
    store.snapshot(
        arenaName,
        region,
        error -> {
          try {
            require(error == null, "Snapshot: " + error);
            report.add("Backup " + store.lastMetrics.get(arenaName));
            mutate();
          } catch (Throwable failure) {
            fail(failure);
          }
        });
  }

  void mutate() {
    world.getBlockAt(0, 80, 0).setType(Material.AIR, false); // explosion-like missing floor
    world.getBlockAt(1, 81, 1).setType(Material.AIR, false);
    world.getBlockAt(2, 81, 1).setType(Material.AIR, false);
    world.getBlockAt(4, 81, 4).setType(Material.WATER, false);
    world.getBlockAt(5, 81, 5).setType(Material.LAVA, false);
    Item item = world.dropItem(new Location(world, 6.5, 82, 6.5), new ItemStack(Material.OBSIDIAN));
    store.restore(
        arenaName,
        region,
        error -> {
          try {
            require(error == null, "Reset: " + error);
            report.add("Reset " + store.lastMetrics.get(arenaName));
            require(
                world.getBlockAt(0, 80, 0).getType() == Material.OBSIDIAN,
                "Missing block restored");
            require(world.getBlockAt(4, 81, 4).getType() == Material.AIR, "Water restored to air");
            require(world.getBlockAt(5, 81, 5).getType() == Material.AIR, "Lava restored to air");
            Chest chest = (Chest) world.getBlockAt(1, 81, 1).getState();
            require(
                chest.getBlockInventory().getItem(0).getAmount() == 7, "Chest inventory preserved");
            Sign sign = (Sign) world.getBlockAt(2, 81, 1).getState();
            require(
                sign.getSide(Side.FRONT).getLine(0).equals("Crystal snapshot"),
                "Sign text preserved");
            require(!item.isValid(), "Event entity cleared");
            require(!store.dirty(arenaName), "Reset unlocked arena");
            report.add(
                "PASS: missing blocks, air, fluids, chest contents, sign text, entity cleanup,"
                    + " persistent reset lock");
            drop();
          } catch (Throwable failure) {
            fail(failure);
          }
        });
  }

  void drop() {
    world.getBlockAt(3, 85, 3).setType(Material.STONE, false);
    world.getBlockAt(32, 85, 3).setType(Material.GOLD_BLOCK, false);
    store.drop(
        arenaName,
        region,
        80,
        error -> {
          try {
            require(error == null, "Drop: " + error);
            require(world.getBlockAt(3, 85, 3).getType() == Material.AIR, "Upper block cleared");
            require(world.getBlockAt(3, 80, 3).getType() == Material.OBSIDIAN, "Floor preserved");
            require(
                world.getBlockAt(32, 85, 3).getType() == Material.GOLD_BLOCK,
                "Outside block preserved");
            report.add("Drop " + store.lastMetrics.get(arenaName));
            report.add(
                "PASS: live terrain drop preserves floor and bounds; remains dirty for reset");
            require(store.dirty(arenaName), "Drop remains dirty");
            managedWorlds();
          } catch (Throwable failure) {
            fail(failure);
          }
        });
  }

  void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  void managedWorlds() {
    events.managedWorlds.create(Bukkit.getConsoleSender());
    long deadline = System.nanoTime() + 300_000_000_000L;
    new org.bukkit.scheduler.BukkitRunnable() {
      public void run() {
        try {
          require(System.nanoTime() < deadline, "Managed world preparation timed out");
          if (!events.managedWorlds.ready()) {
            require(events.managedWorlds.busy(), events.managedWorlds.status());
            return;
          }
          var manifest =
              org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                  new File(events.getDataFolder(), "managed-worlds.yml"));
          var names = manifest.getStringList("worlds");
          require(names.size() == 2, "One main and one test gulag world");
          World main = Bukkit.getWorld(names.getFirst()), gulag = Bukkit.getWorld(names.get(1));
          require(main != null && gulag != null && main != gulag, "Separate worlds loaded");
          for (World generated : List.of(main, gulag)) {
            require(
                generated.getBlockAt(8, 80, 8).getType() == Material.GRASS_BLOCK,
                "Generated surface");
            require(
                generated.getBlockAt(8, 70, 8).getType() == Material.DEEPSLATE,
                "Generated deepslate");
            require(
                generated.getBlockAt(8, 60, 8).getType() == Material.BEDROCK, "Generated bedrock");
            require(generated.getBlockAt(8, 81, 8).isEmpty(), "Spawn headroom");
          }
          var setup =
              org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                  new File(events.getDataFolder(), "setup.yml"));
          require(setup.getList("spawns").size() == 50, "50 saved main spawns");
          require(
              setup.getConfigurationSection("gulags").getKeys(false).size() == 2,
              "Two saved test sites");
          var reload = new ManagedWorlds(events);
          reload.loadOwned();
          require(
              reload.ready() && Bukkit.getWorld(names.getFirst()) == main,
              "Owned worlds reload without replacement");
          report.add(
              "PASS: automatic main/gulag world creation, grass/deepslate/bedrock terrain, 50 saved"
                  + " spawns, two distinct test sites, backups, owned-world reload");
          cancel();
          checkAsyncGulagChunks(gulag, setup);
        } catch (Throwable error) {
          cancel();
          fail(error);
        }
      }
    }.runTaskTimer(this, 20, 20);
  }

  void checkAsyncGulagChunks(World gulag, org.bukkit.configuration.file.YamlConfiguration setup) {
    var sites = setup.getConfigurationSection("gulags");
    var site = sites.getConfigurationSection(sites.getKeys(false).iterator().next());
    Location a = site.getLocation("a"), b = site.getLocation("b");
    require(
        a != null && b != null && a.getWorld() == gulag && b.getWorld() == gulag,
        "Saved gulag spawn locations");
    Set<Chunk> held = new HashSet<>();
    java.util.concurrent.atomic.AtomicBoolean finished =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.CompletableFuture.allOf(
            gulag
                .getChunkAtAsync(a.getBlockX() >> 4, a.getBlockZ() >> 4, true)
                .thenAccept(
                    chunk -> {
                      if (!finished.get()
                          && gulag.addPluginChunkTicket(chunk.getX(), chunk.getZ(), this))
                        held.add(chunk);
                    }),
            gulag
                .getChunkAtAsync(b.getBlockX() >> 4, b.getBlockZ() >> 4, true)
                .thenAccept(
                    chunk -> {
                      if (!finished.get()
                          && gulag.addPluginChunkTicket(chunk.getX(), chunk.getZ(), this))
                        held.add(chunk);
                    }))
        .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .whenComplete(
            (chunks, error) -> {
              Runnable check =
                  () -> {
                    Throwable failure = null;
                    try {
                      require(error == null, "Async gulag chunk load: " + error);
                      require(
                          gulag.isChunkLoaded(a.getBlockX() >> 4, a.getBlockZ() >> 4)
                              && gulag.isChunkLoaded(b.getBlockX() >> 4, b.getBlockZ() >> 4),
                          "Gulag spawn chunks loaded");
                      report.add("PASS: both gulag spawn chunks loaded asynchronously");
                    } catch (Throwable errorDuringCheck) {
                      failure = errorDuringCheck;
                    } finally {
                      finished.set(true);
                      for (Chunk chunk : held)
                        gulag.removePluginChunkTicket(chunk.getX(), chunk.getZ(), this);
                    }
                    if (failure == null) finish("PASS");
                    else fail(failure);
                  };
              if (Bukkit.isPrimaryThread()) check.run();
              else Bukkit.getScheduler().runTask(this, check);
            });
  }

  void fail(Throwable error) {
    report.add("FAIL: " + error);
    error.printStackTrace();
    finish("FAIL");
  }

  void finish(String status) {
    getDataFolder().mkdirs();
    report.add("Overall: " + status);
    try {
      Files.write(new File(getDataFolder(), "smoke-report.txt").toPath(), report);
    } catch (IOException error) {
      getLogger().severe("Cannot write smoke report");
    }
    report.forEach(getLogger()::info);
    Bukkit.shutdown();
  }
}
