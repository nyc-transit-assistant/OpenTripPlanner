package org.opentripplanner.service.equipmentstatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.opentripplanner.service.equipmentstatus.model.EquipmentOutage;
import org.opentripplanner.service.equipmentstatus.model.PlannedEquipmentOutage;

/**
 * Write side of the realtime elevator/escalator status store. There is one instance for the
 * whole application: the equipment-status updater replaces the full snapshot on every
 * successful poll (the source feed is a complete outage list, so recoveries need no tombstone
 * handling) and request threads read it concurrently through {@link EquipmentStatusService}.
 */
public interface EquipmentStatusRepository {
  /**
   * Atomically replace the whole snapshot.
   *
   * @param currentOutages units currently out of service, keyed by unit code
   * @param plannedOutages scheduled future windows, keyed by unit code
   * @param asOf when this data was successfully fetched
   * @param staleAfter how long readers may trust this snapshot before degrading to unknown
   */
  void update(
    Map<String, EquipmentOutage> currentOutages,
    Map<String, List<PlannedEquipmentOutage>> plannedOutages,
    Instant asOf,
    Duration staleAfter
  );
}
