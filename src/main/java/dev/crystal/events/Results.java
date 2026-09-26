package dev.crystal.events;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class Results implements AutoCloseable {
  private final Path root;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "CrystalPvP-results");
            t.setDaemon(true);
            return t;
          });

  public Results(File root) {
    this.root = root.toPath();
    root.mkdirs();
  }

  public static String json(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray())
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 32) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    return b.append('"').toString();
  }

  public static String payload(String summary) {
    return "{\"allowed_mentions\":{\"parse\":[]},\"content\":"
        + json(summary.substring(0, Math.min(1900, summary.length())))
        + "}";
  }

  public static boolean validUrl(String url) {
    try {
      URI u = URI.create(url);
      return u.getScheme().equals("https")
          && Set.of("discord.com", "canary.discord.com", "ptb.discord.com").contains(u.getHost())
          && u.getPort() == -1
          && u.getUserInfo() == null
          && u.getQuery() == null
          && u.getFragment() == null
          && u.getPath().matches("/api/webhooks/[0-9]+/[A-Za-z0-9_-]+");
    } catch (Exception e) {
      return false;
    }
  }

  public static String summary(Tournament t, boolean aborted) {
    StringBuilder b =
        new StringBuilder(
            "CrystalPvP event "
                + t.eventId
                + (aborted ? " — stopped by host" : " — complete")
                + "\n");
    t.entries.values().stream()
        .sorted(Comparator.comparingInt(e -> e.placement == 0 ? Integer.MAX_VALUE : e.placement))
        .forEach(
            e ->
                b.append(e.placement == 0 ? "—" : e.placement)
                    .append(". ")
                    .append(e.name)
                    .append(" | eliminations ")
                    .append(e.kills)
                    .append(" | gulag wins ")
                    .append(e.gulagWins)
                    .append(" | survival ")
                    .append(e.survived)
                    .append("s\n"));
    return b.toString();
  }

  public void save(
      Tournament t, boolean aborted, boolean enabled, String url, int timeout, Consumer<String> log)
      throws IOException {
    String id = t.eventId.toString(), text = summary(t, aborted);
    Files.writeString(root.resolve(id + ".txt"), text, StandardOpenOption.CREATE_NEW);
    String rows =
        t.entries.values().stream()
            .map(
                e ->
                    "{\"uuid\":"
                        + json(e.id.toString())
                        + ",\"name\":"
                        + json(e.name)
                        + ",\"placement\":"
                        + e.placement
                        + ",\"eliminations\":"
                        + e.kills
                        + ",\"gulagVictories\":"
                        + e.gulagWins
                        + ",\"survivalSeconds\":"
                        + e.survived
                        + "}")
            .collect(java.util.stream.Collectors.joining(","));
    Files.writeString(
        root.resolve(id + ".json"),
        "{\"eventId\":" + json(id) + ",\"aborted\":" + aborted + ",\"players\":[" + rows + "]}",
        StandardOpenOption.CREATE_NEW);
    if (!enabled || aborted) return;
    if (!validUrl(url)) {
      log.accept("Discord disabled: invalid webhook configuration (URL withheld)");
      return;
    }
    worker.submit(() -> post(id, text, url, timeout, log));
  }

  public static boolean claim(Path marker) throws IOException {
    try {
      Files.writeString(
          marker,
          "CLAIMED: delivery may be uncertain after a crash",
          StandardOpenOption.CREATE_NEW);
      return true;
    } catch (FileAlreadyExistsException e) {
      return false;
    }
  }

  private void post(String id, String text, String url, int timeout, Consumer<String> log) {
    Path marker = root.resolve(id + ".delivery");
    try {
      if (!claim(marker)) return;
      HttpClient client =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(Math.max(1, Math.min(30, timeout))))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      for (int attempt = 0; attempt < 3; attempt++) {
        var request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, Math.min(30, timeout))))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(text)))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          Files.writeString(marker, "DELIVERED");
          return;
        }
        if (status == 429 && attempt < 2) {
          double wait =
              Double.parseDouble(response.headers().firstValue("Retry-After").orElse("5"));
          Thread.sleep((long) (Math.max(1, Math.min(60, wait)) * 1000));
          continue;
        }
        Files.writeString(marker, "HTTP " + status + "; no automatic replay");
        log.accept("Discord result delivery failed: HTTP " + status);
        return;
      }
    } catch (Exception e) {
      try {
        Files.writeString(marker, "UNCERTAIN: no automatic retry to prevent duplicates");
      } catch (IOException ignored) {
      }
      log.accept("Discord delivery uncertain; see local delivery marker (secret withheld)");
    }
  }

  @Override
  public void close() {
    worker.shutdownNow();
  }
}
