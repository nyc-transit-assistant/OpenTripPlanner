package org.opentripplanner.updater.trip.gtfs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transit.model.framework.DataValidationException;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.timetable.RealTimeTripUpdate;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.repository.MutableTimetableSnapshot;
import org.opentripplanner.transit.service.TransitEditorService;
import org.opentripplanner.updater.spi.DataValidationExceptionMapper;
import org.opentripplanner.updater.spi.UpdateError;
import org.opentripplanner.updater.spi.UpdateSuccess;
import org.opentripplanner.updater.trip.TripUpdateApplier;
import org.opentripplanner.utils.time.ServiceDateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cancels scheduled trips that should already be running but have never been matched by this
 * realtime feed today — "ghost buses". Once the scheduled departure of a trip's first
 * {@code elapsedStops} stops has passed and no trip update has referenced the trip this service
 * day, the trip is cancelled in the realtime snapshot so riders are not shown a departure the
 * operator gives no evidence of running.
 * <p>
 * The matched-trip memory accumulates across polling cycles (a trip that appeared earlier and
 * then dropped out of the feed — mid-route tracking loss is routine for bus AVL — is never
 * cancelled). Full-dataset feeds clear the realtime buffer every cycle, so cancellations are
 * re-asserted on each sweep; the moment a trip finally appears in the feed it stops being a
 * candidate and its next scheduled state applies — recovery is automatic. Only trips still
 * inside their scheduled running window are cancelled: a never-matched trip whose last stop has
 * passed can no longer mislead anyone, and skipping it keeps the sweep bounded to the trips
 * currently relevant.
 * <p>
 * Owned by the polling updater (one per updater lifetime, so the memory survives polls);
 * mutated only on the graph-writer thread, which executes runnables serially.
 */
public class UnmatchedTripCanceler {

  private static final Logger LOG = LoggerFactory.getLogger(UnmatchedTripCanceler.class);
  private static final String GAUGE_NAME = "trip_updates_unmatched_elapsed_cancelled";
  private static final String COUNTER_NAME = "trip_updates_unmatched_elapsed_trips";

  private final int elapsedStops;
  private final String updaterLabel;

  private final Map<LocalDate, Set<FeedScopedId>> matchedByDay = new HashMap<>();
  private final Map<LocalDate, Set<FeedScopedId>> countedByDay = new HashMap<>();
  private final Map<LocalDate, List<Candidate>> candidatesByDay = new HashMap<>();
  private final Map<String, AtomicInteger> gaugesByFeed = new HashMap<>();
  private final Map<String, Counter> countersByFeed = new HashMap<>();

  /** A scheduled trip with the precomputed times the sweep needs, sorted by cutoff. */
  private record Candidate(
    TripPattern pattern,
    Trip trip,
    int cutoffSeconds,
    int lastArrivalSeconds
  ) {}

  public UnmatchedTripCanceler(int elapsedStops, String updaterLabel) {
    if (elapsedStops < 1) {
      throw new IllegalArgumentException("elapsedStops must be at least 1");
    }
    this.elapsedStops = elapsedStops;
    this.updaterLabel = updaterLabel;
  }

  /**
   * Run one sweep: fold this batch's matched trip ids into the per-day memory, then cancel every
   * scheduled trip of the managed feeds whose first {@code elapsedStops} stops have elapsed
   * without the trip ever having been matched today. Yesterday's service date is scanned too so
   * trips with stop times past 24:00 are covered.
   */
  public void sweep(
    TransitEditorService transitEditorService,
    MutableTimetableSnapshot buffer,
    Set<FeedScopedId> seenTripIds,
    List<String> feedIds,
    Instant now,
    LocalDate today,
    List<UpdateSuccess> successes,
    List<UpdateError> errors
  ) {
    var yesterday = today.minusDays(1);
    var keep = Set.of(yesterday, today);
    matchedByDay.keySet().retainAll(keep);
    countedByDay.keySet().retainAll(keep);
    candidatesByDay.keySet().retainAll(keep);

    matchedByDay.computeIfAbsent(today, d -> new HashSet<>()).addAll(seenTripIds);
    Set<FeedScopedId> matched = new HashSet<>();
    matchedByDay.values().forEach(matched::addAll);

    Map<String, Integer> cancelledByFeed = new HashMap<>();
    var zone = transitEditorService.getTimeZone();

    for (LocalDate serviceDate : List.of(yesterday, today)) {
      var candidates = candidatesByDay.computeIfAbsent(serviceDate, date ->
        buildCandidates(transitEditorService, feedIds, date)
      );
      var startOfService = ServiceDateUtils.asStartOfService(serviceDate, zone).toInstant();
      var counted = countedByDay.computeIfAbsent(serviceDate, d -> new HashSet<>());

      for (var candidate : candidates) {
        // Sorted by cutoff: everything after this has not yet elapsed.
        if (startOfService.plusSeconds(candidate.cutoffSeconds()).isAfter(now)) {
          break;
        }
        // The scheduled run is entirely in the past — nothing left to cancel.
        if (!startOfService.plusSeconds(candidate.lastArrivalSeconds()).isAfter(now)) {
          continue;
        }
        var tripId = candidate.trip().getId();
        if (matched.contains(tripId)) {
          continue;
        }
        try {
          var tripTimes = candidate.pattern().getScheduledTimetable().getTripTimes(tripId);
          if (tripTimes == null) {
            continue;
          }
          var builder = tripTimes.createRealTimeFromScheduledTimes();
          builder.withCanceled();
          var result = TripUpdateApplier.apply(
            buffer,
            RealTimeTripUpdate.of(candidate.pattern(), builder.build(), serviceDate)
              .withRevertPreviousRealTimeUpdates(true)
              .build()
          );
          successes.add(result);
          cancelledByFeed.merge(tripId.getFeedId(), 1, Integer::sum);
          if (counted.add(tripId)) {
            counterFor(tripId.getFeedId()).increment();
          }
        } catch (DataValidationException e) {
          errors.add(DataValidationExceptionMapper.map(e).toError());
        }
      }
    }

    for (String feedId : feedIds) {
      gaugeFor(feedId).set(cancelledByFeed.getOrDefault(feedId, 0));
    }
  }

  private List<Candidate> buildCandidates(
    TransitEditorService transitEditorService,
    List<String> feedIds,
    LocalDate serviceDate
  ) {
    var serviceIds = transitEditorService
      .getTripCalendars()
      .listServiceIdsOnServiceDate(serviceDate);
    List<Candidate> candidates = new ArrayList<>();
    if (serviceIds.isEmpty()) {
      return candidates;
    }
    var managedFeeds = Set.copyOf(feedIds);
    for (var route : transitEditorService.listRoutes()) {
      if (!managedFeeds.contains(route.getId().getFeedId())) {
        continue;
      }
      for (var pattern : transitEditorService.findPatterns(route)) {
        var timetable = pattern.getScheduledTimetable();
        for (var trip : pattern.scheduledTripsAsStream().toList()) {
          if (!serviceIds.contains(trip.getServiceId())) {
            continue;
          }
          var tripTimes = timetable.getTripTimes(trip.getId());
          if (tripTimes == null) {
            continue;
          }
          int cutoffStop = Math.min(elapsedStops, tripTimes.getNumStops()) - 1;
          candidates.add(
            new Candidate(
              pattern,
              trip,
              tripTimes.getDepartureTime(cutoffStop),
              tripTimes.getArrivalTime(tripTimes.getNumStops() - 1)
            )
          );
        }
      }
    }
    candidates.sort(Comparator.comparingInt(Candidate::cutoffSeconds));
    LOG.info(
      "[{}] unmatched-trip canceler: indexed {} scheduled trips for {}",
      updaterLabel,
      candidates.size(),
      serviceDate
    );
    return candidates;
  }

  private AtomicInteger gaugeFor(String feedId) {
    return gaugesByFeed.computeIfAbsent(feedId, f -> {
      var value = new AtomicInteger();
      Gauge.builder(GAUGE_NAME, value, AtomicInteger::get)
        .description(
          "Scheduled trips currently cancelled because their first N stops elapsed with no realtime match"
        )
        .tag("updater", updaterLabel)
        .tag("feedId", f)
        .register(Metrics.globalRegistry);
      return value;
    });
  }

  private Counter counterFor(String feedId) {
    return countersByFeed.computeIfAbsent(feedId, f ->
      Counter.builder(COUNTER_NAME)
        .description(
          "Distinct scheduled trips cancelled for having no realtime match after their first N stops elapsed"
        )
        .tag("updater", updaterLabel)
        .tag("feedId", f)
        .register(Metrics.globalRegistry)
    );
  }
}
