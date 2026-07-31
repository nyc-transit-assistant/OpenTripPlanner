package org.opentripplanner.updater.trip.gtfs;

import static org.opentripplanner.updater.spi.UpdateErrorType.NOT_IMPLEMENTED_UNSCHEDULED;
import static org.opentripplanner.updater.trip.UpdateIncrementality.FULL_DATASET;

import com.google.transit.realtime.GtfsRealtime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transit.model.framework.DataValidationException;
import org.opentripplanner.transit.model.timetable.RealTimeTripUpdate;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.repository.MutableTimetableSnapshot;
import org.opentripplanner.transit.service.TransitEditorService;
import org.opentripplanner.updater.spi.DataValidationExceptionMapper;
import org.opentripplanner.updater.spi.ResultLogger;
import org.opentripplanner.updater.spi.UpdateError;
import org.opentripplanner.updater.spi.UpdateException;
import org.opentripplanner.updater.spi.UpdateResult;
import org.opentripplanner.updater.spi.UpdateSuccess;
import org.opentripplanner.updater.trip.TripUpdateApplier;
import org.opentripplanner.updater.trip.UpdateIncrementality;
import org.opentripplanner.updater.trip.gtfs.interpolation.BackwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.interpolation.ForwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.model.TripUpdate;
import org.opentripplanner.utils.time.ServiceDateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Update-scoped object produced by {@link GtfsRealTimeTripUpdateAdapter#forUpdate}. Holds the
 * per-task collaborators (sub-handlers constructed with an update-scoped {@code TransitEditorService})
 * and applies GTFS-RT trip updates against the mutable timetable snapshot.
 */
public class GtfsRealTimeUpdateHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GtfsRealTimeUpdateHandler.class);

  private final MutableTimetableSnapshot buffer;
  private final TransitEditorService transitEditorService;
  private final Supplier<LocalDate> localDateNow;
  private final Supplier<Instant> instantNow;
  private final ScheduledTripHandler scheduledTripHandler;
  private final NewTripHandler addedTripHandler;
  private final CanceledTripHandler canceledTripHandler;
  private final DuplicatedTripHandler duplicatedTripHandler;

  GtfsRealTimeUpdateHandler(
    MutableTimetableSnapshot buffer,
    TransitEditorService transitEditorService,
    Supplier<LocalDate> localDateNow,
    Supplier<Instant> instantNow,
    ScheduledTripHandler scheduledTripHandler,
    NewTripHandler addedTripHandler,
    CanceledTripHandler canceledTripHandler,
    DuplicatedTripHandler duplicatedTripHandler
  ) {
    this.buffer = buffer;
    this.transitEditorService = transitEditorService;
    this.localDateNow = localDateNow;
    this.instantNow = instantNow;
    this.scheduledTripHandler = scheduledTripHandler;
    this.addedTripHandler = addedTripHandler;
    this.canceledTripHandler = canceledTripHandler;
    this.duplicatedTripHandler = duplicatedTripHandler;
  }

  /**
   * Method to apply a trip update list to the most recent version of the timetable snapshot.
   * <p>
   * A single GTFS-RT feed may be applied against multiple static GTFS feeds. Each incoming
   * trip update is matched to the static feed that contains its trip id; if no static feed
   * contains the trip (e.g. a NEW or ADDED trip), the first entry in {@code feedIds} is used
   * as the owning feed. For FULL_DATASET incrementality the buffer is cleared for every
   * managed feedId.
   * <p>
   * If {@code tripReplacementPeriods} is non-empty (NYCT extension), any scheduled trip on a
   * listed route whose origin departure falls within the replacement window and was not seen
   * in this update batch is cancelled — i.e. the realtime feed is treated as authoritative
   * within the window.
   *
   * @param backwardsDelayPropagationType Defines when delays are propagated to previous stops and
   *                                      if these stops are given the NO_DATA flag.
   * @param updateIncrementality          Determines the incrementality of the updates. FULL updates clear the buffer
   *                                      of all previous updates for the given feed ids.
   * @param updates                       GTFS-RT TripUpdate's that should be applied atomically
   * @param tripReplacementPeriods        Per-route windows (NYCT extension) within which the
   *                                      realtime feed is the authoritative source — scheduled
   *                                      trips not present in {@code updates} are cancelled
   * @param feedIds                       Static feed ids this real-time feed applies to (in priority order)
   */
  public UpdateResult applyTripUpdates(
    @Nullable GtfsRealtimeFuzzyTripMatcher fuzzyTripMatcher,
    @Nullable GtfsRealtimePartialTripIdMatcher partialTripIdMatcher,
    ForwardsDelayPropagationType forwardsDelayPropagationType,
    BackwardsDelayPropagationType backwardsDelayPropagationType,
    UpdateIncrementality updateIncrementality,
    List<GtfsRealtime.TripUpdate> updates,
    List<TripReplacementPeriod> tripReplacementPeriods,
    boolean scopedFullDatasetClear,
    List<String> feedIds
  ) {
    if (feedIds == null || feedIds.isEmpty()) {
      throw new IllegalArgumentException("feedIds must contain at least one feedId");
    }
    List<UpdateSuccess> successes = new ArrayList<>();
    List<UpdateError> errors = new ArrayList<>();
    Set<FeedScopedId> seenTripIds = new HashSet<>();
    int partialTripIdMatches = 0;
    int convertedScheduledToAdded = 0;
    int convertedScheduledToAddedDueToPatternDivergence = 0;

    // Inside an active trip_replacement_period the realtime feed is authoritative for the
    // covered route. If we can't resolve an RT trip to a static trip, treat it as ADDED rather
    // than dropping it with TRIP_NOT_FOUND.
    var nowInstant = instantNow.get();
    Set<String> coveredRouteIds = new HashSet<>();
    int expiredPeriods = 0;
    for (var period : tripReplacementPeriods) {
      if (period.endTime().isAfter(nowInstant)) {
        coveredRouteIds.add(period.routeId());
      } else {
        expiredPeriods++;
      }
    }
    Set<String> unresolvedRouteIds = new HashSet<>();

    if (updateIncrementality == FULL_DATASET) {
      if (scopedFullDatasetClear) {
        // Shared-feed mode: this updater is never authoritative for the whole feed, so an
        // unscoped clear is never permitted — with several per-line updaters writing to one
        // feedId (NYCT), an unscoped clear wipes the siblings' just-applied data and the last
        // writer wins. Scope = declared replacement periods (fresh or expired — expiry gates
        // cancellation authority, not ownership) ∪ routes present in this batch, so an updater
        // whose feed omits the NYCT header extension (the G at times) still clears exactly its
        // own slice. An empty scope clears nothing.
        Set<String> scopeRouteIds = new HashSet<>();
        for (var period : tripReplacementPeriods) {
          scopeRouteIds.add(period.routeId());
        }
        for (var u : updates) {
          if (u.hasTrip() && u.getTrip().hasRouteId() && !u.getTrip().getRouteId().isBlank()) {
            scopeRouteIds.add(u.getTrip().getRouteId());
          }
        }
        if (!scopeRouteIds.isEmpty()) {
          for (String feedId : feedIds) {
            Set<FeedScopedId> scoped = scopeRouteIds
              .stream()
              .map(rid -> new FeedScopedId(feedId, rid))
              .collect(Collectors.toSet());
            buffer.clear(feedId, scoped);
          }
        }
      } else if (coveredRouteIds.isEmpty()) {
        // Single authoritative source for this feedId — wipe the whole feed.
        for (String feedId : feedIds) {
          buffer.clear(feedId);
        }
      } else {
        // Multiple updaters share this feedId and each is authoritative only for its own slice
        // (NYCT publishes 8 per-line GTFS-RT URLs all writing to mta-subway). Without scoping,
        // each updater's full-dataset clear would wipe data just produced by the others.
        for (String feedId : feedIds) {
          Set<FeedScopedId> scopedRouteIds = coveredRouteIds
            .stream()
            .map(rid -> new FeedScopedId(feedId, rid))
            .collect(Collectors.toSet());
          buffer.clear(feedId, scopedRouteIds);
        }
      }
    }

    for (var rawTripUpdate : updates) {
      UpdateSuccess result;
      try {
        String resolvedFeedId = resolveFeedIdForTripUpdate(rawTripUpdate, feedIds);

        if (partialTripIdMatcher != null) {
          // Some agencies (notably MTA NYC Subway) emit a realtime trip_id that is a suffix
          // of the static GTFS trip_id. Rewrite the descriptor before fuzzy matching or
          // exact lookup so downstream code sees the full static id.
          var originalTripId = rawTripUpdate.getTrip().getTripId();
          var trip = partialTripIdMatcher.match(resolvedFeedId, rawTripUpdate.getTrip());
          if (!trip.getTripId().equals(originalTripId)) {
            partialTripIdMatches++;
          }
          rawTripUpdate = rawTripUpdate.toBuilder().setTrip(trip).build();
        }

        if (fuzzyTripMatcher != null) {
          var trip = fuzzyTripMatcher.match(resolvedFeedId, rawTripUpdate.getTrip());
          rawTripUpdate = rawTripUpdate.toBuilder().setTrip(trip).build();
          // Re-resolve in case fuzzy matching populated a previously-missing trip_id.
          resolvedFeedId = resolveFeedIdForTripUpdate(rawTripUpdate, feedIds);
        }

        if (rawTripUpdate.hasTrip() && rawTripUpdate.getTrip().hasTripId()) {
          var tripIdValue = rawTripUpdate.getTrip().getTripId();
          if (!tripIdValue.isBlank()) {
            seenTripIds.add(new FeedScopedId(resolvedFeedId, tripIdValue));
          }
        }

        // If this RT trip is on a route covered by an active replacement period and either
        // (a) doesn't resolve to a static trip after partial/fuzzy matching, or (b) resolves
        // but reports stops not present in the resolved static pattern, rewrite it to NEW.
        // The schedule is not authoritative inside the window — synthesizing a fresh pattern
        // from the RT data is more correct than forcing a divergent path through a stale one.
        if (rawTripUpdate.hasTrip() && rawTripUpdate.getTrip().hasRouteId()) {
          var trip = rawTripUpdate.getTrip();
          var resolvedTrip = trip.hasTripId() && !trip.getTripId().isBlank()
            ? transitEditorService.getTrip(new FeedScopedId(resolvedFeedId, trip.getTripId()))
            : null;
          boolean shouldRewrite = false;
          String rewriteReason = null;
          if (resolvedTrip == null) {
            unresolvedRouteIds.add(trip.getRouteId());
            shouldRewrite = coveredRouteIds.contains(trip.getRouteId());
            rewriteReason = "unresolved";
          } else if (
            coveredRouteIds.contains(trip.getRouteId()) &&
            rtStopsDivergeFromPattern(rawTripUpdate, resolvedTrip, resolvedFeedId)
          ) {
            shouldRewrite = true;
            rewriteReason = "stop-pattern-divergence";
          }
          if (shouldRewrite) {
            var rel = trip.getScheduleRelationship();
            boolean treatAsScheduled =
              !trip.hasScheduleRelationship() ||
              rel == GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED;
            if (treatAsScheduled) {
              // Unresolved trips become NEW (synthesize new trip + pattern). Resolved trips
              // with diverging stop patterns become REPLACEMENT (keep trip identity, replace
              // stop pattern). NEW would fail TRIP_ALREADY_EXISTS for the resolved case.
              var newRel = "unresolved".equals(rewriteReason)
                ? GtfsRealtime.TripDescriptor.ScheduleRelationship.NEW
                : GtfsRealtime.TripDescriptor.ScheduleRelationship.REPLACEMENT;
              var rewritten = trip.toBuilder().setScheduleRelationship(newRel).build();
              rawTripUpdate = rawTripUpdate.toBuilder().setTrip(rewritten).build();
              if ("unresolved".equals(rewriteReason)) {
                convertedScheduledToAdded++;
              } else {
                convertedScheduledToAddedDueToPatternDivergence++;
              }
            }
          }
        }

        var tripUpdate = new TripUpdate(resolvedFeedId, rawTripUpdate, localDateNow);
        tripUpdate.validate();

        result = applyUpdate(
          tripUpdate,
          updateIncrementality,
          backwardsDelayPropagationType,
          forwardsDelayPropagationType
        );
        successes.add(result);
      } catch (DataValidationException e) {
        errors.add(DataValidationExceptionMapper.map(e).toError());
      } catch (UpdateException e) {
        errors.add(e.toError());
      }
    }

    int cancelledByOmission = applyTripReplacementPeriodCancellations(
      tripReplacementPeriods,
      seenTripIds,
      feedIds,
      successes,
      errors
    );

    var updateResult = UpdateResult.of(successes, errors);

    if (updateIncrementality == FULL_DATASET) {
      ResultLogger.logUpdateResult(String.join(",", feedIds), "gtfs-rt-trip-updates", updateResult);
    }
    if (partialTripIdMatcher != null && !updates.isEmpty()) {
      LOG.info(
        "[feedIds={}] partial-matcher diag: {}",
        feedIds,
        partialTripIdMatcher.summarizeCounters()
      );
    }
    if (
      partialTripIdMatches > 0 ||
      cancelledByOmission > 0 ||
      convertedScheduledToAdded > 0 ||
      convertedScheduledToAddedDueToPatternDivergence > 0 ||
      !tripReplacementPeriods.isEmpty()
    ) {
      LOG.info(
        "[feedIds={}] partial trip-id matches: {}, cancel-by-omission: {}, converted-to-added (unresolved/path-diverged): {}/{} (periods: total={}, active-routes={}, expired={})",
        feedIds,
        partialTripIdMatches,
        cancelledByOmission,
        convertedScheduledToAdded,
        convertedScheduledToAddedDueToPatternDivergence,
        tripReplacementPeriods.size(),
        coveredRouteIds,
        expiredPeriods
      );
    }
    // When unresolved trips show up on routes that no active replacement period covers, log
    // once so we can tell apart "NYCT didn't send a period for this route" from "our matcher
    // just couldn't resolve it".
    if (!unresolvedRouteIds.isEmpty() && !tripReplacementPeriods.isEmpty()) {
      var uncoveredUnresolved = new HashSet<>(unresolvedRouteIds);
      uncoveredUnresolved.removeAll(coveredRouteIds);
      if (!uncoveredUnresolved.isEmpty()) {
        LOG.info(
          "[feedIds={}] unresolved RT trips on routes not covered by any active replacement period: {}",
          feedIds,
          uncoveredUnresolved
        );
      }
    }
    return updateResult;
  }

  /**
   * Cancel scheduled trips on routes covered by an NYCT trip_replacement_period whose origin
   * departure falls within the period's window and that were not present in the current
   * update batch. The realtime feed is the authoritative source within the window.
   * <p>
   * Service dates yesterday/today/tomorrow are scanned to handle trips with stop times past
   * 24:00:00 (still belonging to the previous service date) and windows that straddle midnight.
   */
  private int applyTripReplacementPeriodCancellations(
    List<TripReplacementPeriod> periods,
    Set<FeedScopedId> seenTripIds,
    List<String> feedIds,
    List<UpdateSuccess> successes,
    List<UpdateError> errors
  ) {
    if (periods.isEmpty()) {
      return 0;
    }
    int cancelled = 0;

    var zone = transitEditorService.getTimeZone();
    var now = instantNow.get();
    LocalDate today = localDateNow.get();
    var serviceDates = List.of(today.minusDays(1), today, today.plusDays(1));
    var tripCalendars = transitEditorService.getTripCalendars();

    for (var period : periods) {
      if (period.endTime().isBefore(now)) {
        continue;
      }
      String resolvedFeedId = resolveFeedIdForRoute(period.routeId(), feedIds);
      if (resolvedFeedId == null) {
        continue;
      }
      var route = transitEditorService.getRoute(new FeedScopedId(resolvedFeedId, period.routeId()));
      if (route == null) {
        continue;
      }

      for (var serviceDate : serviceDates) {
        var serviceIds = tripCalendars.listServiceIdsOnServiceDate(serviceDate);
        if (serviceIds.isEmpty()) {
          continue;
        }
        var startOfService = ServiceDateUtils.asStartOfService(serviceDate, zone).toInstant();

        for (var pattern : transitEditorService.findPatterns(route)) {
          var timetable = pattern.getScheduledTimetable();
          var trips = pattern.scheduledTripsAsStream().toList();
          for (var trip : trips) {
            if (!serviceIds.contains(trip.getServiceId())) {
              continue;
            }
            if (seenTripIds.contains(trip.getId())) {
              continue;
            }
            var tripTimes = timetable.getTripTimes(trip.getId());
            if (tripTimes == null) {
              continue;
            }
            // The window covers any trip still running, not just ones yet to depart their
            // origin. At a mid-line station nearly every upcoming train left its terminal
            // long ago; testing the origin departure against `now` would skip them all.
            var firstDeparture = startOfService.plusSeconds(tripTimes.getDepartureTime(0));
            var lastArrival = startOfService.plusSeconds(
              tripTimes.getArrivalTime(tripTimes.getNumStops() - 1)
            );
            if (firstDeparture.isAfter(period.endTime()) || lastArrival.isBefore(now)) {
              continue;
            }
            try {
              var builder = tripTimes.createRealTimeFromScheduledTimes();
              builder.withCanceled();
              var result = TripUpdateApplier.apply(
                buffer,
                RealTimeTripUpdate.of(pattern, builder.build(), serviceDate)
                  .withRevertPreviousRealTimeUpdates(true)
                  .build()
              );
              successes.add(result);
              cancelled++;
            } catch (DataValidationException e) {
              errors.add(DataValidationExceptionMapper.map(e).toError());
            }
          }
        }
      }
    }
    return cancelled;
  }

  /**
   * Returns true if the RT update reports any stop_id not present in the resolved trip's
   * static pattern. NYCT regularly reroutes trips during service changes — the trip_id matches
   * the static schedule but the actual stop sequence differs (e.g. an extra origin stop, or a
   * mid-trip reroute via a different platform). When this happens the SCHEDULED handler would
   * throw INVALID_STOP_REFERENCE; treating the trip as ADDED instead synthesizes a fresh
   * pattern from the RT data.
   */
  private boolean rtStopsDivergeFromPattern(
    GtfsRealtime.TripUpdate update,
    Trip resolvedTrip,
    String resolvedFeedId
  ) {
    var pattern = transitEditorService.findPattern(resolvedTrip);
    if (pattern == null) {
      return false;
    }
    Set<String> patternStopIds = new HashSet<>();
    for (var stop : pattern.getStops()) {
      patternStopIds.add(stop.getId().getId());
    }
    for (var stu : update.getStopTimeUpdateList()) {
      if (
        stu.hasStopId() && !stu.getStopId().isBlank() && !patternStopIds.contains(stu.getStopId())
      ) {
        return true;
      }
    }
    return false;
  }

  private @Nullable String resolveFeedIdForRoute(String routeId, List<String> feedIds) {
    for (var feedId : feedIds) {
      if (transitEditorService.getRoute(new FeedScopedId(feedId, routeId)) != null) {
        return feedId;
      }
    }
    return null;
  }

  /**
   * Pick the static feed that owns the trip referenced by a raw GTFS-RT update.
   * <p>
   * Tries to resolve, in order: an existing trip with this trip_id, then an existing route
   * with this route_id (relevant for NEW or ADDED trips that don't yet exist as trips but
   * reference an existing route). Falls back to the first feedId — appropriate when the
   * update creates a brand new route as well, or when fields are missing.
   */
  private String resolveFeedIdForTripUpdate(
    GtfsRealtime.TripUpdate rawTripUpdate,
    List<String> feedIds
  ) {
    if (feedIds.size() == 1) {
      return feedIds.getFirst();
    }
    if (rawTripUpdate.hasTrip() && rawTripUpdate.getTrip().hasTripId()) {
      var tripId = rawTripUpdate.getTrip().getTripId();
      for (var feedId : feedIds) {
        if (transitEditorService.getTrip(new FeedScopedId(feedId, tripId)) != null) {
          return feedId;
        }
      }
    }
    if (rawTripUpdate.hasTrip() && rawTripUpdate.getTrip().hasRouteId()) {
      var routeId = rawTripUpdate.getTrip().getRouteId();
      for (var feedId : feedIds) {
        if (transitEditorService.getRoute(new FeedScopedId(feedId, routeId)) != null) {
          return feedId;
        }
      }
    }
    return feedIds.getFirst();
  }

  private UpdateSuccess applyUpdate(
    TripUpdate tripUpdate,
    UpdateIncrementality updateIncrementality,
    BackwardsDelayPropagationType backwardsDelayPropagationType,
    ForwardsDelayPropagationType forwardsDelayPropagationType
  ) throws UpdateException {
    // The GTFS-RT TripDescriptor.schedule_relationship field is a protobuf optional enum,
    // so a single TripUpdate message carries exactly one value — it is structurally impossible
    // for a message to express two states (e.g. ADDED and CANCELED) at the same time.
    // Cancelling a previously-added trip therefore always arrives as a second, separate feed
    // entity carrying only CANCELED or DELETED. This is why the RealTimeTripTimesBuilder never
    // needs to hold both added=true and canceled=true simultaneously for a GTFS-RT source.
    return switch (tripUpdate.scheduleRelationship()) {
      case SCHEDULED -> scheduledTripHandler.handle(
        tripUpdate,
        forwardsDelayPropagationType,
        backwardsDelayPropagationType
      );
      case NEW, ADDED -> addedTripHandler.handleNew(tripUpdate);
      case CANCELED -> canceledTripHandler.cancel(tripUpdate, updateIncrementality);
      case DELETED -> canceledTripHandler.delete(tripUpdate, updateIncrementality);
      case DUPLICATED -> duplicatedTripHandler.handleDuplicated(tripUpdate, updateIncrementality);
      case REPLACEMENT -> addedTripHandler.handleReplacement(tripUpdate);
      case UNSCHEDULED -> throw UpdateException.of(
        tripUpdate.tripId(),
        NOT_IMPLEMENTED_UNSCHEDULED
      );
    };
  }
}
