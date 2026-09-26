package dev.crystal.events;

import java.util.*;

/** Tick-confined state machine. Call settle only after all losses in a server tick. */
public final class Tournament {
  public enum Phase {
    CLOSED,
    REGISTRATION,
    COUNTDOWN,
    FIGHTING,
    FINISHED
  }

  public enum State {
    REGISTERED,
    ACTIVE,
    WAITING,
    GULAG,
    RETURNING,
    OUT
  }

  public static final class Entry {
    public final UUID id;
    public final String name;
    public State state = State.REGISTERED;
    public int chances, kills, gulagWins, rekits, placement;
    public long survived, queuedAt, lastRekit = -1_000_000;

    Entry(UUID id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
  public Phase phase = Phase.CLOSED;
  public boolean gulagOpen, rekit;
  public long seconds;
  public UUID eventId;

  public void open(boolean gulag, boolean rekit) {
    if (phase != Phase.CLOSED && phase != Phase.FINISHED)
      throw new IllegalStateException("Event already open");
    entries.clear();
    eventId = UUID.randomUUID();
    phase = Phase.REGISTRATION;
    gulagOpen = gulag;
    this.rekit = rekit;
    seconds = 0;
  }

  public boolean join(UUID id, String name) {
    if (phase != Phase.REGISTRATION || entries.containsKey(id)) return false;
    entries.put(id, new Entry(id, name));
    return true;
  }

  public void start() {
    if (phase != Phase.COUNTDOWN) throw new IllegalStateException("Not counting down");
    phase = Phase.FIGHTING;
    entries.values().forEach(e -> e.state = State.ACTIVE);
  }

  public boolean potential(Entry e) {
    return e.state != State.OUT && e.state != State.REGISTERED;
  }

  public long remaining() {
    return entries.values().stream().filter(this::potential).count();
  }

  public long living() {
    return entries.values().stream().filter(e -> e.state == State.ACTIVE).count();
  }

  public List<Entry> queue() {
    return entries.values().stream()
        .filter(e -> e.state == State.WAITING)
        .sorted(Comparator.comparingLong(e -> e.queuedAt))
        .toList();
  }

  public void lose(UUID id, boolean disconnected, int maxChances) {
    Entry e = entries.get(id);
    if (e == null || e.state == State.OUT) return;
    if (phase != Phase.FIGHTING) {
      entries.remove(id);
      return;
    }
    if (!disconnected && (e.state == State.WAITING || e.state == State.RETURNING)) return;
    if (e.state == State.ACTIVE && !disconnected && gulagOpen && e.chances < maxChances) {
      e.chances++;
      e.state = State.WAITING;
      e.queuedAt = seconds;
    } else eliminate(e);
  }

  public void eliminate(Entry e) {
    if (e.state != State.OUT) {
      e.state = State.OUT;
      e.survived = seconds;
    }
  }

  public void comeback(UUID id) {
    Entry e = entries.get(id);
    if (e != null && e.state == State.GULAG) {
      e.gulagWins++;
      e.state = State.RETURNING;
    }
  }

  public boolean requestRekit(UUID id, int cooldown, int limit) {
    Entry e = entries.get(id);
    if (phase != Phase.FIGHTING
        || !rekit
        || e == null
        || e.state != State.ACTIVE
        || seconds - e.lastRekit < cooldown
        || (limit > 0 && e.rekits >= limit)) return false;
    e.rekits++;
    e.lastRekit = seconds;
    return true;
  }

  public void closeGulag() {
    gulagOpen = false;
    entries.values().stream()
        .filter(e -> e.state == State.WAITING || e.state == State.GULAG)
        .forEach(this::eliminate);
  }

  public boolean settle() {
    if (phase != Phase.FIGHTING) return false;
    int rank = (int) remaining() + 1;
    entries.values().stream()
        .filter(e -> e.state == State.OUT && e.placement == 0)
        .forEach(e -> e.placement = rank);
    // A single queued/returning player must resolve their revival before winning.
    if (remaining() > 1
        || entries.values().stream()
            .anyMatch(
                e ->
                    e.state == State.WAITING
                        || e.state == State.GULAG
                        || e.state == State.RETURNING)) return false;
    entries.values().stream()
        .filter(this::potential)
        .forEach(
            e -> {
              e.placement = 1;
              e.survived = seconds;
            });
    phase = Phase.FINISHED;
    return true;
  }
}
