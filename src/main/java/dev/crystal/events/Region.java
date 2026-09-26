package dev.crystal.events;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;

public record Region(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
  public Region {
    if (x1 > x2 || y1 > y2 || z1 > z2) throw new IllegalArgumentException("Reversed bounds");
  }

  public static Region between(Location a, Location b) {
    if (!a.getWorld().equals(b.getWorld()))
      throw new IllegalArgumentException("Corners must share a world");
    return new Region(
        a.getWorld().getName(),
        Math.min(a.getBlockX(), b.getBlockX()),
        Math.min(a.getBlockY(), b.getBlockY()),
        Math.min(a.getBlockZ(), b.getBlockZ()),
        Math.max(a.getBlockX(), b.getBlockX()),
        Math.max(a.getBlockY(), b.getBlockY()),
        Math.max(a.getBlockZ(), b.getBlockZ()));
  }

  public long volume() {
    return ((long) x2 - x1 + 1) * ((long) y2 - y1 + 1) * ((long) z2 - z1 + 1);
  }

  public World w() {
    return Objects.requireNonNull(Bukkit.getWorld(world), "World not loaded: " + world);
  }

  public boolean contains(Location l) {
    return l != null
        && l.getWorld() != null
        && world.equals(l.getWorld().getName())
        && l.getX() >= x1
        && l.getX() < x2 + 1d
        && l.getY() >= y1
        && l.getY() < y2 + 1d
        && l.getZ() >= z1
        && l.getZ() < z2 + 1d;
  }

  public boolean overlaps(Region r) {
    return world.equals(r.world)
        && x1 <= r.x2
        && x2 >= r.x1
        && y1 <= r.y2
        && y2 >= r.y1
        && z1 <= r.z2
        && z2 >= r.z1;
  }

  public Location center() {
    return new Location(w(), (x1 + x2 + 1d) / 2, y2 + 1, (z1 + z2 + 1d) / 2);
  }

  public double width() {
    return Math.min(x2 - x1 + 1, z2 - z1 + 1);
  }

  public void save(ConfigurationSection c) {
    c.set("world", world);
    c.set("bounds", List.of(x1, y1, z1, x2, y2, z2));
  }

  public static Region load(ConfigurationSection c) {
    if (c == null) return null;
    List<Integer> b = c.getIntegerList("bounds");
    return new Region(
        c.getString("world"), b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5));
  }

  public record Cube(int x, int y, int z, int sx, int sy, int sz) {}

  public List<Cube> cubes(int size) {
    if (size < 1 || size > 16) throw new IllegalArgumentException("Cube size 1..16");
    List<Cube> c = new ArrayList<>();
    for (int y = y1; y <= y2; y += size)
      for (int z = z1; z <= z2; z += size)
        for (int x = x1; x <= x2; x += size)
          c.add(
              new Cube(
                  x,
                  y,
                  z,
                  Math.min(size, x2 - x + 1),
                  Math.min(size, y2 - y + 1),
                  Math.min(size, z2 - z + 1)));
    return c;
  }
}
