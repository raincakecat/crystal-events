package dev.crystal.events;

import java.io.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

/** Write before modifying a player. Pending files recover on join after restart. */
public final class PlayerBackup {
  private final File root;
  private final Set<UUID> pending = new HashSet<>();

  public PlayerBackup(File root) {
    this.root = root;
    root.mkdirs();
    File[] files = root.listFiles((dir, name) -> name.endsWith(".yml"));
    if (files != null)
      for (File f : files) {
        try {
          pending.add(UUID.fromString(f.getName().replace(".yml", "")));
        } catch (IllegalArgumentException ignored) {
        }
      }
  }

  private File file(Player p) {
    return new File(root, p.getUniqueId() + ".yml");
  }

  public boolean exists(Player p) {
    return pending.contains(p.getUniqueId());
  }

  public void capture(Player p) throws IOException {
    if (exists(p))
      throw new IllegalStateException(
          "Pending inventory recovery; leave/rejoin before participating");
    p.closeInventory();
    if (p.getItemOnCursor().getType() != Material.AIR)
      throw new IllegalArgumentException("Clear your cursor first");
    var c = new YamlConfiguration();
    c.set("location", p.getLocation());
    c.set("mode", p.getGameMode().name());
    for (int i = 0; i < 41; i++) c.set("items." + i, p.getInventory().getItem(i));
    c.set("health", p.getHealth());
    c.set("food", p.getFoodLevel());
    c.set("saturation", p.getSaturation());
    c.set("exhaustion", p.getExhaustion());
    c.set("level", p.getLevel());
    c.set("exp", p.getExp());
    c.set("total-exp", p.getTotalExperience());
    c.set("effects", new ArrayList<>(p.getActivePotionEffects()));
    c.set("allow-flight", p.getAllowFlight());
    c.set("flying", p.isFlying());
    c.set("fire", p.getFireTicks());
    c.save(file(p));
    pending.add(p.getUniqueId());
  }

  public boolean restore(Player p) {
    if (!exists(p) || p.isDead()) return false;
    var c = YamlConfiguration.loadConfiguration(file(p));
    Location loc = c.getLocation("location");
    if (loc == null || loc.getWorld() == null) return false;
    if (!p.teleport(loc)) return false;
    p.closeInventory();
    p.setItemOnCursor(null);
    p.getInventory().clear();
    for (int i = 0; i < 41; i++) p.getInventory().setItem(i, c.getItemStack("items." + i));
    p.setGameMode(GameMode.valueOf(c.getString("mode", "SURVIVAL")));
    p.setWorldBorder(null);
    p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
    for (Object o : c.getList("effects", List.of()))
      if (o instanceof PotionEffect e) p.addPotionEffect(e);
    p.setHealth(
        Math.max(
            0.5,
            Math.min(c.getDouble("health", 20), p.getAttribute(Attribute.MAX_HEALTH).getValue())));
    p.setFoodLevel(c.getInt("food", 20));
    p.setSaturation((float) c.getDouble("saturation", 5));
    p.setExhaustion((float) c.getDouble("exhaustion"));
    p.setTotalExperience(c.getInt("total-exp"));
    p.setLevel(c.getInt("level"));
    p.setExp((float) c.getDouble("exp"));
    p.setAllowFlight(c.getBoolean("allow-flight"));
    p.setFlying(c.getBoolean("flying"));
    p.setFireTicks(c.getInt("fire"));
    p.setFallDistance(0);
    try {
      p.saveData();
      java.nio.file.Files.delete(file(p).toPath());
      pending.remove(p.getUniqueId());
    } catch (IOException e) {
      throw new IllegalStateException("Cannot finish inventory recovery", e);
    }
    return true;
  }
}
