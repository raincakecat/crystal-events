package dev.crystal.events;

import java.io.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class Kits {
  private final File folder;
  public ItemStack[] main, gulag;

  public Kits(File folder) {
    this.folder = folder;
    folder.mkdirs();
    main = load(new File(folder, "main.yml"));
    gulag = load(new File(folder, "gulag.yml"));
  }

  static ItemStack[] copy(ItemStack[] a) {
    if (a == null) return null;
    return Arrays.stream(a).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
  }

  private ItemStack[] load(File f) {
    if (!f.exists()) return null;
    var c = YamlConfiguration.loadConfiguration(f);
    ItemStack[] a = new ItemStack[41];
    for (int i = 0; i < 41; i++) a[i] = c.getItemStack("slots." + i);
    return a;
  }

  private void save(File f, ItemStack[] a) throws IOException {
    var c = new YamlConfiguration();
    for (int i = 0; i < 41; i++) c.set("slots." + i, a[i]);
    c.save(f);
  }

  public void saveKit(Player p, boolean separate) throws IOException {
    ItemStack[] a = copy(p.getInventory().getContents());
    if (Arrays.stream(a).noneMatch(i -> i != null && !i.getType().isAir()))
      throw new IllegalArgumentException("Kit cannot be empty");
    // Reject containers with hidden supplies: kit quantities must be visible and enforceable.
    for (ItemStack i : a)
      if (i != null
          && (i.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta
              || i.getItemMeta() instanceof org.bukkit.inventory.meta.BundleMeta))
        throw new IllegalArgumentException("Container items/bundles are not supported in kits");
    save(new File(folder, separate ? "gulag.yml" : "main.yml"), a);
    if (separate) gulag = a;
    else main = a;
  }

  public static boolean sameQuantities(ItemStack[] a, ItemStack[] b) {
    return quantities(a).equals(quantities(b));
  }

  private static Map<ItemStack, Integer> quantities(ItemStack[] a) {
    Map<ItemStack, Integer> m = new HashMap<>();
    if (a != null)
      for (ItemStack i : a)
        if (i != null && !i.getType().isAir()) {
          ItemStack k = i.clone();
          k.setAmount(1);
          m.merge(k, i.getAmount(), Integer::sum);
        }
    return m;
  }

  public void saveLayout(Player p) throws IOException {
    ItemStack[] a = copy(p.getInventory().getContents());
    if (main == null || !sameQuantities(main, a))
      throw new IllegalArgumentException(
          "Layout must contain exactly the saved kit, including item metadata and quantities. Use"
              + " /event layout edit first.");
    save(new File(folder, p.getUniqueId() + ".yml"), a);
  }

  public void give(Player p, boolean separate) {
    ItemStack[] base = separate ? gulag : main;
    if (base == null) throw new IllegalStateException("Kit missing");
    ItemStack[] layout = separate ? null : load(new File(folder, p.getUniqueId() + ".yml"));
    p.closeInventory();
    p.setItemOnCursor(null);
    p.getInventory().clear();
    p.getInventory()
        .setContents(copy(layout != null && sameQuantities(base, layout) ? layout : base));
    p.updateInventory();
  }
}
