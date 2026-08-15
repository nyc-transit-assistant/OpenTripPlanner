package org.opentripplanner.service.equipmentstatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentripplanner.service.equipmentstatus.internal.DefaultEquipmentStatusService;
import org.opentripplanner.service.equipmentstatus.model.EquipmentOutage;
import org.opentripplanner.service.equipmentstatus.model.PlannedEquipmentOutage;

class DefaultEquipmentStatusServiceTest {

  private static final EquipmentOutage OUTAGE = new EquipmentOutage("Repair", null, null);
  private static final PlannedEquipmentOutage PLANNED = new PlannedEquipmentOutage(
    Instant.parse("2030-01-01T03:00:00Z"),
    Instant.parse("2030-01-01T11:00:00Z"),
    "Maintenance"
  );

  @Test
  void unknownBeforeFirstUpdate() {
    var service = new DefaultEquipmentStatusService();
    assertNull(service.operational("EL358"));
    assertNull(service.currentOutage("EL358"));
    assertTrue(service.plannedOutages("EL358").isEmpty());
  }

  @Test
  void freshSnapshotAnswersDefinitively() {
    var service = new DefaultEquipmentStatusService();
    service.update(
      Map.of("EL100", OUTAGE),
      Map.of("EL200", List.of(PLANNED)),
      Instant.now(),
      Duration.ofMinutes(30)
    );
    assertFalse(service.operational("EL100"));
    assertEquals(OUTAGE, service.currentOutage("EL100"));
    // Absent from the official outage list means in service — a positive answer, not unknown.
    assertTrue(service.operational("EL358"));
    assertNull(service.currentOutage("EL358"));
    assertEquals(List.of(PLANNED), service.plannedOutages("EL200"));
  }

  @Test
  void staleSnapshotDegradesToUnknown() {
    var service = new DefaultEquipmentStatusService();
    service.update(
      Map.of("EL100", OUTAGE),
      Map.of(),
      Instant.now().minus(Duration.ofMinutes(45)),
      Duration.ofMinutes(30)
    );
    // A dead feed must degrade to unknown, never report a broken unit as working.
    assertNull(service.operational("EL100"));
    assertNull(service.operational("EL358"));
    assertNull(service.currentOutage("EL100"));
    assertTrue(service.plannedOutages("EL100").isEmpty());
  }
}
