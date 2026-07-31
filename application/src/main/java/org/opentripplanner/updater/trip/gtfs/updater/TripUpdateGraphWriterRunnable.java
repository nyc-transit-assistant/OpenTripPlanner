package org.opentripplanner.updater.trip.gtfs.updater;

import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.opentripplanner.updater.GraphWriterRunnable;
import org.opentripplanner.updater.RealTimeUpdateContext;
import org.opentripplanner.updater.spi.UpdateResult;
import org.opentripplanner.updater.trip.UpdateIncrementality;
import org.opentripplanner.updater.trip.gtfs.BackwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.ForwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.GtfsRealTimeTripUpdateAdapter;
import org.opentripplanner.updater.trip.gtfs.GtfsRealtimePartialTripIdMatcher;
import org.opentripplanner.updater.trip.gtfs.TripReplacementPeriod;

public class TripUpdateGraphWriterRunnable implements GraphWriterRunnable {

  private final UpdateIncrementality updateIncrementality;

  /**
   * The list with updates to apply to the graph
   */
  private final List<TripUpdate> updates;

  private final boolean fuzzyTripMatching;
  private final boolean partialTripIdMatching;

  private final ForwardsDelayPropagationType forwardsDelayPropagationType;
  private final BackwardsDelayPropagationType backwardsDelayPropagationType;

  /**
   * Trip replacement periods extracted from the feed message header (NYCT extension), or
   * an empty list if the feed doesn't carry the extension. When non-empty, scheduled trips
   * on the listed routes that are not present in this update batch will be cancelled.
   */
  private final List<TripReplacementPeriod> tripReplacementPeriods;

  private final List<String> feedIds;
  private final boolean scopedFullDatasetClear;
  private final Consumer<UpdateResult> sendMetrics;
  private final GtfsRealTimeTripUpdateAdapter adapter;

  public TripUpdateGraphWriterRunnable(
    GtfsRealTimeTripUpdateAdapter adapter,
    boolean fuzzyTripMatching,
    boolean partialTripIdMatching,
    ForwardsDelayPropagationType forwardsDelayPropagationType,
    BackwardsDelayPropagationType backwardsDelayPropagationType,
    UpdateIncrementality updateIncrementality,
    List<TripUpdate> updates,
    List<TripReplacementPeriod> tripReplacementPeriods,
    boolean scopedFullDatasetClear,
    List<String> feedIds,
    Consumer<UpdateResult> sendMetrics
  ) {
    this.adapter = adapter;
    this.fuzzyTripMatching = fuzzyTripMatching;
    this.partialTripIdMatching = partialTripIdMatching;
    this.forwardsDelayPropagationType = forwardsDelayPropagationType;
    this.backwardsDelayPropagationType = backwardsDelayPropagationType;
    this.updateIncrementality = updateIncrementality;
    this.updates = Objects.requireNonNull(updates);
    this.tripReplacementPeriods = List.copyOf(Objects.requireNonNull(tripReplacementPeriods));
    this.scopedFullDatasetClear = scopedFullDatasetClear;
    this.feedIds = List.copyOf(Objects.requireNonNull(feedIds));
    if (this.feedIds.isEmpty()) {
      throw new IllegalArgumentException("feedIds must contain at least one feedId");
    }
    this.sendMetrics = sendMetrics;
  }

  @Override
  public void run(RealTimeUpdateContext context) {
    var partialMatcher = partialTripIdMatching
      ? new GtfsRealtimePartialTripIdMatcher(context.transitService())
      : null;
    var result = adapter.applyTripUpdates(
      fuzzyTripMatching ? context.gtfsRealtimeFuzzyTripMatcher() : null,
      partialMatcher,
      forwardsDelayPropagationType,
      backwardsDelayPropagationType,
      updateIncrementality,
      updates,
      tripReplacementPeriods,
      scopedFullDatasetClear,
      feedIds
    );
    sendMetrics.accept(result);
  }
}
