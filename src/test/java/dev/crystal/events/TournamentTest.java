package dev.crystal.events;

import static dev.crystal.events.Tournament.State.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class TournamentTest {
  Tournament start(int count, boolean gulag) {
    Tournament t = new Tournament();
    t.open(gulag, true);
    for (int i = 0; i < count; i++) t.join(UUID.randomUUID(), "Player" + i);
    t.phase = Tournament.Phase.COUNTDOWN;
    t.start();
    return t;
  }

  List<Tournament.Entry> entries(Tournament t) {
    return new ArrayList<>(t.entries.values());
  }

  @Test
  void simultaneousFinalDeathsDrawWithSharedRank() {
    Tournament t = start(2, false);
    var e = entries(t);
    t.lose(e.get(0).id, false, 1);
    t.lose(e.get(1).id, false, 1);
    assertTrue(t.settle());
    assertEquals(0, t.living());
    assertEquals(1, e.get(0).placement);
    assertEquals(1, e.get(1).placement);
  }

  @Test
  void pendingGulagPreventsPrematureWinner() {
    Tournament t = start(2, true);
    var e = entries(t);
    t.lose(e.get(1).id, false, 1);
    assertEquals(WAITING, e.get(1).state);
    assertEquals(2, t.remaining());
    assertFalse(t.settle());
  }

  @Test
  void singleReturningPlayerMustLandBeforeWinning() {
    Tournament t = start(2, true);
    var e = entries(t);
    e.get(0).state = GULAG;
    e.get(1).state = GULAG;
    t.lose(e.get(1).id, false, 1);
    t.comeback(e.get(0).id);
    assertFalse(t.settle());
    e.get(0).state = ACTIVE;
    assertTrue(t.settle());
    assertEquals(1, e.get(0).gulagWins);
  }

  @Test
  void oneChanceIsConsumedOnlyOnMainDeath() {
    Tournament t = start(3, true);
    var e = entries(t).getFirst();
    t.lose(e.id, false, 1);
    assertEquals(1, e.chances);
    e.state = GULAG;
    t.comeback(e.id);
    e.state = ACTIVE;
    t.lose(e.id, false, 1);
    assertEquals(OUT, e.state);
    assertEquals(1, e.chances);
  }

  @Test
  void disconnectedPlayerNeverReceivesQueueChance() {
    Tournament t = start(2, true);
    var e = entries(t).getFirst();
    t.lose(e.id, true, 1);
    assertEquals(OUT, e.state);
    assertTrue(t.settle());
  }

  @Test
  void bothGulagDisconnectsProduceNoComeback() {
    Tournament t = start(3, true);
    var e = entries(t);
    e.get(0).state = GULAG;
    e.get(1).state = GULAG;
    t.lose(e.get(0).id, true, 1);
    t.lose(e.get(1).id, true, 1);
    t.comeback(e.get(0).id);
    assertEquals(OUT, e.get(0).state);
    assertEquals(0, e.get(0).gulagWins);
    assertTrue(t.settle());
  }

  @Test
  void gulagCutoffEliminatesQueueAndDuelsButPreservesEarnedReturn() {
    Tournament t = start(4, true);
    var e = entries(t);
    e.get(0).state = WAITING;
    e.get(1).state = GULAG;
    e.get(2).state = RETURNING;
    t.closeGulag();
    assertEquals(OUT, e.get(0).state);
    assertEquals(OUT, e.get(1).state);
    assertEquals(RETURNING, e.get(2).state);
    assertFalse(t.settle());
  }

  @Test
  void rekitCooldownLimitAndRevivalAreIndependent() {
    Tournament t = start(2, true);
    var e = entries(t).getFirst();
    assertTrue(t.requestRekit(e.id, 30, 2));
    assertFalse(t.requestRekit(e.id, 30, 2));
    t.seconds = 30;
    assertTrue(t.requestRekit(e.id, 30, 2));
    t.seconds = 60;
    assertFalse(t.requestRekit(e.id, 30, 2));
    t.rekit = false;
    t.lose(e.id, false, 1);
    assertEquals(WAITING, e.state);
    assertFalse(t.requestRekit(e.id, 0, 0));
  }

  @Test
  void unlimitedRekitStillRequiresLivingFighter() {
    Tournament t = start(2, true);
    var e = entries(t).getFirst();
    for (int i = 0; i < 10; i++) assertTrue(t.requestRekit(e.id, 0, 0));
    e.state = GULAG;
    assertFalse(t.requestRekit(e.id, 0, 0));
  }

  @Test
  void duplicateLossCannotConsumeTwoChances() {
    Tournament t = start(3, true);
    var e = entries(t).getFirst();
    t.lose(e.id, false, 1);
    t.lose(e.id, false, 1);
    assertEquals(WAITING, e.state);
    assertEquals(1, e.chances);
  }

  @Test
  void reopeningClearsStatisticsAndGeneratesNewResultId() {
    Tournament t = start(2, false);
    UUID previous = t.eventId;
    t.entries.values().forEach(t::eliminate);
    t.settle();
    t.open(true, true);
    assertNotEquals(previous, t.eventId);
    assertTrue(t.entries.isEmpty());
    assertEquals(0, t.seconds);
  }
}
