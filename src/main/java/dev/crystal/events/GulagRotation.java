package dev.crystal.events;

import java.util.*;

/** One duel per world; a location is used at most once per tournament. */
public final class GulagRotation {
  public record Site(String id, String world) {}

  private final Set<String> used = new HashSet<>();
  public final Map<String, String> lastByWorld = new LinkedHashMap<>();

  public void newEvent() {
    used.clear();
  }

  public Optional<Site> choose(List<Site> sites, Set<String> busyWorlds, Set<String> unavailable) {
    Map<String, List<Site>> byWorld = new LinkedHashMap<>();
    sites.forEach(s -> byWorld.computeIfAbsent(s.world(), k -> new ArrayList<>()).add(s));
    for (var group : byWorld.entrySet()) {
      if (busyWorlds.contains(group.getKey())) continue;
      List<Site> list = group.getValue();
      int start = 0;
      for (int i = 0; i < list.size(); i++)
        if (list.get(i).id().equals(lastByWorld.get(group.getKey()))) start = (i + 1) % list.size();
      for (int i = 0; i < list.size(); i++) {
        Site site = list.get((start + i) % list.size());
        if (!used.contains(site.id()) && !unavailable.contains(site.id())) return Optional.of(site);
      }
    }
    return Optional.empty();
  }

  public void claim(Site site) {
    used.add(site.id());
    lastByWorld.put(site.world(), site.id());
  }

  public int usedCount() {
    return used.size();
  }
}
