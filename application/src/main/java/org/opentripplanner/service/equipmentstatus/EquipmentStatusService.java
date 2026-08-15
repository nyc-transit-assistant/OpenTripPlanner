package org.opentripplanner.service.equipmentstatus;

import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.service.equipmentstatus.model.EquipmentOutage;
import org.opentripplanner.service.equipmentstatus.model.PlannedEquipmentOutage;

/**
 * Read side of the realtime elevator/escalator status store, keyed by the operator's unit code
 * (e.g. {@code EL358}) — the same code carried by {@code StationEquipment.code} and
 * {@code ElevatorUse.equipmentCode}.
 */
public interface EquipmentStatusService {
  /**
   * Whether the unit is currently in service. Null means unknown: no updater configured, no
   * successful poll yet, or the last successful poll is older than the staleness cutoff. A dead
   * feed must degrade to "unknown", never to "everything is fine".
   */
  @Nullable
  Boolean operational(String equipmentCode);

  /** The current outage for the unit, or null when it is in service (or status is unknown). */
  @Nullable
  EquipmentOutage currentOutage(String equipmentCode);

  /** Scheduled future outage windows for the unit; empty when none (or status is unknown). */
  List<PlannedEquipmentOutage> plannedOutages(String equipmentCode);

  /**
   * Codes of every unit currently out of service. Empty when status is unknown (no updater,
   * stale feed) — so consumers that BLOCK on membership degrade to "no blocking", never to
   * blocking on frozen data.
   */
  Set<String> inoperativeEquipmentCodes();
}
