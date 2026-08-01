package org.opentripplanner.updater.trip.gtfs;

import static org.opentripplanner.updater.spi.UpdateErrorType.NOT_IMPLEMENTED_DUPLICATED;
import static org.opentripplanner.updater.spi.UpdateErrorType.NOT_IMPLEMENTED_UNSCHEDULED;
import static org.opentripplanner.updater.spi.UpdateErrorType.NO_SERVICE_ON_DATE;
import static org.opentripplanner.updater.spi.UpdateErrorType.NO_TRIP_FOR_CANCELLATION_FOUND;
import static org.opentripplanner.updater.spi.UpdateErrorType.NO_UPDATES;
import static org.opentripplanner.updater.spi.UpdateErrorType.OUTSIDE_SERVICE_PERIOD;
import static org.opentripplanner.updater.spi.UpdateErrorType.TOO_FEW_STOPS;
import static org.opentripplanner.updater.spi.UpdateErrorType.TRIP_ALREADY_EXISTS;
import static org.opentripplanner.updater.spi.UpdateErrorType.TRIP_NOT_FOUND;
import static org.opentripplanner.updater.trip.UpdateIncrementality.FULL_DATASET;

import com.google.transit.realtime.GtfsRealtime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.opentripplanner.core.framework.deduplicator.DeduplicatorService;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transit.model.framework.DataValidationException;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.RealTimeState;
import org.opentripplanner.transit.model.timetable.RealTimeTripTimes;
import org.opentripplanner.transit.model.timetable.RealTimeTripUpdate;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripOnServiceDate;
import org.opentripplanner.transit.service.DefaultTransitService;
import org.opentripplanner.transit.service.TimetableRepository;
import org.opentripplanner.transit.service.TransitEditorService;
import org.opentripplanner.updater.spi.DataValidationExceptionMapper;
import org.opentripplanner.updater.spi.ResultLogger;
import org.opentripplanner.updater.spi.UpdateError;
import org.opentripplanner.updater.spi.UpdateException;
import org.opentripplanner.updater.spi.UpdateResult;
import org.opentripplanner.updater.spi.UpdateSuccess;
import org.opentripplanner.updater.trip.TimetableSnapshotManager;
import org.opentripplanner.updater.trip.UpdateIncrementality;
import org.opentripplanner.updater.trip.gtfs.model.TripUpdate;
import org.opentripplanner.updater.trip.patterncache.AddedTripHopGeometryResolver;
import org.opentripplanner.updater.trip.patterncache.TripPatternCache;
import org.opentripplanner.updater.trip.patterncache.TripPatternIdGenerator;
import org.opentripplanner.utils.time.ServiceDateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts from GTFS-RT TripUpdates to OTP's internal real-time data model.
 */
public class GtfsRealTimeTripUpdateAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(GtfsRealTimeTripUpdateAdapter.class);

  /**
   * A synchronized cache of trip patterns added to the timetable repository
   * due to GTFS-realtime messages.
   **/
  private final TripPatternCache tripPatternCache;

  /**
   * Long-lived transit editor service that has access to the timetable snapshot buffer.
   * This differs from the usual use case where the transit service refers to the latest published
   * timetable snapshot.
   */
  private final TransitEditorService transitEditorService;

  private final TimetableSnapshotManager snapshotManager;
  private final Supplier<LocalDate> localDateNow;
  private final Supplier<Instant> instantNow;
  private final TripTimesUpdater tripTimesUpdater;

  public GtfsRealTimeTripUpdateAdapter(
    TimetableRepository timetableRepository,
    DeduplicatorService deduplicator,
    TimetableSnapshotManager snapshotManager,
    Supplier<LocalDate> localDateNow
  ) {
    this(timetableRepository, deduplicator, snapshotManager, localDateNow, Instant::now);
  }

  /**
   * Constructor to allow tests to provide their own clock, not using system time. The
   * {@code instantNow} supplier should normally be consistent with {@code localDateNow}.
   */
  public GtfsRealTimeTripUpdateAdapter(
    TimetableRepository timetableRepository,
    DeduplicatorService deduplicator,
    TimetableSnapshotManager snapshotManager,
    Supplier<LocalDate> localDateNow,
    Supplier<Instant> instantNow
  ) {
    this.snapshotManager = snapshotManager;
    this.localDateNow = localDateNow;
    this.instantNow = instantNow;
    this.transitEditorService = new DefaultTransitService(
      timetableRepository,
      snapshotManager.getTimetableSnapshotBuffer()
    );
    this.tripTimesUpdater = new TripTimesUpdater(timetableRepository.getTimeZone(), deduplicator);
    var hopGeometryResolver = new AddedTripHopGeometryResolver(
      transitEditorService::findPatterns,
      stop -> transitEditorService.findPatterns(stop)
    );
    this.tripPatternCache = new TripPatternCache(
      new TripPatternIdGenerator(),
      transitEditorService::findPattern,
      hopGeometryResolver
    );
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
            snapshotManager.clearBufferForRoutes(feedId, scoped);
          }
        }
      } else if (coveredRouteIds.isEmpty()) {
        // Single authoritative source for this feedId — wipe the whole feed.
        for (String feedId : feedIds) {
          snapshotManager.clearBuffer(feedId);
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
          snapshotManager.clearBufferForRoutes(feedId, scopedRouteIds);
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
        "[feedIds={}] partial-matcher diag: {}, strippedEmptyStopTimeEvents=" +
          strippedEmptyStopTimeEvents,
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
    var calendarService = transitEditorService.getCalendarService();

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
        var serviceIds = calendarService.getServiceIdsOnDate(serviceDate);
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
              builder.cancelTrip();
              var result = snapshotManager.updateBuffer(
                RealTimeTripUpdate.of(pattern, builder.build(), serviceDate)
                  .withRevertPreviousRealTimeUpdates(true)
                  .build()
              );
              successes.add(result);
              cancelled++;
            } catch (UpdateException e) {
              errors.add(e.toError());
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
    return switch (tripUpdate.scheduleRelationship()) {
      case SCHEDULED -> handleScheduledTrip(
        tripUpdate,
        forwardsDelayPropagationType,
        backwardsDelayPropagationType
      );
      case NEW, ADDED -> validateAndHandleNewTrip(tripUpdate);
      case CANCELED -> handleCanceledTrip(tripUpdate, CancelationType.CANCEL, updateIncrementality);
      case DELETED -> handleCanceledTrip(tripUpdate, CancelationType.DELETE, updateIncrementality);
      case REPLACEMENT -> validateAndHandleReplacementTrip(tripUpdate);
      case UNSCHEDULED -> throw UpdateException.of(
        tripUpdate.tripId(),
        NOT_IMPLEMENTED_UNSCHEDULED
      );
      case DUPLICATED -> throw UpdateException.of(tripUpdate.tripId(), NOT_IMPLEMENTED_DUPLICATED);
    };
  }

  private UpdateSuccess handleScheduledTrip(
    TripUpdate tripUpdate,
    ForwardsDelayPropagationType forwardsDelayPropagationType,
    BackwardsDelayPropagationType backwardsDelayPropagationType
  ) throws UpdateException {
    final TripPattern pattern = getPatternForTripId(tripUpdate.tripId());

    if (pattern == null) {
      throw UpdateException.of(tripUpdate.tripId(), TRIP_NOT_FOUND);
    }

    if (tripUpdate.stopTimeUpdates().isEmpty()) {
      throw UpdateException.of(tripUpdate.tripId(), NO_UPDATES);
    }

    var serviceId = transitEditorService.getTrip(tripUpdate.tripId()).getServiceId();
    var serviceDates = transitEditorService
      .getCalendarService()
      .getServiceDatesForServiceId(serviceId);
    if (!serviceDates.contains(tripUpdate.serviceDate())) {
      throw UpdateException.of(tripUpdate.tripId(), NO_SERVICE_ON_DATE);
    }

    // Get new TripTimes based on scheduled timetable
    var tripTimesPatch = tripTimesUpdater.createUpdatedTripTimesFromGtfsRt(
      pattern.getScheduledTimetable(),
      tripUpdate,
      forwardsDelayPropagationType,
      backwardsDelayPropagationType
    );

    var updatedPickup = tripTimesPatch.updatedPickup();
    var updatedDropoff = tripTimesPatch.updatedDropoff();
    var replacedStopIndices = tripTimesPatch.replacedStopIndices();

    var updatedTripTimes = tripTimesPatch.tripTimes();

    Map<Integer, StopLocation> newStops = new HashMap<>();
    for (var entry : replacedStopIndices.entrySet()) {
      var stop = transitEditorService.getRegularStop(
        new FeedScopedId(tripUpdate.tripId().getFeedId(), entry.getValue())
      );
      if (stop != null) {
        newStops.put(entry.getKey(), stop);
      }
    }

    // If there are stops with different pickup / drop off, or replaced stops, we need to change the pattern from the scheduled one
    if (!updatedPickup.isEmpty() || !updatedDropoff.isEmpty() || !newStops.isEmpty()) {
      StopPattern newStopPattern = pattern
        .copyPlannedStopPattern()
        .updatePickups(updatedPickup)
        .updateDropoffs(updatedDropoff)
        .replaceStops(newStops)
        .build();

      final Trip trip = transitEditorService.getTrip(tripUpdate.tripId());
      // Get cached trip pattern or create one if it doesn't exist yet
      final TripPattern newPattern = tripPatternCache.getOrCreateTripPattern(newStopPattern, trip);

      return snapshotManager.updateBuffer(
        RealTimeTripUpdate.of(newPattern, updatedTripTimes, tripUpdate.serviceDate())
          .withRevertPreviousRealTimeUpdates(true)
          .withHideTripInScheduledPattern(pattern)
          .build()
      );
    } else {
      // Set the updated trip times in the buffer
      return snapshotManager.updateBuffer(
        RealTimeTripUpdate.of(pattern, updatedTripTimes, tripUpdate.serviceDate())
          .withRevertPreviousRealTimeUpdates(true)
          .build()
      );
    }
  }

  /**
   * Validate and handle GTFS-RT TripUpdate message containing an NEW trip.
   *
   * @return empty Result if successful or one containing an error
   */
  private UpdateSuccess validateAndHandleNewTrip(final TripUpdate tripUpdate)
    throws UpdateException {
    // Check whether trip id already exists in graph
    if (transitEditorService.getScheduledTrip(tripUpdate.tripId()) != null) {
      throw UpdateException.of(tripUpdate.tripId(), TRIP_ALREADY_EXISTS);
    }
    // get service ID running only on this service date
    var serviceId = transitEditorService.getOrCreateServiceIdForDate(tripUpdate.serviceDate());
    if (serviceId == null) {
      throw UpdateException.of(tripUpdate.tripId(), OUTSIDE_SERVICE_PERIOD);
    }

    var result = new RouteFactory(transitEditorService).getOrCreate(tripUpdate);

    // TODO: which Agency ID to use? Currently use feed id.
    var tripBuilder = Trip.of(tripUpdate.tripId())
      .withRoute(result.route())
      .withServiceId(serviceId);

    tripUpdate.tripHeadsign().ifPresent(tripBuilder::withHeadsign);
    tripUpdate.tripShortName().ifPresent(tripBuilder::withShortName);

    Trip trip = tripBuilder.build();

    return handleNewOrReplacementTrip(
      trip,
      tripUpdate,
      RealTimeState.ADDED,
      result.newRouteCreated()
    );
  }

  /**
   * Remove any stop that is not know in the static transit data.
   */
  private List<StopAndStopTimeUpdate> matchStopsToStopTimeUpdates(TripUpdate tripUpdate) {
    return tripUpdate
      .stopTimeUpdates()
      .stream()
      .flatMap(st ->
        st
          .stopId()
          .flatMap(id -> {
            var stopId = new FeedScopedId(tripUpdate.tripId().getFeedId(), id);
            var stop = transitEditorService.getRegularStop(stopId);
            return Optional.ofNullable(stop).map(s -> new StopAndStopTimeUpdate(s, st));
          })
          .stream()
      )
      .toList();
  }

  /**
   * Handle GTFS-RT TripUpdate message containing an NEW or REPLACEMENT trip.
   *
   * @return empty Result if successful or one containing an error
   */
  private UpdateSuccess handleNewOrReplacementTrip(
    Trip trip,
    TripUpdate tripUpdate,
    RealTimeState realTimeState,
    boolean hasANewRouteBeenCreated
  ) throws UpdateException {
    FeedScopedId tripId = trip.getId();
    var stopAndStopTimeUpdates = matchStopsToStopTimeUpdates(tripUpdate);

    var warnings = new ArrayList<UpdateSuccess.WarningType>(0);

    if (stopAndStopTimeUpdates.size() < tripUpdate.stopTimeUpdates().size()) {
      warnings.add(UpdateSuccess.WarningType.UNKNOWN_STOPS_REMOVED_FROM_ADDED_TRIP);
    }

    // check if after filtering the stops we still have at least 2
    if (stopAndStopTimeUpdates.size() < 2) {
      throw UpdateException.of(tripId, TOO_FEW_STOPS);
    }

    var value = tripTimesUpdater.createNewTripTimesFromGtfsRt(
      trip,
      tripUpdate,
      stopAndStopTimeUpdates,
      realTimeState,
      transitEditorService.getServiceCode(trip.getServiceId())
    );

    return addNewOrReplacementTripToSnapshot(
      value,
      tripUpdate.serviceDate(),
      realTimeState,
      hasANewRouteBeenCreated
    ).addWarnings(warnings);
  }

  /**
   * Add a new or replacement trip to the snapshot
   *
   * @param serviceDate       service date of trip
   * @param realTimeState     real-time state of new trip
   * @return empty Result if successful or one containing an error
   */
  private UpdateSuccess addNewOrReplacementTripToSnapshot(
    final TripTimesWithStopPattern tripTimesWithStopPattern,
    final LocalDate serviceDate,
    final RealTimeState realTimeState,
    final boolean hasANewRouteBeenCreated
  ) throws UpdateException {
    RealTimeTripTimes tripTimes = tripTimesWithStopPattern.tripTimes();
    Trip trip = tripTimes.getTrip();

    // Create StopPattern
    final StopPattern stopPattern = tripTimesWithStopPattern.stopPattern();

    // Get cached trip pattern or create one if it doesn't exist yet
    final TripPattern pattern = tripPatternCache.getOrCreateTripPattern(stopPattern, trip);

    // Look up the scheduled pattern for MODIFIED trips so the manager can mark it as deleted
    TripPattern hideTripInScheduledPattern = null;
    if (realTimeState == RealTimeState.MODIFIED) {
      hideTripInScheduledPattern = getPatternForTripId(trip.getId());
    }

    // Add new trip times to the buffer
    var builder = RealTimeTripUpdate.of(pattern, tripTimes, serviceDate)
      .withRouteCreation(hasANewRouteBeenCreated)
      .withRevertPreviousRealTimeUpdates(true)
      .withHideTripInScheduledPattern(hideTripInScheduledPattern);
    if (realTimeState == RealTimeState.ADDED) {
      builder
        .withAddedTripOnServiceDate(
          TripOnServiceDate.of(trip.getId()).withTrip(trip).withServiceDate(serviceDate).build()
        )
        .withTripCreation(true);
    }
    return snapshotManager.updateBuffer(builder.build());
  }

  /**
   * Validate and handle GTFS-RT TripUpdate message containing a REPLACEMENT trip.
   *
   * @param tripUpdate     GTFS-RT TripUpdate message
   * @return empty Result if successful or one containing an error
   */
  private UpdateSuccess validateAndHandleReplacementTrip(TripUpdate tripUpdate)
    throws UpdateException {
    // Check whether trip id already exists in graph
    Trip trip = transitEditorService.getTrip(tripUpdate.tripId());

    if (trip == null) {
      throw UpdateException.of(tripUpdate.tripId(), TRIP_NOT_FOUND);
    }

    // Check whether service date is served by trip
    final Set<FeedScopedId> serviceIds = transitEditorService
      .getCalendarService()
      .getServiceIdsOnDate(tripUpdate.serviceDate());
    if (!serviceIds.contains(trip.getServiceId())) {
      // TODO: should we support this and change service id of trip?
      throw UpdateException.of(tripUpdate.tripId(), NO_SERVICE_ON_DATE);
    }

    return handleNewOrReplacementTrip(trip, tripUpdate, RealTimeState.MODIFIED, false);
  }

  private UpdateSuccess handleCanceledTrip(
    TripUpdate tripUpdate,
    CancelationType cancelationType,
    UpdateIncrementality incrementality
  ) throws UpdateException {
    // For DIFFERENTIAL updates, try to cancel a previously added trip
    if (incrementality != FULL_DATASET) {
      var addedPattern = snapshotManager.getNewTripPatternForModifiedTrip(
        tripUpdate.tripId(),
        tripUpdate.serviceDate()
      );
      if (addedPattern != null) {
        var timetable = snapshotManager.resolve(addedPattern, tripUpdate.serviceDate());
        if (timetable != null) {
          var tripTimes = timetable.getTripTimes(tripUpdate.tripId());
          if (tripTimes != null && tripTimes.getRealTimeState() == RealTimeState.ADDED) {
            var builder = tripTimes.createRealTimeFromScheduledTimes();
            switch (cancelationType) {
              case CANCEL -> builder.cancelTrip();
              case DELETE -> builder.deleteTrip();
            }
            return snapshotManager.updateBuffer(
              RealTimeTripUpdate.of(addedPattern, builder.build(), tripUpdate.serviceDate()).build()
            );
          }
        }
      }
    }

    // Cancel the scheduled trip
    var pattern = getPatternForTripId(tripUpdate.tripId());
    if (pattern == null) {
      throw UpdateException.of(tripUpdate.tripId(), NO_TRIP_FOR_CANCELLATION_FOUND);
    }

    var tripTimes = pattern.getScheduledTimetable().getTripTimes(tripUpdate.tripId());
    if (tripTimes == null) {
      throw UpdateException.of(tripUpdate.tripId(), NO_TRIP_FOR_CANCELLATION_FOUND);
    }

    var builder = tripTimes.createRealTimeFromScheduledTimes();
    switch (cancelationType) {
      case CANCEL -> builder.cancelTrip();
      case DELETE -> builder.deleteTrip();
    }
    return snapshotManager.updateBuffer(
      RealTimeTripUpdate.of(pattern, builder.build(), tripUpdate.serviceDate())
        .withRevertPreviousRealTimeUpdates(true)
        .build()
    );
  }

  /**
   * Retrieve a trip pattern given a trip id.
   *
   * @param tripId trip id
   * @return trip pattern or null if no trip pattern was found
   */
  private TripPattern getPatternForTripId(FeedScopedId tripId) {
    Trip trip = transitEditorService.getTrip(tripId);
    return transitEditorService.findPattern(trip);
  }

  private enum CancelationType {
    CANCEL,
    DELETE,
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
