package dev.crystal.events;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ResultsTest {
  @TempDir Path root;

  @Test
  void escapesJsonAndSuppressesDiscordMentions() {
    String payload = Results.payload("Player \"name\"\\\n@everyone\t");
    assertTrue(payload.contains("\"allowed_mentions\":{\"parse\":[]}"));
    assertTrue(payload.contains("\\\"name\\\"\\\\\\n"));
    assertFalse(payload.contains("\n"));
  }

  @Test
  void webhookClaimSurvivesNewInstancesAndCannotBeReplayed() throws Exception {
    Path marker = root.resolve("event.delivery");
    assertTrue(Results.claim(marker));
    assertFalse(Results.claim(marker));
    assertTrue(Files.readString(marker).contains("CLAIMED"));
  }

  @Test
  void onlyDiscordHttpsWebhookEndpointsAreAccepted() {
    assertTrue(Results.validUrl("https://discord.com/api/webhooks/123/fake_token"));
    for (String s :
        List.of(
            "http://discord.com/api/webhooks/123/x",
            "https://discord.com.evil.test/api/webhooks/123/x",
            "https://localhost/api/webhooks/123/x",
            "https://discord.com:444/api/webhooks/123/x",
            "https://discord.com/api/webhooks/123/x?wait=true",
            "not a url")) assertFalse(Results.validUrl(s));
  }

  @Test
  void disabledWebhookStillPersistsFullResultsAndNoDeliveryClaim() throws Exception {
    Tournament t = new Tournament();
    t.open(false, false);
    t.join(UUID.randomUUID(), "Test");
    try (Results r = new Results(root.toFile())) {
      r.save(t, false, false, "", 10, s -> fail(s));
    }
    assertTrue(Files.exists(root.resolve(t.eventId + ".txt")));
    assertTrue(Files.readString(root.resolve(t.eventId + ".json")).contains("survivalSeconds"));
    assertFalse(Files.exists(root.resolve(t.eventId + ".delivery")));
  }
}
