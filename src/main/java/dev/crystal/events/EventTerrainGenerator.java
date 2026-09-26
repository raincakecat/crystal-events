package dev.crystal.events;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.generator.*;

/** Stateless, thread-safe plains-style terrain with three prepared event floor levels. */
public final class EventTerrainGenerator extends ChunkGenerator {
  @Override
  public void generateNoise(WorldInfo world, Random random, int x, int z, ChunkData data) {
    for (int y = Math.max(60, data.getMinHeight());
        y <= Math.min(80, data.getMaxHeight() - 1);
        y++) {
      Material material =
          y == 60
              ? Material.BEDROCK
              : y <= 70 ? Material.DEEPSLATE : y == 80 ? Material.GRASS_BLOCK : Material.STONE;
      data.setRegion(0, y, 0, 16, y + 1, 16, material);
    }
  }

  @Override
  public Location getFixedSpawnLocation(World world, Random random) {
    return new Location(world, -24.5, 81, -24.5);
  }

  @Override
  public int getBaseHeight(WorldInfo world, Random random, int x, int z, HeightMap map) {
    return 81;
  }

  @Override
  public BiomeProvider getDefaultBiomeProvider(WorldInfo world) {
    return new BiomeProvider() {
      @Override
      public Biome getBiome(WorldInfo info, int x, int y, int z) {
        return Biome.PLAINS;
      }

      @Override
      public List<Biome> getBiomes(WorldInfo info) {
        return List.of(Biome.PLAINS);
      }
    };
  }
}
