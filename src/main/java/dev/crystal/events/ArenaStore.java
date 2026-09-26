package dev.crystal.events;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.structure.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

/** World access stays on the main thread. Failed/interrupted work remains locked on disk. */
public final class ArenaStore {
  private final EventsPlugin plugin;
  private final File root;
  private final Set<String> dirtyNames = new HashSet<>();
  private long budgetTick = -1;
  private int budgetUsed;
  public final Map<String, String> lastMetrics = new HashMap<>();
  public final Set<String> locked = new HashSet<>();
  public final Map<String, String> progress = new HashMap<>();

  public ArenaStore(EventsPlugin p) {
    plugin = p;
    root = new File(p.getDataFolder(), "arenas");
    root.mkdirs();
    File[] dirs = root.listFiles(File::isDirectory);
    if (dirs != null)
      for (File d : dirs) if (new File(d, "dirty").exists()) dirtyNames.add(d.getName());
  }

  private File dir(String name) {
    if (!name.matches("[a-zA-Z0-9_-]{1,32}"))
      throw new IllegalArgumentException("Arena name: 1..32 letters/digits/_/-");
    return new File(root, name);
  }

  public boolean dirty(String name) {
    return dirtyNames.contains(name);
  }

  public void dirty(String name, boolean value) {
    try {
      dir(name).mkdirs();
      if (value) Files.writeString(new File(dir(name), "dirty").toPath(), "Reset required");
      else Files.deleteIfExists(new File(dir(name), "dirty").toPath());
      if (value) dirtyNames.add(name);
      else dirtyNames.remove(name);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot persist arena lock", e);
    }
  }

  public boolean hasBackup(String name, Region r) {
    try {
      var m = manifest(name);
      return r.equals(Region.load(m.getConfigurationSection("region")))
          && m.getInt("count") == r.cubes(m.getInt("cube-size")).size()
          && allFiles(name, m);
    } catch (Exception e) {
      return false;
    }
  }

  private boolean allFiles(String n, YamlConfiguration m) {
    for (int i = 0; i < m.getInt("count"); i++)
      if (!new File(dir(n), m.getString("generation") + "/" + i + ".nbt").isFile()) return false;
    return true;
  }

  private YamlConfiguration manifest(String name) throws Exception {
    File f = new File(dir(name), "manifest.yml");
    if (!f.isFile()) throw new IOException("No backup");
    YamlConfiguration c = new YamlConfiguration();
    c.load(f);
    return c;
  }

  public void snapshot(String name, Region r, Consumer<String> done) {
    if (locked.contains(name) || dirty(name))
      throw new IllegalStateException("Arena locked/dirty; restore before replacing backup");
    validate(r);
    String gen = "snapshot-" + UUID.randomUUID();
    File folder = new File(dir(name), gen);
    folder.mkdirs();
    int size = plugin.getConfig().getInt("arena.cube-size", 8);
    List<Region.Cube> cubes = r.cubes(size);
    run(
        name,
        "snapshot",
        cubes.size(),
        i -> {
          var c = cubes.get(i);
          Structure s = Bukkit.getStructureManager().createStructure();
          s.fill(
              new Location(r.w(), c.x(), c.y(), c.z()),
              new BlockVector(c.sx(), c.sy(), c.sz()),
              false);
          Bukkit.getStructureManager().saveStructure(new File(folder, i + ".nbt"), s);
        },
        () -> {
          YamlConfiguration m = new YamlConfiguration();
          r.save(m.createSection("region"));
          m.set("generation", gen);
          m.set("cube-size", size);
          m.set("count", cubes.size());
          File temp = new File(dir(name), "manifest.tmp");
          m.save(temp);
          Files.move(
              temp.toPath(),
              new File(dir(name), "manifest.yml").toPath(),
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE);
        },
        done);
  }

  public void restore(String name, Region r, Consumer<String> done) {
    if (locked.contains(name)) throw new IllegalStateException("Arena operation already running");
    if (!hasBackup(name, r))
      throw new IllegalStateException("Missing/mismatched backup for " + name);
    try {
      var m = manifest(name);
      var cubes = r.cubes(m.getInt("cube-size"));
      dirty(name, true);
      clearEntities(r);
      run(
          name,
          "reset",
          cubes.size(),
          i -> {
            var c = cubes.get(i);
            Structure s =
                Bukkit.getStructureManager()
                    .loadStructure(
                        new File(dir(name), m.getString("generation") + "/" + i + ".nbt"));
            s.place(
                new Location(r.w(), c.x(), c.y(), c.z()),
                false,
                StructureRotation.NONE,
                Mirror.NONE,
                0,
                1f,
                new Random(0));
          },
          () -> {
            clearEntities(r);
            dirty(name, false);
          },
          done);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot start reset", e);
    }
  }

  public void drop(String name, Region r, int floor, Consumer<String> done) {
    if (locked.contains(name)) throw new IllegalStateException("Arena operation already running");
    validate(r);
    if (floor < r.y1() || floor > r.y2() - 2)
      throw new IllegalArgumentException("Floor must leave 2 air blocks within arena");
    dirty(name, true);
    clearEntities(r);
    long total = (long) (r.x2() - r.x1() + 1) * (r.z2() - r.z1() + 1) * (r.y2() - floor);
    int budget =
        Math.max(1, Math.min(10000, plugin.getConfig().getInt("arena.drop-blocks-per-tick", 1000)));
    int batches = (int) ((total + budget - 1) / budget);
    int width = r.x2() - r.x1() + 1, depth = r.z2() - r.z1() + 1;
    run(
        name,
        "drop",
        batches,
        b -> {
          for (long i = (long) b * budget; i < Math.min(total, (long) (b + 1) * budget); i++) {
            int y = r.y2() - (int) (i / (width * depth));
            int z = r.z1() + (int) ((i / width) % depth);
            int x = r.x1() + (int) (i % width);
            r.w().getBlockAt(x, y, z).setType(Material.AIR, false);
          }
        },
        () -> clearEntities(r),
        done);
  }

  public void validate(Region r) {
    if (r.volume() > Math.min(16000000L, plugin.getConfig().getLong("arena.max-blocks", 4000000))
        || r.y1() < r.w().getMinHeight()
        || r.y2() >= r.w().getMaxHeight())
      throw new IllegalArgumentException("Arena exceeds configured block/height limit");
  }

  public void clearEntities(Region r) {
    for (var e : r.w().getEntities())
      if (!(e instanceof Player) && r.contains(e.getLocation())) e.remove();
  }

  @FunctionalInterface
  private interface Step {
    void run(int i) throws Exception;
  }

  @FunctionalInterface
  private interface Finish {
    void run() throws Exception;
  }

  private void run(
      String name, String operation, int total, Step step, Finish finish, Consumer<String> done) {
    locked.add(name);
    progress.put(name, "0%");
    new BukkitRunnable() {
      int i;
      final long started = System.nanoTime();
      long longestBatch;

      @Override
      public void run() {
        boolean completed = false;
        try {
          if (budgetTick != plugin.tick) {
            budgetTick = plugin.tick;
            budgetUsed = 0;
          }
          int count =
              Math.max(1, Math.min(8, plugin.getConfig().getInt("arena.cubes-per-tick", 1)));
          while (budgetUsed < count && i < total) {
            long batchStarted = System.nanoTime();
            step.run(i++);
            longestBatch = Math.max(longestBatch, System.nanoTime() - batchStarted);
            budgetUsed++;
          }
          progress.put(name, (i * 100 / Math.max(1, total)) + "%");
          if (i == total) {
            finish.run();
            cancel();
            locked.remove(name);
            progress.remove(name);
            String metrics =
                String.format(
                    Locale.ROOT,
                    "%s: %d batches, %.3fs elapsed, max batch %.3fms",
                    operation,
                    total,
                    (System.nanoTime() - started) / 1e9,
                    longestBatch / 1e6);
            lastMetrics.put(name, metrics);
            Files.writeString(new File(dir(name), "last-operation.txt").toPath(), metrics);
            plugin.getLogger().info("Arena " + name + " " + metrics);
            completed = true;
          }
        } catch (Exception e) {
          cancel();
          if (!operation.equals("snapshot")) dirty(name, true);
          locked.remove(name);
          progress.put(
              name,
              operation.equals("snapshot")
                  ? "Backup failed; retry backup"
                  : "FAILED; reset required");
          plugin
              .getLogger()
              .severe("Arena " + name + " operation failed: " + e.getClass().getSimpleName());
          done.accept(
              "Arena "
                  + operation
                  + " failed; inspect backup/storage"
                  + (operation.equals("snapshot") ? " and retry backup" : " and reset"));
          return;
        }
        if (completed) done.accept(null);
      }
    }.runTaskTimer(plugin, 1, 1);
  }
}
