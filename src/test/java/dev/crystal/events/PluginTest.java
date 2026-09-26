package dev.crystal.events;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PluginTest {
  ServerMock server;
  EventsPlugin plugin;
  World world;
  PlayerMock host;

  // MockBukkit does not implement player-specific borders. This test double stores assignment only;
  // actual border rendering/damage belongs to the separate Paper smoke/manual suite.
  static class BorderPlayer extends PlayerMock {
    WorldBorder border;

    BorderPlayer(ServerMock server, String name) {
      super(server, name);
    }

    @Override
    public void setWorldBorder(WorldBorder border) {
      this.border = border;
    }

    @Override
    public WorldBorder getWorldBorder() {
      return border;
    }

    @Override
    public void saveData() {
      /* Real player.dat I/O is outside this server mock. */
    }
  }

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("world");
    plugin = MockBukkit.load(EventsPlugin.class);
    plugin.getConfig().set("worlds.auto-create", false);
    host = new BorderPlayer(server, "Host");
    server.addPlayer(host);
    host.setOp(true);
    host.teleport(new Location(world, 100.5, 65, 100.5));
    server.getScheduler().performTicks(2);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void pluginEnablesAndRegistersHostMenu() {
    assertTrue(plugin.isEnabled());
    host.performCommand("event gui");
    assertInstanceOf(
        EventsPlugin.Menu.class, host.getOpenInventory().getTopInventory().getHolder());
  }

  @Test
  void hostCanPersistRekitToggleButGulagCannotRekit() {
    server.dispatchCommand(server.getConsoleSender(), "event rekit off");
    assertFalse(plugin.game.rekit);
    assertFalse(plugin.getConfig().getBoolean("rekit.enabled"));
    server.dispatchCommand(server.getConsoleSender(), "event rekit on");
    assertTrue(plugin.game.rekit);
    assertTrue(plugin.getConfig().getBoolean("rekit.enabled"));
    plugin.game.open(true, true);
    plugin.game.join(host.getUniqueId(), host.getName());
    plugin.game.phase = Tournament.Phase.FIGHTING;
    plugin.game.entries.get(host.getUniqueId()).state = Tournament.State.GULAG;
    host.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
    host.performCommand("event rekit");
    assertEquals(3, host.getInventory().getItem(0).getAmount());
    assertEquals(0, plugin.game.entries.get(host.getUniqueId()).rekits);
  }

  @Test
  void ordinaryPlayerCannotEnableRekit() {
    plugin.setRekit(false);
    var guest = server.addPlayer();
    guest.performCommand("event rekit on");
    assertFalse(plugin.game.rekit);
  }

  @Test
  void rearrangedKitAcceptedButExtraItemsAndMetadataRejected() throws Exception {
    host.getInventory().setItem(0, new ItemStack(Material.END_CRYSTAL, 64));
    host.getInventory().setItem(1, new ItemStack(Material.OBSIDIAN, 64));
    host.getInventory().setItem(40, new ItemStack(Material.TOTEM_OF_UNDYING));
    plugin.kits.saveKit(host, false);
    ItemStack[] layout = Kits.copy(plugin.kits.main);
    layout[7] = layout[0];
    layout[0] = null;
    assertTrue(Kits.sameQuantities(plugin.kits.main, layout));
    layout[7].setAmount(63);
    assertFalse(Kits.sameQuantities(plugin.kits.main, layout));
    layout[7].setAmount(64);
    var meta = layout[7].getItemMeta();
    meta.setDisplayName("Alternate");
    layout[7].setItemMeta(meta);
    assertFalse(Kits.sameQuantities(plugin.kits.main, layout));
  }

  @Test
  void rekitReplacesAllSlotsRatherThanAddingItems() throws Exception {
    host.getInventory().setItem(0, new ItemStack(Material.END_CRYSTAL, 64));
    plugin.kits.saveKit(host, false);
    host.getInventory().setItem(10, new ItemStack(Material.DIAMOND, 64));
    plugin.kits.give(host, false);
    plugin.kits.give(host, false);
    assertEquals(64, host.getInventory().getItem(0).getAmount());
    assertNull(host.getInventory().getItem(10));
  }

  @Test
  void originalInventoryAndLocationRecoverFromDisk() throws Exception {
    host.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 13));
    Location before = host.getLocation();
    plugin.backups.capture(host);
    host.getInventory().clear();
    host.teleport(new Location(world, 0, 100, 0));
    assertTrue(plugin.backups.restore(host));
    assertEquals(13, host.getInventory().getItem(0).getAmount());
    assertEquals(before, host.getLocation());
    assertFalse(plugin.backups.exists(host));
  }

  @Test
  void layoutEditorRejectsExternalCommandsAndItemDrops() throws Exception {
    host.getInventory().setItem(0, new ItemStack(Material.OBSIDIAN, 64));
    plugin.kits.saveKit(host, false);
    plugin.layout(host, new String[] {"layout", "edit"});
    var command = new PlayerCommandPreprocessEvent(host, "/give Host diamond 64");
    server.getPluginManager().callEvent(command);
    assertTrue(command.isCancelled());
    var dropped = world.dropItem(host.getLocation(), new ItemStack(Material.OBSIDIAN));
    var drop = new PlayerDropItemEvent(host, dropped);
    server.getPluginManager().callEvent(drop);
    assertTrue(drop.isCancelled());
    plugin.layout(host, new String[] {"layout", "cancel"});
    assertEquals(64, host.getInventory().getItem(0).getAmount());
  }

  @Test
  void missingSetupPreflightFailsWithoutMutatingState() {
    assertFalse(plugin.preflight().isEmpty());
    assertThrows(IllegalStateException.class, () -> plugin.open());
    assertEquals(Tournament.Phase.CLOSED, plugin.game.phase);
  }

  @Test
  void restoredEliminatedPlayerIsNoLongerInventoryRestricted() throws Exception {
    plugin.game.open(false, false);
    plugin.backups.capture(host);
    plugin.game.join(host.getUniqueId(), host.getName());
    plugin.game.entries.get(host.getUniqueId()).state = Tournament.State.OUT;
    plugin.restore(host);
    assertFalse(plugin.managed(host));
    var command = new PlayerCommandPreprocessEvent(host, "/help");
    server.getPluginManager().callEvent(command);
    assertFalse(command.isCancelled());
  }

  @Test
  void dirtyArenaLockSurvivesStoreReconstruction() {
    plugin.arenas.dirty("main", true);
    ArenaStore reopened = new ArenaStore(plugin);
    assertTrue(reopened.dirty("main"));
    reopened.dirty("main", false);
    assertFalse(reopened.dirty("main"));
  }

  @Test
  void unsupportedSnapshotFailurePublishesNoManifestAndAllowsRetry() {
    Region region = new Region("world", 0, 60, 0, 1, 62, 1);
    plugin.main = region;
    world.getBlockAt(0, 60, 0).setType(Material.OBSIDIAN);
    List<String> status = new ArrayList<>();
    plugin.arenas.snapshot("main", region, status::add);
    server.getScheduler().performTicks(5);
    // MockBukkit's StructureManager cannot write NBT; exercise the real failure path.
    assertEquals(1, status.size());
    assertNotNull(status.getFirst());
    assertFalse(plugin.arenas.hasBackup("main", region));
    assertFalse(plugin.arenas.dirty("main"));
    assertEquals(Material.OBSIDIAN, world.getBlockAt(0, 60, 0).getType());
    assertThrows(
        IllegalStateException.class, () -> plugin.arenas.restore("main", region, status::add));
  }

  @Test
  void terrainDropPreservesFloorAndBoundsAndRemainsDirtyForLaterReset() {
    Region region = new Region("world", 0, 60, 0, 1, 63, 1);
    plugin.main = region;
    for (int y = 60; y <= 63; y++)
      for (int x = 0; x <= 2; x++)
        for (int z = 0; z <= 1; z++) world.getBlockAt(x, y, z).setType(Material.STONE);
    List<String> status = new ArrayList<>();
    plugin.arenas.drop("main", region, 60, status::add);
    server.getScheduler().performTicks(5);
    assertEquals(1, status.size());
    assertNull(status.getFirst());
    assertEquals(Material.STONE, world.getBlockAt(0, 60, 0).getType());
    assertEquals(Material.AIR, world.getBlockAt(0, 61, 0).getType());
    assertEquals(Material.STONE, world.getBlockAt(2, 62, 0).getType());
    assertTrue(plugin.arenas.dirty("main"));
  }

  List<PlayerMock> fightingPlayers() throws Exception {
    plugin.main = new Region("world", 0, 60, 0, 31, 80, 31);
    plugin.lobby = host.getLocation();
    plugin.game.open(true, true);
    plugin.border = world.getWorldBorder();
    plugin.border.setCenter(plugin.main.center());
    plugin.border.setSize(32);
    List<PlayerMock> players = new ArrayList<>();
    players.add(host);
    for (int i = 1; i < 3; i++) {
      PlayerMock player = new BorderPlayer(server, "Fighter" + i);
      server.addPlayer(player);
      players.add(player);
    }
    for (PlayerMock player : players) {
      player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 9));
      plugin.backups.capture(player);
      plugin.game.join(player.getUniqueId(), player.getName());
      plugin.teleport(player, new Location(world, 8.5, 65, 8.5));
    }
    plugin.game.phase = Tournament.Phase.COUNTDOWN;
    plugin.game.start();
    return players;
  }

  @Test
  void deathListenersBatchLossesAndSuppressDroppedKitItems() throws Exception {
    var players = fightingPlayers();
    for (PlayerMock player : players.subList(0, 2)) {
      var event =
          new PlayerDeathEvent(
              player,
              DamageSource.builder(DamageType.GENERIC).build(),
              new ArrayList<>(List.of(new ItemStack(Material.END_CRYSTAL, 64))),
              10,
              "death");
      server.getPluginManager().callEvent(event);
      assertTrue(event.getDrops().isEmpty());
      assertEquals(0, event.getDroppedExp());
    }
    plugin.processLosses();
    assertEquals(2, plugin.game.queue().size());
    assertEquals(3, plugin.game.remaining());
    assertFalse(plugin.game.settle());
  }

  @Test
  void quitAndImmediateRejoinForfeitWithoutLosingOriginalInventory() throws Exception {
    fightingPlayers();
    server.getPluginManager().callEvent(new PlayerQuitEvent(host, "quit"));
    server.getPluginManager().callEvent(new PlayerJoinEvent(host, "join"));
    assertEquals(Tournament.State.OUT, plugin.entry(host).state);
    assertEquals(9, host.getInventory().getItem(0).getAmount());
    assertFalse(plugin.managed(host));
    assertEquals(0, plugin.entry(host).chances);
  }

  @Test
  void guiClickExecutesToggleAndCannotTakeMenuItem() throws Exception {
    fightingPlayers();
    plugin.gui(host);
    assertTrue(host.hasPermission("crystal.host"), "Host permission");
    assertInstanceOf(
        EventsPlugin.Menu.class, host.getOpenInventory().getTopInventory().getHolder());
    var event =
        new org.bukkit.event.inventory.InventoryClickEvent(
            host.getOpenInventory(),
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
            4,
            org.bukkit.event.inventory.ClickType.LEFT,
            org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
    server.getScheduler().performTicks(2);
    assertFalse(plugin.game.rekit, "Message: " + host.nextMessage());
  }

  @Test
  void mainFighterCannotBreakFloorOrWalkOutsideSelectedArea() throws Exception {
    fightingPlayers();
    var floor = new BlockBreakEvent(world.getBlockAt(8, 60, 8), host);
    server.getPluginManager().callEvent(floor);
    assertTrue(floor.isCancelled());
    Location from = host.getLocation(), to = new Location(world, 40, 65, 8);
    var move = new PlayerMoveEvent(host, from, to);
    server.getPluginManager().callEvent(move);
    assertEquals(from, move.getTo());
  }

  @Test
  void virtualBorderWithNoWorldStillUsesFullWidthCoordinates() {
    assertTrue(plugin.insideSquare(new Location(world, 4, 70, 4), new Location(null, 0, 0, 0), 10));
    assertFalse(
        plugin.insideSquare(new Location(world, 6, 70, 0), new Location(null, 0, 0, 0), 10));
  }
}
