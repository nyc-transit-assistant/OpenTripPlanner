package org.opentripplanner.model.plan.walkstep.verticaltransportation;

import javax.annotation.Nullable;
import org.opentripplanner.service.streetdetails.model.Level;

/**
 * Represents information about a single use of an elevator related to
 * {@link org.opentripplanner.model.plan.walkstep.WalkStep}.
 */
public final class ElevatorUse extends VerticalTransportationUse {

  @Nullable
  private final String equipmentCode;

  public ElevatorUse(
    @Nullable Level from,
    @Nullable Level to,
    VerticalDirection verticalDirection
  ) {
    this(from, to, verticalDirection, null);
  }

  public ElevatorUse(
    @Nullable Level from,
    @Nullable Level to,
    VerticalDirection verticalDirection,
    @Nullable String equipmentCode
  ) {
    super(from, to, verticalDirection);
    this.equipmentCode = equipmentCode;
  }

  /**
   * The operator's unit code parsed from the elevator's signposted name ("EL359 — ..."), matching
   * the station equipment registry; null when the elevator carries no code (plain OSM elevators).
   */
  @Nullable
  public String equipmentCode() {
    return equipmentCode;
  }
}
