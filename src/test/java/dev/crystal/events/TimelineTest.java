package dev.crystal.events;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class TimelineTest {
  @Test
  void orderedTriggersAreExactlyOnce() {
    var t =
        new Timeline(
            List.of(
                new Timeline.Stage("shrink", "time", 10, "shrink"),
                new Timeline.Stage("cutoff", "players", 2, "gulag-close"),
                new Timeline.Stage("final", "host", 0, "bedrock-finale")));
    assertNull(t.poll(9, 2, false));
    assertEquals("shrink", t.poll(10, 3, false).action());
    assertNull(t.poll(10, 3, false));
    assertEquals("gulag-close", t.poll(11, 2, false).action());
    assertNull(t.poll(999, 1, false));
    assertEquals("bedrock-finale", t.poll(999, 1, true).action());
    assertNull(t.poll(1000, 1, true));
  }

  @Test
  void delayAndSkipDoNotExecuteEffects() {
    var t =
        new Timeline(
            List.of(
                new Timeline.Stage("A", "time", 0, "announce"),
                new Timeline.Stage("B", "host", 0, "rekit-off")));
    t.delay(10, 30);
    assertNull(t.poll(39, 2, true));
    t.skip();
    assertEquals("B", t.poll(40, 2, true).name());
  }

  @Test
  void invalidTriggersAndActionsFailEarly() {
    assertThrows(
        IllegalArgumentException.class, () -> new Timeline.Stage("x", "typo", 1, "shrink"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Timeline.Stage("x", "time", 1, "arbitrary-command"));
  }
}
