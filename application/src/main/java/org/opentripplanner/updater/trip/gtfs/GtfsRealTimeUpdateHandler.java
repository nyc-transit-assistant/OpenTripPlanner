package org.opentripplanner.updater.trip.gtfs;

import static org.opentripplanner.updater.spi.UpdateErrorType.NOT_IMPLEMENTED_UNSCHEDULED;
import static org.opentripplanner.updater.trip.UpdateIncrementality.FULL_DATASET;

import com.google.transit.realtime.GtfsRealtime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transit.model.framework.DataValidationException;
import org.opentripplanner.transit.model.timetable.RealTimeTripUpdate;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripIdAndServiceDate;
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
  private final ZoneId timeZone;
  private final ScheduledTripHandler scheduledTripHandler;
  private final NewTripHandler addedTripHandler;
  private final CanceledTripHandler canceledTripHandler;
  private final DuplicatedTripHandler duplicatedTripHandler;

  GtfsRealTimeUpdateHandler(
    MutableTimetableSnapshot buffer,
    TransitEditorService transitEditorService,
    Supplier<LocalDate> localDateNow,
    Supplier<Instant> instantNow,
    ZoneId timeZone,
    ScheduledTripHandler scheduledTripHandler,
    NewTripHandler addedTripHandler,
    CanceledTripHandler canceledTripHandler,
    DuplicatedTripHandler duplicatedTripHandler
  ) {
    this.buffer = buffer;
    this.transitEditorService = transitEditorService;
    this.localDateNow = localDateNow;
    this.instantNow = instantNow;
    this.timeZone = timeZone;
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
    int strippedEmptyStopTimeEvents = 0;
    int convertedScheduledToAdded = 0;
    int convertedScheduledToAddedDueToPatternDivergence = 0;
    int skippedInformationlessUpdates = 0;

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

    // Snapshot realtime-added trip state before any clear: producers such as NYCT drop a
    // stop's update once the vehicle departs it, so a NEW trip rebuilt from the current
    // message alone erodes from the front every cycle. The pre-clear buffer is the last
    // cycle's state — harvest it so NewTripHandler can carry the departed stops forward
    // with their last observed times. Lifecycle is free: when the trip leaves the feed
    // entirely, the clear drops it and nothing is harvested next cycle.
    Map<TripIdAndServiceDate, List<PastStop>> pastStopsByTrip = updateIncrementality == FULL_DATASET
      ? harvestRealTimeTripStops(feedIds, updates, partialTripIdMatcher)
      : Map.of();

    if (updateIncrementality == FULL_DATASET) {
      if (scopedFullDatasetClear) {
        // Shared-feed mode: this updater is never authoritative for the whole feed, so an
        // unscoped clear is never permitted — with several per-line updaters writing to one
        // feedId (NYCT), an unscoped clear wipes the siblings' just-applied data and the last
        // writer wins. Scope = declared replacement periods (fresh or expired — expiry gates
        // cancellation authority, not ownership). Routes merely present in the batch do NOT
        // widen the scope when periods exist: NYCT slips stray foreign-route entries into a
        // sibling feed (rerouted E shells in the BDFM feed, with zero stop time updates), and
        // letting those claim route-level clear authority makes this updater wipe the entire
        // foreign route's realtime every cycle while re-applying nothing — the exact
        // last-writer-wins failure scoping exists to prevent. Batch routes are the fallback
        // only when the feed declares no periods at all (the G omits the NYCT header
        // extension at times), where they are the only ownership signal available. An empty
        // scope clears nothing. The cost: a route whose period NYCT forgets to declare keeps
        // ghost entries for trips that leave the feed — bounded staleness, versus a sibling
        // erasing a whole route's live data.
        Set<String> scopeRouteIds = new HashSet<>();
        for (var period : tripReplacementPeriods) {
          scopeRouteIds.add(period.routeId());
        }
        if (scopeRouteIds.isEmpty()) {
          for (var u : updates) {
            if (u.hasTrip() && u.getTrip().hasRouteId() && !u.getTrip().getRouteId().isBlank()) {
              scopeRouteIds.add(u.getTrip().getRouteId());
            }
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
        var sanitized = stripEmptyStopTimeEvents(rawTripUpdate);
        if (sanitized != rawTripUpdate) {
          strippedEmptyStopTimeEvents++;
          rawTripUpdate = sanitized;
        }
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

        // NYCT emits informationless updates two ways: placeholder-only updates on unstarted
        // trips (whose sole degenerate event the sanitizer just stripped) and zero-STU stray
        // shells for rerouted trains slipped into sibling feeds. Neither carries anything
        // applyable — skip them as counted benign events rather than failing the batch with
        // NO_UPDATES / TRIP_NOT_FOUND noise that drowns real regressions in the metrics.
        // Ordering matters: the trip was recorded in seenTripIds above, so cancel-by-omission
        // still treats it as present in the feed. CANCELED updates legitimately carry no stop
        // time updates and pass through untouched.
        if (isInformationlessUpdate(rawTripUpdate)) {
          skippedInformationlessUpdates++;
          continue;
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
          forwardsDelayPropagationType,
          pastStopsByTrip.get(new TripIdAndServiceDate(tripUpdate.tripId(), tripUpdate.startDate()))
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
        "[feedIds={}] partial-matcher diag: {}, strippedEmptyStopTimeEvents=" +
          strippedEmptyStopTimeEvents +
          ", skippedInformationlessUpdates=" +
          skippedInformationlessUpdates,
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

  /**
   * Capture every realtime-added trip's stops with their last observed times, keyed by trip and
   * service date. Must run against the buffer <em>before</em> the FULL_DATASET clear — the
   * pre-clear buffer is the previous cycle's state and the only place a NEW trip's already
   * departed stops still exist.
   */
  private Map<TripIdAndServiceDate, List<PastStop>> harvestRealTimeTripStops(
    List<String> feedIds,
    List<GtfsRealtime.TripUpdate> updates,
    @Nullable GtfsRealtimePartialTripIdMatcher partialTripIdMatcher
  ) {
    Map<TripIdAndServiceDate, List<PastStop>> out = new HashMap<>();
    // Realtime-added (NEW/ADDED) trips are enumerable directly.
    for (var tripOnServiceDate : buffer.listRealTimeAddedTripOnServiceDate()) {
      Trip trip = tripOnServiceDate.getTrip();
      if (!feedIds.contains(trip.getId().getFeedId())) {
        continue;
      }
      var pattern = buffer.getRealTimeAddedPatternForTrip(trip);
      harvestTrip(out, trip.getId(), tripOnServiceDate.getServiceDate(), pattern);
    }
    // REPLACEMENT trips (including scheduled trips this handler rewrites to REPLACEMENT on
    // stop-pattern divergence) register as modified-trip patterns, which have no enumeration
    // API — probe the trips of the incoming batch instead.
    for (var update : updates) {
      if (!update.hasTrip() || !update.getTrip().hasTripId()) {
        continue;
      }
      var descriptor = update.getTrip();
      if (descriptor.getTripId().isBlank()) {
        // Invalid updates are rejected downstream, one update at a time.
        continue;
      }
      LocalDate serviceDate;
      try {
        serviceDate = descriptor.hasStartDate()
          ? LocalDate.parse(
              descriptor.getStartDate(),
              java.time.format.DateTimeFormatter.BASIC_ISO_DATE
            )
          : localDateNow.get();
      } catch (java.time.format.DateTimeParseException e) {
        continue;
      }
      for (String feedId : feedIds) {
        // The modified-trip registry is keyed by the RESOLVED (static) trip id, but the raw
        // batch carries the producer's suffix ids (NYCT) — resolve the same way the main
        // loop will, without disturbing the matcher's diagnostic counters.
        var resolvedId = descriptor.getTripId();
        if (partialTripIdMatcher != null) {
          resolvedId = partialTripIdMatcher.matchQuietly(feedId, descriptor).getTripId();
        }
        var candidateIds = resolvedId.equals(descriptor.getTripId())
          ? List.of(descriptor.getTripId())
          : List.of(descriptor.getTripId(), resolvedId);
        for (var idValue : candidateIds) {
          var tripId = new FeedScopedId(feedId, idValue);
          var key = new TripIdAndServiceDate(tripId, serviceDate);
          if (out.containsKey(key)) {
            continue;
          }
          var probedPattern = buffer.getNewTripPatternForModifiedTrip(tripId, serviceDate);
          if (probedPattern == null) {
            probedPattern = realTimeTouchedScheduledPattern(tripId, serviceDate);
          }
          harvestTrip(out, tripId, serviceDate, probedPattern);
        }
      }
    }
    return out;
  }

  /**
   * A matched trip is only registered in the modified-trip pattern map once its realtime stop
   * pattern actually differs from the scheduled one. Before the origin departs, the feed still
   * lists every stop, so a matched trip's realtime state — plain delay updates, or a
   * REPLACEMENT rebuild whose only divergence is an unknown stop (NYCT phantom stops absent
   * from static GTFS) — rides the <em>scheduled</em> pattern's timetable. At the origin
   * departure the origin drops from the feed, the pattern finally diverges, and the
   * modified-trip probe above finds nothing from the previous cycle: without this fallback the
   * origin stop is silently lost at exactly that transition (the Whitehall St case). Only
   * realtime-touched trip times are harvested — scheduled times are predictions nobody
   * observed, and carrying them would fabricate history the feed never reported.
   */
  @Nullable
  private org.opentripplanner.transit.model.network.TripPattern realTimeTouchedScheduledPattern(
    FeedScopedId tripId,
    LocalDate serviceDate
  ) {
    var trip = transitEditorService.getTrip(tripId);
    if (trip == null) {
      return null;
    }
    var pattern = transitEditorService.findPattern(trip);
    if (pattern == null) {
      return null;
    }
    var tripTimes = buffer.resolve(pattern, serviceDate).getTripTimes(tripId);
    if (tripTimes == null || !tripTimes.hasAnyUpdates() || tripTimes.isCanceledOrDeleted()) {
      return null;
    }
    return pattern;
  }

  private void harvestTrip(
    Map<TripIdAndServiceDate, List<PastStop>> out,
    FeedScopedId tripId,
    LocalDate serviceDate,
    @Nullable org.opentripplanner.transit.model.network.TripPattern pattern
  ) {
    if (pattern == null) {
      return;
    }
    var timetable = buffer.resolve(pattern, serviceDate);
    var tripTimes = timetable.getTripTimes(tripId);
    if (tripTimes == null) {
      return;
    }
    long midnight = ServiceDateUtils.asStartOfService(serviceDate, timeZone).toEpochSecond();
    int n = pattern.numberOfStops();
    List<PastStop> stops = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      stops.add(
        new PastStop(
          pattern.getStop(i),
          midnight + tripTimes.getArrivalTime(i),
          midnight + tripTimes.getDepartureTime(i)
        )
      );
    }
    out.put(new TripIdAndServiceDate(tripId, serviceDate), stops);
  }

  private UpdateSuccess applyUpdate(
    TripUpdate tripUpdate,
    UpdateIncrementality updateIncrementality,
    BackwardsDelayPropagationType backwardsDelayPropagationType,
    ForwardsDelayPropagationType forwardsDelayPropagationType,
    @Nullable List<PastStop> pastStops
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
      case NEW, ADDED -> addedTripHandler.handleNew(tripUpdate, pastStops);
      case CANCELED -> canceledTripHandler.cancel(tripUpdate, updateIncrementality);
      case DELETED -> canceledTripHandler.delete(tripUpdate, updateIncrementality);
      case DUPLICATED -> duplicatedTripHandler.handleDuplicated(tripUpdate, updateIncrementality);
      case REPLACEMENT -> addedTripHandler.handleReplacement(tripUpdate, pastStops);
      case UNSCHEDULED -> throw UpdateException.of(
        tripUpdate.tripId(),
        NOT_IMPLEMENTED_UNSCHEDULED
      );
    };
  }

  /**
   * Remove StopTimeEvents that are present but empty (neither time nor delay), and drop
   * StopTimeUpdates left with no events at all and no other semantics.
   * <p>
   * The spec requires a StopTimeEvent to carry a time or a delay, and stock OTP rejects the
   * whole trip update over a violation (INVALID_ARRIVAL_TIME / INVALID_DEPARTURE_TIME). NYCT
   * prepends exactly such a degenerate entry — the trip's terminal, out of sequence, with an
   * empty departure event — to trips that haven't started yet, which would cost the trip its
   * entire prediction set. An empty event carries the same information as an absent one, so
   * stripping it is lossless; a stop left with no events simply isn't mentioned, which the
   * delay interpolators already handle.
   *
   * @return the same instance when nothing needed stripping, a rebuilt update otherwise
   */
  /**
   * True when the update carries no stop time updates and plain SCHEDULED semantics — nothing
   * to apply, nothing being cancelled. Such updates are presence markers at most.
   */
  static boolean isInformationlessUpdate(GtfsRealtime.TripUpdate tripUpdate) {
    if (tripUpdate.getStopTimeUpdateCount() > 0) {
      return false;
    }
    var trip = tripUpdate.getTrip();
    // A shell must at least reference a trip: updates with a missing or blank trip id are
    // structurally invalid, not informationless, and must still fail validation
    // (INVALID_INPUT_STRUCTURE) instead of being silently skipped.
    if (!trip.hasTripId() || trip.getTripId().isBlank()) {
      return false;
    }
    return (
      !trip.hasScheduleRelationship() ||
      trip.getScheduleRelationship() == GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED
    );
  }

  static GtfsRealtime.TripUpdate stripEmptyStopTimeEvents(GtfsRealtime.TripUpdate tripUpdate) {
    boolean changed = false;
    var builder = tripUpdate.toBuilder();
    var cleaned = new ArrayList<GtfsRealtime.TripUpdate.StopTimeUpdate>(
      tripUpdate.getStopTimeUpdateCount()
    );
    for (var stu : tripUpdate.getStopTimeUpdateList()) {
      var stuBuilder = stu.toBuilder();
      boolean stuChanged = false;
      if (stu.hasArrival() && !stu.getArrival().hasTime() && !stu.getArrival().hasDelay()) {
        stuBuilder.clearArrival();
        stuChanged = true;
      }
      if (stu.hasDeparture() && !stu.getDeparture().hasTime() && !stu.getDeparture().hasDelay()) {
        stuBuilder.clearDeparture();
        stuChanged = true;
      }
      if (stuChanged) {
        changed = true;
        var result = stuBuilder.build();
        // A stop with no events left and plain SCHEDULED semantics is simply not mentioned.
        // SKIPPED / NO_DATA entries keep their meaning without times and are preserved.
        if (
          !result.hasArrival() &&
          !result.hasDeparture() &&
          result.getScheduleRelationship() ==
          GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SCHEDULED
        ) {
          continue;
        }
        cleaned.add(result);
      } else {
        cleaned.add(stu);
      }
    }
    if (!changed) {
      return tripUpdate;
    }
    builder.clearStopTimeUpdate();
    builder.addAllStopTimeUpdate(cleaned);
    return builder.build();
  }
}
