package dev.crystal.events;

import java.util.*;

public final class Timeline {
  public record Stage(String name, String trigger, long value, String action) {
    public Stage {
      if (name == null
          || name.isBlank()
          || !Set.of("time", "players", "host").contains(trigger)
          || value < 0
          || !Set.of(
                  "announce",
                  "shrink",
                  "rekit-off",
                  "gulag-close",
                  "deepslate-drop",
                  "bedrock-finale")
              .contains(action)) throw new IllegalArgumentException("Invalid timeline stage");
    }
  }

  public final List<Stage> stages;
  public int index;
  public long delayedUntil;
  public String current = "Fighting";

  public Timeline(List<Stage> stages) {
    this.stages = List.copyOf(stages);
  }

  public Stage next() {
    return index < stages.size() ? stages.get(index) : null;
  }

  public Stage poll(long seconds, long remaining, boolean host) {
    Stage n = next();
    if (n == null || seconds < delayedUntil) return null;
    if (!host
        && !(n.trigger.equals("time") && seconds >= n.value)
        && !(n.trigger.equals("players") && remaining <= n.value)) return null;
    index++;
    current = n.name;
    delayedUntil = 0;
    return n;
  }

  public void delay(long now, long delay) {
    if (delay < 0) throw new IllegalArgumentException("Positive delay required");
    delayedUntil = Math.max(now, delayedUntil) + delay;
  }

  public void skip() {
    if (next() != null) index++;
    delayedUntil = 0;
  }
}
