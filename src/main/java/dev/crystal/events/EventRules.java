package dev.crystal.events;

import static dev.crystal.events.Tournament.State.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;

/**
 * Supported Bukkit event boundaries. Other plugins must not deliberately bypass cancelled events.
 */
public final class EventRules implements Listener {
  private final EventsPlugin p;

  EventRules(EventsPlugin p) {
    this.p = p;
  }

  boolean owned(Player player) {
    return p.managed(player);
  }

  String area(Location location) {
    if (p.main != null && p.main.contains(location)) return "main";
    for (var g : p.gulags.values()) if (g.region().contains(location)) return g.name();
    return null;
  }

  boolean locked(String name) {
    return name != null
        && (p.arenas.locked.contains(name)
            || (p.arenas.dirty(name)
                && !(name.equals("main") && p.running())
                && !p.matches.containsKey(name)));
  }

  String fightingArea(Player player) {
    var entry = p.entry(player);
    if (!p.running()
        || entry == null
        || !owned(player)
        || p.terrainBusy
        || p.losses.contains(player.getUniqueId())) return null;
    if (entry.state == ACTIVE) return "main";
    if (entry.state == GULAG)
      for (var match : p.matches.values()) {
        if (match.a.equals(player.getUniqueId()) || match.b.equals(player.getUniqueId()))
          return match.arena.name();
      }
    return null;
  }

  boolean canEdit(Player player, Block block) {
    String name = area(block.getLocation());
    if (name == null) return !owned(player);
    if (locked(name)) return false;
    if (p.reserved()) return name.equals(fightingArea(player)) && !floor(block);
    return !owned(player);
  }

  boolean floor(Block block) {
    return "main".equals(area(block.getLocation()))
        && (block.getY() == p.main.y1()
            || block.getY() == p.getConfig().getInt("arena.deepslate-y")
            || block.getY() == p.getConfig().getInt("arena.bedrock-y"));
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void death(PlayerDeathEvent event) {
    Player player = event.getEntity();
    if (!owned(player)) return;
    event.getDrops().clear();
    event.setDroppedExp(0);
    event.setKeepInventory(true);
    event.setKeepLevel(true);
    player.getInventory().clear();
    player.setItemOnCursor(null);
    if (p.editors.remove(player.getUniqueId())) return;
    p.losses.add(player.getUniqueId());
    // Credit only the directly lethal melee/projectile hit. Crystal chains are intentionally
    // uncredited.
    if (player.getLastDamageCause() instanceof EntityDamageByEntityEvent hit) {
      Player killer = attacker(hit.getDamager());
      if (killer != null) p.directKillers.put(player.getUniqueId(), killer.getUniqueId());
    }
    Bukkit.getScheduler()
        .runTask(
            p,
            () -> {
              if (player.isOnline() && player.isDead()) player.spigot().respawn();
            });
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    if (!owned(player)) return;
    if (!p.editors.remove(player.getUniqueId())) {
      p.losses.add(player.getUniqueId());
      p.disconnects.add(player.getUniqueId());
    }
    p.restore(player);
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    // A disconnect is always a forfeit, even if the reconnect arrives before the next scheduler
    // tick.
    if (p.disconnects.contains(player.getUniqueId())) p.processLosses();
    if (!p.backups.exists(player)) return;
    Bukkit.getScheduler()
        .runTask(
            p,
            () -> {
              if (p.backups.exists(player)) {
                if (player.isDead()) player.spigot().respawn();
                else p.restore(player);
              }
            });
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent event) {
    Player player = event.getPlayer();
    if (!p.backups.exists(player)) return;
    if (p.lobby != null) event.setRespawnLocation(p.lobby);
    Bukkit.getScheduler()
        .runTask(
            p,
            () -> {
              p.processLosses();
              var entry = p.entry(player);
              if (p.running()
                  && entry != null
                  && (entry.state == WAITING || entry.state == RETURNING)) p.waiting(player);
              else p.restore(player);
            });
  }

  Player attacker(Entity entity) {
    if (entity instanceof Player player) return player;
    if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player)
      return player;
    return null;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void damage(EntityDamageEvent event) {
    if (event instanceof EntityDamageByEntityEvent hit && p.reserved()) {
      String source = area(hit.getDamager().getLocation());
      if (source != null && !source.equals(area(event.getEntity().getLocation()))) {
        event.setCancelled(true);
        return;
      }
    }
    Player attacker =
        event instanceof EntityDamageByEntityEvent hit ? attacker(hit.getDamager()) : null;
    if (attacker != null && owned(attacker)) {
      p.protection.remove(attacker.getUniqueId());
      if (fightingArea(attacker) == null) {
        event.setCancelled(true);
        return;
      }
      if (!(event.getEntity() instanceof Player) && !(event.getEntity() instanceof EnderCrystal)) {
        event.setCancelled(true);
        return;
      }
      if (!Objects.equals(fightingArea(attacker), area(event.getEntity().getLocation()))) {
        event.setCancelled(true);
        return;
      }
    }
    if (event.getEntity() instanceof Player victim) {
      if (owned(victim)) {
        String fight = fightingArea(victim);
        if (fight == null || p.protection.getOrDefault(victim.getUniqueId(), 0L) > p.tick) {
          event.setCancelled(true);
          return;
        }
        if (attacker != null && !Objects.equals(fight, fightingArea(attacker)))
          event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent hit
            && !Objects.equals(fight, area(hit.getDamager().getLocation())))
          event.setCancelled(true);
      } else if (attacker != null && owned(attacker)) event.setCancelled(true);
      else if (p.reserved() && area(victim.getLocation()) != null) event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void click(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    if (event.getView().getTopInventory().getHolder() instanceof EventsPlugin.Menu menu) {
      event.setCancelled(true);
      String action = menu.actions.get(event.getRawSlot());
      if (action != null && player.hasPermission("crystal.host"))
        Bukkit.getScheduler()
            .runTask(
                p,
                () -> {
                  player.closeInventory();
                  player.performCommand("event " + action);
                });
      return;
    }
    if (!owned(player)) return;
    if (event instanceof InventoryCreativeEvent
        || event.getView().getType() != InventoryType.CRAFTING
        || event.getRawSlot() < 5
        || event.getAction() == InventoryAction.DROP_ALL_CURSOR
        || event.getAction() == InventoryAction.DROP_ONE_CURSOR
        || event.getAction() == InventoryAction.DROP_ALL_SLOT
        || event.getAction() == InventoryAction.DROP_ONE_SLOT) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void drag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof EventsPlugin.Menu
        || (event.getWhoClicked() instanceof Player player
            && owned(player)
            && (event.getView().getType() != InventoryType.CRAFTING
                || event.getRawSlots().stream().anyMatch(s -> s < 5)))) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void open(InventoryOpenEvent event) {
    if (event.getPlayer() instanceof Player player
        && owned(player)
        && !(event.getInventory().getHolder() instanceof EventsPlugin.Menu)
        && event.getInventory().getType() != InventoryType.CRAFTING) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drop(PlayerDropItemEvent e) {
    if (owned(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void pickup(EntityPickupItemEvent e) {
    if (e.getEntity() instanceof Player player && owned(player)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void xp(PlayerExpChangeEvent e) {
    if (owned(e.getPlayer())) e.setAmount(0);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void breakBlock(BlockBreakEvent e) {
    if (!canEdit(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    else if (owned(e.getPlayer())) {
      e.setDropItems(false);
      e.setExpToDrop(0);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void placeBlock(BlockPlaceEvent e) {
    if (!canEdit(e.getPlayer(), e.getBlockPlaced())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void bucketFill(PlayerBucketFillEvent e) {
    if (!canEdit(e.getPlayer(), e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void bucketEmpty(PlayerBucketEmptyEvent e) {
    if (!canEdit(e.getPlayer(), e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void entityPlace(EntityPlaceEvent e) {
    if (e.getPlayer() != null && owned(e.getPlayer())) {
      p.protection.remove(e.getPlayer().getUniqueId());
      if (!(e.getEntity() instanceof EnderCrystal)
          || !Objects.equals(fightingArea(e.getPlayer()), area(e.getEntity().getLocation())))
        e.setCancelled(true);
    } else if (p.reserved() && area(e.getEntity().getLocation()) != null) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void interact(PlayerInteractEvent e) {
    Player player = e.getPlayer();
    Block block = e.getClickedBlock();
    if (!owned(player)) {
      if (block != null
          && (locked(area(block.getLocation()))
              || p.reserved() && area(block.getLocation()) != null)) {
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
      }
      return;
    }
    if (fightingArea(player) == null) {
      e.setUseInteractedBlock(Event.Result.DENY);
      e.setUseItemInHand(Event.Result.DENY);
      return;
    }
    if (block != null
        && (!Objects.equals(fightingArea(player), area(block.getLocation()))
            || block.getState() instanceof org.bukkit.inventory.InventoryHolder
            || block.getType().isInteractable())) e.setUseInteractedBlock(Event.Result.DENY);
    if (e.getItem() != null
        && e.getItem().getType() == Material.END_CRYSTAL
        && block != null
        && !Objects.equals(fightingArea(player), area(block.getRelative(0, 1, 0).getLocation())))
      e.setUseItemInHand(Event.Result.DENY);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void interactEntity(PlayerInteractEntityEvent e) {
    if (owned(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void consume(PlayerItemConsumeEvent e) {
    if (owned(e.getPlayer()) && fightingArea(e.getPlayer()) == null) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void shoot(ProjectileLaunchEvent e) {
    if (e.getEntity().getShooter() instanceof Player player && owned(player)) {
      p.protection.remove(player.getUniqueId());
      if (fightingArea(player) == null) e.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void vehicle(VehicleEnterEvent e) {
    if (e.getEntered() instanceof Player player && owned(player)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void command(PlayerCommandPreprocessEvent e) {
    if (!owned(e.getPlayer())) return;
    String message = e.getMessage();
    String root =
        (message.startsWith("/") ? message.substring(1) : message)
            .split(" ", 2)[0].toLowerCase(Locale.ROOT);
    if (!Set.of("event", "cpvpevent", "crystalpvpevents:event", "crystalpvpevents:cpvpevent")
        .contains(root)) {
      e.setCancelled(true);
      p.tell(e.getPlayer(), "Use /event leave before using other commands.");
    }
  }

  boolean mayEnter(Player player, Location to) {
    String name = area(to);
    String fight = fightingArea(player);
    if (fight != null) return fight.equals(name) && !locked(name);
    if (name == null) return true;
    if (locked(name)) return false;
    return !p.reserved() || name.equals(fightingArea(player));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void move(PlayerMoveEvent e) {
    if (p.internalTeleport.contains(e.getPlayer().getUniqueId())) return;
    if (p.editors.contains(e.getPlayer().getUniqueId())) {
      if (e.hasChangedPosition()) e.setTo(e.getFrom());
      return;
    }
    if (!mayEnter(e.getPlayer(), e.getTo())) e.setTo(e.getFrom());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void teleport(PlayerTeleportEvent e) {
    if (p.internalTeleport.contains(e.getPlayer().getUniqueId())) return;
    if (!mayEnter(e.getPlayer(), e.getTo())) {
      e.setCancelled(true);
      return;
    }
    if (owned(e.getPlayer())) {
      String fight = fightingArea(e.getPlayer());
      boolean pearl = e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
      if (!pearl || fight == null || !fight.equals(area(e.getTo()))) e.setCancelled(true);
    }
  }

  void explosion(Location origin, List<Block> blocks) {
    String source = area(origin);
    blocks.removeIf(
        block -> {
          String dest = area(block.getLocation());
          if (source != null && (p.reserved() || locked(source)))
            return locked(source) || !source.equals(dest) || floor(block);
          return dest != null && (p.reserved() || locked(dest));
        });
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void explode(EntityExplodeEvent e) {
    explosion(e.getLocation(), e.blockList());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void explodeBlock(BlockExplodeEvent e) {
    explosion(e.getBlock().getLocation(), e.blockList());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void flow(BlockFromToEvent e) {
    String a = area(e.getBlock().getLocation()), b = area(e.getToBlock().getLocation());
    if (locked(a)
        || locked(b)
        || (p.reserved() && !Objects.equals(a, b) && (a != null || b != null)))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void physics(BlockPhysicsEvent e) {
    if (locked(area(e.getBlock().getLocation()))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void burn(BlockBurnEvent e) {
    if (locked(area(e.getBlock().getLocation())) || floor(e.getBlock()) && p.reserved())
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void spread(BlockSpreadEvent e) {
    String a = area(e.getSource().getLocation()), b = area(e.getBlock().getLocation());
    if (locked(a) || locked(b) || p.reserved() && !Objects.equals(a, b) && (a != null || b != null))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void entityChange(EntityChangeBlockEvent e) {
    if (locked(area(e.getBlock().getLocation())) || p.reserved() && floor(e.getBlock()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void piston(BlockPistonExtendEvent e) {
    if ((p.reserved() || !p.arenas.locked.isEmpty())
        && (area(e.getBlock().getLocation()) != null
            || e.getBlocks().stream()
                .anyMatch(
                    b ->
                        area(b.getLocation()) != null
                            || area(b.getRelative(e.getDirection()).getLocation()) != null)))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void pistonBack(BlockPistonRetractEvent e) {
    if ((p.reserved() || !p.arenas.locked.isEmpty())
        && (area(e.getBlock().getLocation()) != null
            || e.getBlocks().stream().anyMatch(b -> area(b.getLocation()) != null)))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void grow(BlockGrowEvent e) {
    if (locked(area(e.getBlock().getLocation()))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void form(BlockFormEvent e) {
    if (locked(area(e.getBlock().getLocation()))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void fade(BlockFadeEvent e) {
    if (locked(area(e.getBlock().getLocation()))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void decay(LeavesDecayEvent e) {
    if (locked(area(e.getBlock().getLocation()))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void hopper(InventoryMoveItemEvent e) {
    if (locked(area(e.getSource().getLocation())) || locked(area(e.getDestination().getLocation())))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void hopperPickup(InventoryPickupItemEvent e) {
    if (locked(area(e.getInventory().getLocation()))) e.setCancelled(true);
  }
}
