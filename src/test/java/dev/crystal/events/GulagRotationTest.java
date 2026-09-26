package dev.crystal.events;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class GulagRotationTest {
  final List<GulagRotation.Site> sites =
      List.of(
          new GulagRotation.Site("a1", "a"),
          new GulagRotation.Site("a2", "a"),
          new GulagRotation.Site("b1", "b"));

  @Test
  void busyWorldExcludesEveryLocationAndResetLockCanBlockIt() {
    var r = new GulagRotation();
    assertEquals("b1", r.choose(sites, Set.of("a"), Set.of()).orElseThrow().id());
    assertTrue(r.choose(sites, Set.of("a", "b"), Set.of()).isEmpty());
  }

  @Test
  void freshLocationsUntilExhaustedThenRotateAcrossEvents() {
    var r = new GulagRotation();
    r.claim(r.choose(sites, Set.of(), Set.of()).orElseThrow());
    assertEquals("a2", r.choose(sites, Set.of(), Set.of()).orElseThrow().id());
    r.newEvent();
    assertEquals("a2", r.choose(sites, Set.of(), Set.of()).orElseThrow().id());
    for (int i = 0; i < 3; i++) r.claim(r.choose(sites, Set.of(), Set.of()).orElseThrow());
    assertTrue(r.choose(sites, Set.of(), Set.of()).isEmpty());
    assertEquals(3, r.usedCount());
  }

  @Test
  void unavailableLocationsAreSkipped() {
    var r = new GulagRotation();
    assertEquals("a2", r.choose(sites, Set.of(), Set.of("a1")).orElseThrow().id());
  }

  @Test
  void generatedSitesHaveDistinctNonoverlappingFootprints() {
    var regions = ManagedWorlds.sites("gulag", 32);
    assertEquals(32, regions.size());
    for (int i = 0; i < regions.size(); i++) {
      assertEquals(32, regions.get(i).width());
      for (int j = i + 1; j < regions.size(); j++)
        assertFalse(regions.get(i).overlaps(regions.get(j)));
    }
    assertThrows(IllegalArgumentException.class, () -> ManagedWorlds.sites("g", 33));
  }
}
