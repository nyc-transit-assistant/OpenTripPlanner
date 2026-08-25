package org.opentripplanner.updater.trip.gtfs.updater.http;

import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.opentripplanner.updater.spi.PollingGraphUpdater;
import org.opentripplanner.updater.spi.UpdateResult;
import org.opentripplanner.updater.trip.gtfs.GtfsRealTimeTripUpdateAdapter;
import org.opentripplanner.updater.trip.gtfs.UnmatchedTripCanceler;
import org.opentripplanner.updater.trip.gtfs.interpolation.BackwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.interpolation.ForwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.updater.TripUpdateGraphWriterRunnable;
import org.opentripplanner.updater.trip.metrics.BatchTripUpdateMetrics;
import org.opentripplanner.updater.trip.metrics.TripUpdateMetrics;
import org.opentripplanner.utils.tostring.ToStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Update OTP stop timetables from some a GTFS-RT source.
 */
public class PollingTripUpdater extends PollingGraphUpdater {

  private static final Logger LOG = LoggerFactory.getLogger(PollingTripUpdater.class);

  private final HttpTripUpdateSource updateSource;
  private final GtfsRealTimeTripUpdateAdapter adapter;

  /**
   * Static GTFS feeds that this real-time updater applies updates to. Each incoming trip
   * update is matched against these feeds in order.
   */
  private final List<String> feedIds;

  /**
   * Defines when delays are propagated to next stops.
   */
  private final ForwardsDelayPropagationType forwardsDelayPropagationType;
  /**
   * Defines when delays are propagated to previous stops and if these stops are given the NO_DATA
   * flag.
   */
  private final BackwardsDelayPropagationType backwardsDelayPropagationType;
  private final Consumer<UpdateResult> recordMetrics;
  private final Consumer<Throwable> recordCrash;

  /**
   * Set only if we should attempt to match the trip_id from other data in TripDescriptor
   */
  private final boolean fuzzyTripMatching;

  /**
   * If true, look up realtime trip ids as suffixes of the static GTFS trip id
   * (e.g. for MTA NYC Subway).
   */
  private final boolean partialTripIdMatching;

  /** If true, resolve realtime trips by train number (trip_short_name), e.g. for MTA MNR. */
  private final boolean trainNumberMatching;
  private final String trainNumberSynthesisIdPrefix;
  private final String trainNumberSynthesisRouteId;

  /** See {@code PollingTripUpdaterConfig}: shared-feed updaters must never clear unscoped. */
  private final boolean scopedFullDatasetClear;

  /** Ghost-trip cancellation state; null when the feature is disabled. Long-lived so the
   * matched-trip memory survives polling cycles. */
  @Nullable
  private final UnmatchedTripCanceler unmatchedTripCanceler;

  public PollingTripUpdater(
    PollingTripUpdaterParameters parameters,
    GtfsRealTimeTripUpdateAdapter adapter
  ) {
    super(parameters);
    // Create update streamer from preferences
    this.feedIds = Objects.requireNonNull(parameters.feedIds());
    this.updateSource = new HttpTripUpdateSource(parameters);
    this.forwardsDelayPropagationType = parameters.forwardsDelayPropagationType();
    this.backwardsDelayPropagationType = parameters.backwardsDelayPropagationType();
    this.adapter = adapter;
    this.fuzzyTripMatching = parameters.fuzzyTripMatching();
    this.scopedFullDatasetClear = parameters.scopedFullDatasetClear();
    this.partialTripIdMatching = parameters.partialTripIdMatching();
    this.trainNumberMatching = parameters.trainNumberMatching();
    this.trainNumberSynthesisIdPrefix = parameters.trainNumberSynthesisIdPrefix();
    this.trainNumberSynthesisRouteId = parameters.trainNumberSynthesisRouteId();

    this.unmatchedTripCanceler = parameters.cancelUnmatchedAfterElapsedStops() > 0
      ? new UnmatchedTripCanceler(
          parameters.cancelUnmatchedAfterElapsedStops(),
          TripUpdateMetrics.updaterLabel(parameters.url())
        )
      : null;

    var batchMetrics = BatchTripUpdateMetrics.createBatch(parameters);
    this.recordMetrics = batchMetrics != null ? batchMetrics::setGauges : TripUpdateMetrics.NOOP;
    this.recordCrash = batchMetrics != null ? batchMetrics::recordCrash : ignored -> {};

    LOG.info("Creating stop time updater running every {} : {}", pollingPeriod(), updateSource);
  }

  /**
   * Repeatedly makes blocking calls to an UpdateStreamer to retrieve new stop time updates, and
   * applies those updates to the graph.
   */
  @Override
  public void runPolling() throws InterruptedException, ExecutionException {
    // Get update lists from update source
    List<TripUpdate> updates = updateSource.getUpdates();
    var incrementality = updateSource.incrementalityOfLastUpdates();

    if (updates != null) {
      // Handle trip updates via graph writer runnable
      TripUpdateGraphWriterRunnable runnable = new TripUpdateGraphWriterRunnable(
        adapter,
        fuzzyTripMatching,
        partialTripIdMatching,
        trainNumberMatching,
        trainNumberSynthesisIdPrefix,
        trainNumberSynthesisRouteId,
        forwardsDelayPropagationType,
        backwardsDelayPropagationType,
        incrementality,
        updates,
        updateSource.tripReplacementPeriodsOfLastUpdates(),
        scopedFullDatasetClear,
        feedIds,
        unmatchedTripCanceler,
        recordMetrics,
        recordCrash
      );
      updateGraph(runnable);
    }
  }

  @Override
  public String toString() {
    return ToStringBuilder.of(this.getClass())
      .addObj("updateSource", updateSource)
      .addCol("feedIds", feedIds)
      .addBool("fuzzyTripMatching", fuzzyTripMatching)
      .toString();
  }
}
