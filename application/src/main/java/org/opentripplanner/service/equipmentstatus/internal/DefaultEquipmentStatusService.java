package org.opentripplanner.service.equipmentstatus.internal;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.service.equipmentstatus.EquipmentStatusRepository;
import org.opentripplanner.service.equipmentstatus.EquipmentStatusService;
import org.opentripplanner.service.equipmentstatus.model.EquipmentOutage;
import org.opentripplanner.service.equipmentstatus.model.PlannedEquipmentOutage;

/**
 * The single application-wide equipment status store. The updater replaces the immutable
 * snapshot atomically; readers see either the previous or the next snapshot, never a mix.
 * <p>
 * Staleness is enforced at read time: once the snapshot's age exceeds its {@code staleAfter},
 * every answer degrades to unknown rather than serving frozen data as current truth.
 */
@Singleton
public class DefaultEquipmentStatusService
  implements EquipmentStatusService, EquipmentStatusRepository {

  private record Snapshot(
    Map<String, EquipmentOutage> currentOutages,
    Map<String, List<PlannedEquipmentOutage>> plannedOutages,
    Instant asOf,
    Duration staleAfter
  ) {
    boolean fresh(Instant now) {
      return !now.isAfter(asOf.plus(staleAfter));
    }
  }

  private volatile Snapshot snapshot = null;

  @Inject
  public DefaultEquipmentStatusService() {}

  @Override
  public void update(
    Map<String, EquipmentOutage> currentOutages,
    Map<String, List<PlannedEquipmentOutage>> plannedOutages,
    Instant asOf,
    Duration staleAfter
  ) {
    this.snapshot = new Snapshot(
      Map.copyOf(currentOutages),
      Map.copyOf(plannedOutages),
      asOf,
      staleAfter
    );
  }

  @Override
  @Nullable
  public Boolean operational(String equipmentCode) {
    var current = freshSnapshot();
    if (current == null) {
      return null;
    }
    return !current.currentOutages().containsKey(equipmentCode);
  }

  @Override
  @Nullable
  public EquipmentOutage currentOutage(String equipmentCode) {
    var current = freshSnapshot();
    return current == null ? null : current.currentOutages().get(equipmentCode);
  }

  @Override
  public List<PlannedEquipmentOutage> plannedOutages(String equipmentCode) {
    var current = freshSnapshot();
    if (current == null) {
      return List.of();
    }
    return current.plannedOutages().getOrDefault(equipmentCode, List.of());
  }

  @Override
  public Set<String> inoperativeEquipmentCodes() {
    var current = freshSnapshot();
    return current == null ? Set.of() : current.currentOutages().keySet();
  }

  @Nullable
  private Snapshot freshSnapshot() {
    var current = snapshot;
    return current != null && current.fresh(Instant.now()) ? current : null;
  }
}
