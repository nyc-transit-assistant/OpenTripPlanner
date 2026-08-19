package org.opentripplanner.updater.trip.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.opentripplanner.updater.spi.UpdateErrorType;
import org.opentripplanner.updater.spi.UpdateResult;
import org.opentripplanner.updater.spi.UpdateSuccess;
import org.opentripplanner.updater.trip.UrlUpdaterParameters;

/**
 * Records micrometer metrics for trip updaters that send batches of updates, for example GTFS-RT
 * via HTTP.
 * <p>
 * It records the most recent trip update as gauges, and — because gauges freeze at their last
 * value when an apply pass dies before recording (a frozen gauge and a healthy gauge look
 * identical, which once hid 45 minutes of dead NJT-rail polls) — it also keeps monotonic
 * counters and last-attempt/last-success timestamps. {@code rate(applied_total)} going to zero
 * and {@code time() - last_attempt_epoch} growing are both visible regardless of how a poll
 * died, and {@link #recordCrash} makes an exception in the apply pass itself a first-class
 * signal instead of a silent gap.
 */
public class BatchTripUpdateMetrics extends TripUpdateMetrics {

  /**
   * Null when the actuator API is off — callers fall back to no-op consumers, matching the
   * behavior of {@link TripUpdateMetrics#batch}.
   */
  public static BatchTripUpdateMetrics createBatch(UrlUpdaterParameters parameters) {
    return org.opentripplanner.framework.application.OTPFeature.ActuatorAPI.isOn()
      ? new BatchTripUpdateMetrics(parameters)
      : null;
  }

  protected static final String METRICS_PREFIX = "batch.trip.updates";
  private final AtomicInteger successfulGauge;
  private final AtomicInteger failureGauge;
  private final AtomicInteger warningsGauge;
  private final Map<UpdateErrorType, AtomicInteger> failuresByType = new HashMap<>();
  private final Map<UpdateSuccess.WarningType, AtomicInteger> warningsByType = new HashMap<>();

  private final Counter attemptsCounter;
  private final Counter appliedCounter;
  private final Counter rejectedCounter;
  private final Counter crashedCounter;
  private final Map<UpdateErrorType, Counter> rejectedByType = new HashMap<>();
  private final AtomicLong lastAttemptEpoch = new AtomicLong(0);
  private final AtomicLong lastSuccessEpoch = new AtomicLong(0);

  public BatchTripUpdateMetrics(UrlUpdaterParameters parameters) {
    super(parameters);
    this.successfulGauge = getGauge(
      "successful",
      "Trip updates that were successfully applied at the most recent update"
    );
    this.failureGauge = getGauge(
      "failed",
      "Trip updates that failed to apply at the most recent update"
    );

    this.warningsGauge = getGauge(
      "warnings",
      "Number of warnings when successfully applying trip updates"
    );
    this.attemptsCounter = getCounter(
      "attempts",
      "Apply passes recorded, including ones that crashed"
    );
    this.appliedCounter = getCounter("applied", "Trip updates successfully applied, cumulative");
    this.rejectedCounter = getCounter("rejected", "Trip updates that failed to apply, cumulative");
    this.crashedCounter = getCounter(
      "crashed",
      "Apply passes that threw before recording a result — the gauge-freeze case"
    );
    Gauge.builder(METRICS_PREFIX + ".last_attempt_epoch", lastAttemptEpoch::get)
      .description("Epoch seconds of the most recent recorded apply pass (crashed or not)")
      .tags(baseTags)
      .register(Metrics.globalRegistry);
    Gauge.builder(METRICS_PREFIX + ".last_success_epoch", lastSuccessEpoch::get)
      .description("Epoch seconds of the most recent apply pass that recorded a result")
      .tags(baseTags)
      .register(Metrics.globalRegistry);
  }

  public void setGauges(UpdateResult result) {
    long now = System.currentTimeMillis() / 1000;
    lastAttemptEpoch.set(now);
    lastSuccessEpoch.set(now);
    attemptsCounter.increment();
    appliedCounter.increment(result.successful());
    rejectedCounter.increment(result.failed());
    for (var errorType : result.failures().keySet()) {
      rejectedByType
        .computeIfAbsent(errorType, t ->
          getCounter(
            "rejected_by_type",
            "Trip updates that failed to apply, cumulative by error type",
            Tag.of("errorType", t.name())
          )
        )
        .increment(result.failures().get(errorType).size());
    }

    this.successfulGauge.set(result.successful());
    this.failureGauge.set(result.failed());
    this.warningsGauge.set(result.warnings().size());

    setFailureTypes(result);

    setWarnings(result);
  }

  /**
   * The apply pass threw before producing an {@link UpdateResult}. Without this, nothing is
   * recorded for the poll and every gauge silently keeps its previous value.
   */
  public void recordCrash(Throwable t) {
    lastAttemptEpoch.set(System.currentTimeMillis() / 1000);
    attemptsCounter.increment();
    crashedCounter.increment();
  }

  private void setWarnings(UpdateResult result) {
    // we have to set the warnings from the previous update to zero
    Set.copyOf(warningsByType.values()).forEach(i -> i.set(0));

    for (var warningType : result.warnings()) {
      var counter = warningsByType.get(warningType);
      if (Objects.isNull(counter)) {
        counter = getGauge(
          "warning_type",
          "Warning types of the most recent update",
          Tag.of("warningType", warningType.name())
        );
        warningsByType.put(warningType, counter);
      }
      counter.getAndIncrement();
    }
  }

  private void setFailureTypes(UpdateResult result) {
    for (var errorType : result.failures().keySet()) {
      var counter = failuresByType.get(errorType);
      if (Objects.isNull(counter)) {
        counter = getGauge(
          "failure_type",
          "Failure types of the most recent update",
          Tag.of("errorType", errorType.name())
        );
        failuresByType.put(errorType, counter);
      }
      counter.set(result.failures().get(errorType).size());
    }

    // every counter that was set in one of the previous rounds but not in this one
    // needs to be explicitly set to zero, otherwise the previous count will persist across
    // batches. this would of course lead to wrong totals.
    var toZero = new HashSet<>(failuresByType.keySet());
    toZero.removeAll(result.failures().keySet());

    for (var keyToZero : toZero) {
      failuresByType.get(keyToZero).set(0);
    }
  }

  private Counter getCounter(String name, String description, Tag... tags) {
    var finalTags = Tags.concat(Arrays.stream(tags).toList(), baseTags);
    return Counter.builder(METRICS_PREFIX + "." + name)
      .description(description)
      .tags(finalTags)
      .register(Metrics.globalRegistry);
  }

  private AtomicInteger getGauge(String name, String description, Tag... tags) {
    var finalTags = Tags.concat(Arrays.stream(tags).toList(), baseTags);
    var atomicInt = new AtomicInteger(0);
    Gauge.builder(METRICS_PREFIX + "." + name, atomicInt::get)
      .description(description)
      .tags(finalTags)
      .register(Metrics.globalRegistry);
    return atomicInt;
  }
}
