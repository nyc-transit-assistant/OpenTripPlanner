package org.opentripplanner.updater.equipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class MtaEquipmentStatusUpdaterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId NY = ZoneId.of("America/New_York");
  // "Now" between the current outage (2024) and the planned window (far future).
  private static final Instant NOW = ZonedDateTime.of(2026, 8, 15, 12, 0, 0, 0, NY).toInstant();

  private static final String FEED = """
    [
      {
        "equipment": "ES448", "equipmenttype": "ES",
        "outagedate": "09/30/2024 12:05:00 PM",
        "estimatedreturntoservice": "08/31/2026 11:59:00 PM",
        "reason": "Capital Replacement", "isupcomingoutage": "N", "ismaintenanceoutage": "N"
      },
      {
        "equipment": "EL333", "equipmenttype": "EL",
        "outagedate": "08/17/2027 10:00:00 PM",
        "estimatedreturntoservice": "08/18/2027 06:00:00 AM",
        "reason": "Maintenance", "isupcomingoutage": "Y", "ismaintenanceoutage": "N"
      },
      {
        "equipment": "EL999", "equipmenttype": "EL",
        "outagedate": "01/01/2027 08:00:00 AM",
        "estimatedreturntoservice": "",
        "reason": "Planned Work", "isupcomingoutage": "N", "ismaintenanceoutage": "N"
      },
      {
        "equipment": "EL777", "equipmenttype": "EL",
        "outagedate": "not a date",
        "estimatedreturntoservice": null,
        "reason": "Repair", "isupcomingoutage": "N", "ismaintenanceoutage": "N"
      },
      { "equipment": "", "outagedate": "09/30/2024 12:05:00 PM" }
    ]
    """;

  @Test
  void parsesCurrentPlannedAndMalformedRows() throws Exception {
    var snapshot = MtaEquipmentStatusUpdater.parseSnapshot(MAPPER.readTree(FEED), NOW);

    // Genuinely out now: the escalator with a past start, and the unparseable-date repair row
    // (unknown start, N flag: trust the feed's current list).
    assertEquals(2, snapshot.currentOutages().size());
    var out = snapshot.currentOutages().get("ES448");
    assertEquals("Capital Replacement", out.reason());
    assertEquals(ZonedDateTime.of(2024, 9, 30, 12, 5, 0, 0, NY).toInstant(), out.since());
    assertEquals(
      ZonedDateTime.of(2026, 8, 31, 23, 59, 0, 0, NY).toInstant(),
      out.estimatedReturnToService()
    );
    assertNull(snapshot.currentOutages().get("EL777").since());

    // Planned: the Y-flagged row AND the future-dated row missing the flag (belt and suspenders
    // — a future start must never count as out today).
    assertEquals(2, snapshot.plannedOutages().size());
    var window = snapshot.plannedOutages().get("EL333").get(0);
    assertEquals("Maintenance", window.reason());
    assertEquals(ZonedDateTime.of(2027, 8, 17, 22, 0, 0, 0, NY).toInstant(), window.start());
    assertTrue(snapshot.plannedOutages().containsKey("EL999"));
    assertNull(snapshot.plannedOutages().get("EL999").get(0).end());
  }

  @Test
  void feedTimeParsing() {
    assertEquals(
      ZonedDateTime.of(2024, 9, 30, 12, 5, 0, 0, NY).toInstant(),
      MtaEquipmentStatusUpdater.parseFeedTime("09/30/2024 12:05:00 PM")
    );
    assertNull(MtaEquipmentStatusUpdater.parseFeedTime(null));
    assertNull(MtaEquipmentStatusUpdater.parseFeedTime("  "));
    assertNull(MtaEquipmentStatusUpdater.parseFeedTime("garbage"));
  }
}
