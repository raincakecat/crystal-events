package dev.crystal.events;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RegionTest {
  @Test
  void snapshotCubesCoverEveryBlockExactlyOnceIncludingEdges() {
    Region r = new Region("world", -3, -60, 2, 15, -44, 20);
    Set<String> blocks = new HashSet<>();
    for (var c : r.cubes(8))
      for (int y = c.y(); y < c.y() + c.sy(); y++)
        for (int z = c.z(); z < c.z() + c.sz(); z++)
          for (int x = c.x(); x < c.x() + c.sx(); x++)
            assertTrue(blocks.add(x + "," + y + "," + z));
    assertEquals(r.volume(), blocks.size());
    assertTrue(blocks.contains("15,-44,20"));
    assertTrue(blocks.contains("-3,-60,2"));
  }

  @Test
  void overlapIncludesTouchingBlocksButNotAdjacentBlocks() {
    Region r = new Region("world", 0, 0, 0, 15, 15, 15);
    assertTrue(r.overlaps(new Region("world", 15, 0, 0, 20, 10, 10)));
    assertFalse(r.overlaps(new Region("world", 16, 0, 0, 20, 10, 10)));
  }
}
