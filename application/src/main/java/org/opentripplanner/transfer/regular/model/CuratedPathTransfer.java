package org.opentripplanner.transfer.regular.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.opentripplanner.routing.cost.CostLimit;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.transit.model.site.StopLocation;

/**
 * A hand-curated station transfer loaded from the transfer-links file rather than generated from
 * the street network. Cross-feed station complexes (subway–PATH, subway–commuter rail,
 * bus-terminal–subway) share no GTFS dataset, so no transfers.txt or pathways.txt can span them;
 * these links carry a signposted walk time instead of a street path.
 * <p>
 * The duration is fixed by the curator — request walk speed does not scale it, matching the GTFS
 * {@code min_transfer_time} semantics of a minimum time rather than a distance. A separate
 * wheelchair duration applies to wheelchair requests; when absent the transfer is not
 * wheelchair-accessible and is omitted from wheelchair searches entirely.
 * <p>
 * The wheelchair path may further depend on specific elevators ({@code wheelchairElevators},
 * operator unit codes): if the realtime equipment feed reports ANY of them out of service the
 * transfer is omitted from wheelchair searches, exactly as {@code ElevatorBoardEdge} blocks
 * dead elevators in street searches. The request's inoperative set is empty when status is
 * unknown (no updater, stale feed), so gating fails open — and because the set participates in
 * the raptor transfer cache key via {@code WheelchairRequest}, outage changes recompute the
 * wheelchair transfer index without any explicit invalidation.
 */
public class CuratedPathTransfer extends PathTransfer {

  /** Marker for "this transfer cannot be made in a wheelchair". */
  public static final int NOT_WHEELCHAIR_ACCESSIBLE = -1;

  private final int durationSeconds;
  private final int wheelchairDurationSeconds;

  /** Operator unit codes the wheelchair path requires; empty when it needs no elevator. */
  private final List<String> wheelchairElevators;

  public CuratedPathTransfer(
    StopLocation from,
    StopLocation to,
    double distanceMeters,
    int durationSeconds,
    int wheelchairDurationSeconds
  ) {
    this(from, to, distanceMeters, durationSeconds, wheelchairDurationSeconds, List.of());
  }

  public CuratedPathTransfer(
    StopLocation from,
    StopLocation to,
    double distanceMeters,
    int durationSeconds,
    int wheelchairDurationSeconds,
    List<String> wheelchairElevators
  ) {
    super(from, to, distanceMeters, null, EnumSet.of(StreetMode.WALK));
    this.durationSeconds = durationSeconds;
    this.wheelchairDurationSeconds = wheelchairDurationSeconds;
    this.wheelchairElevators = List.copyOf(wheelchairElevators);
  }

  public int durationSeconds() {
    return durationSeconds;
  }

  public boolean wheelchairAccessible() {
    return wheelchairDurationSeconds != NOT_WHEELCHAIR_ACCESSIBLE;
  }

  public List<String> wheelchairElevators() {
    return wheelchairElevators;
  }

  @Override
  public Optional<DefaultRaptorTransfer> asRaptorTransfer(StreetSearchRequest request) {
    int duration = request.wheelchairEnabled() ? wheelchairDurationSeconds : durationSeconds;
    if (duration == NOT_WHEELCHAIR_ACCESSIBLE) {
      return Optional.empty();
    }
    if (request.wheelchairEnabled() && !wheelchairElevators.isEmpty()) {
      var inoperative = request.wheelchair().inoperativeEquipment();
      if (wheelchairElevators.stream().anyMatch(inoperative::contains)) {
        return Optional.empty();
      }
    }
    return Optional.of(
      new DefaultRaptorTransfer(
        to.getIndex(),
        duration,
        CostLimit.toRaptorCostWholeSeconds((double) duration * request.walk().reluctance()),
        this
      )
    );
  }
}
