package org.opentripplanner.updater.trip.gtfs;

import static org.opentripplanner.updater.spi.UpdateErrorType.NOT_IMPLEMENTED_UNSCHEDULED;
import static org.opentripplanner.updater.trip.UpdateIncrementality.FULL_DATASET;

import com.google.transit.realtime.GtfsRealtime;
import io.micrometer.core.instrument.Metrics;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

  /**
   * How long after a trip instance's scheduled end a timeless update (e.g. a cancellation) is
   * still attributed to that instance. Beyond this, if the following date is also active, the
   * update is taken to mean the upcoming instance instead. Generous on purpose: a late-posted
   * cancellation for a just-finished trip must not cancel tomorrow's run, while NYCT's
   * previous-transit-day anchoring puts the claimed instance a full ~24h in the past.
   */
  private static final Duration REANCHOR_GRACE = Duration.ofHours(4);

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
    @Nullable GtfsRealtimeTrainNumberTripMatcher trainNumberTripMatcher,
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
    int trainNumberMatches = 0;
    int strippedEmptyStopTimeEvents = 0;
    int convertedScheduledToAdded = 0;
    int convertedScheduledToAddedDueToPatternDivergence = 0;
    int skippedInformationlessUpdates = 0;
    int reanchoredStartDates = 0;
    int discardedDateMismatchedResolutions = 0;

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
    // Realtime route labels, as sent, of trips that resolved to no static trip and whose route no
    // active replacement period covers. Populated at the point of decision rather than subtracted
    // afterwards, so an aliased label is not reported as uncovered when its static route is.
    Set<String> unresolvedUncoveredRouteIds = new HashSet<>();

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
        // Kept so a resolution that turns out to contradict the schedule by a whole day can
        // be undone wholesale (trip id and start date rewrites included).
        var preResolutionUpdate = rawTripUpdate;

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

        if (trainNumberTripMatcher != null) {
          // Train-number identity (MTA Metro-North, NJT rail): the vehicle descriptor carries
          // the train number — natively (NJT vehicle.id) or stashed there by the source from
          // the FeedEntity id (MNR). Resolve it against static trip_short_name + service date.
          var vehicle = rawTripUpdate.getVehicle();
          var trainNumber = !vehicle.getLabel().isBlank() ? vehicle.getLabel() : vehicle.getId();
          var originalTripId = rawTripUpdate.getTrip().getTripId();
          var trip = trainNumberTripMatcher.match(
            resolvedFeedId,
            trainNumber,
            rawTripUpdate.getTrip()
          );
          if (!trip.getTripId().equals(originalTripId)) {
            trainNumberMatches++;
          }
          rawTripUpdate = rawTripUpdate.toBuilder().setTrip(trip).build();
        }

        if (fuzzyTripMatcher != null) {
          var trip = fuzzyTripMatcher.match(resolvedFeedId, rawTripUpdate.getTrip());
          rawTripUpdate = rawTripUpdate.toBuilder().setTrip(trip).build();
          // Re-resolve in case fuzzy matching populated a previously-missing trip_id.
          resolvedFeedId = resolveFeedIdForTripUpdate(rawTripUpdate, feedIds);
        }

        var reanchored = reanchorStartDate(rawTripUpdate, resolvedFeedId);
        if (reanchored != rawTripUpdate) {
          reanchoredStartDates++;
          rawTripUpdate = reanchored;
        }

        // NYCT regenerates supplement-schedule trip ids per service date, so tonight's
        // post-midnight train may have no static variant active on its true service date at
        // all. The partial matcher's alternate-date fallback (or an exact id hit) then
        // resolves the update onto the *other* day's instance, and no start-date choice can
        // reconcile it — the applied delay comes out ±24h and tomorrow's trip surfaces in
        // results with tonight's estimated times. When the resolved schedule anchor still
        // contradicts the update's absolute times by more than half a day, the resolution
        // itself is wrong: undo it entirely and let the update flow down the unresolved path
        // (NEW-trip synthesis on covered routes), which yields correct times.
        if (resolutionContradictsSchedule(rawTripUpdate, resolvedFeedId)) {
          discardedDateMismatchedResolutions++;
          rawTripUpdate = preResolutionUpdate;
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
          var rawRouteId = trip.getRouteId();
          // Replacement periods are keyed by the static route id, so a realtime-only route label
          // has to be resolved before the coverage check. NYCT's SIR feed sends shuttle-pattern
          // trains as route SS while declaring the period against SI, so comparing raw ids marks
          // them uncovered and drops them.
          //
          // Only substituted when the realtime id has no static route of its own, mirroring
          // GtfsRealtimePartialTripIdMatcher.match(): the alias table is global, and a feed with
          // a genuine route of that name must keep it.
          var coverageRouteId = transitEditorService.getRoute(
              new FeedScopedId(resolvedFeedId, rawRouteId)
            ) ==
            null
            ? GtfsRealtimePartialTripIdMatcher.staticRouteId(rawRouteId)
            : rawRouteId;
          var resolvedTrip = trip.hasTripId() && !trip.getTripId().isBlank()
            ? transitEditorService.getTrip(new FeedScopedId(resolvedFeedId, trip.getTripId()))
            : null;
          boolean shouldRewrite = false;
          String rewriteReason = null;
          if (resolvedTrip == null) {
            var coveredByPeriod = coveredRouteIds.contains(coverageRouteId);
            if (!coveredByPeriod) {
              // Recorded with the label the feed actually used, so the diagnostic below can still
              // tell an SS-specific resolution failure from a genuine SI one.
              unresolvedUncoveredRouteIds.add(rawRouteId);
            }
            // Synthesis looks the route up by the descriptor's id. With no static route,
            // RouteFactory falls through to createRoute, which dereferences the agency id of an
            // AddedRoute extension NYCT does not send and throws IllegalArgumentException — not
            // one of the per-update exceptions caught below, so it escapes the loop and, with the
            // FULL_DATASET clear already applied, takes the rest of the batch with it. Dropping
            // the single trip is the lesser failure.
            var targetRouteExists =
              transitEditorService.getRoute(new FeedScopedId(resolvedFeedId, coverageRouteId)) !=
              null;
            shouldRewrite = coveredByPeriod && targetRouteExists;
            rewriteReason = "unresolved";
          } else if (
            coveredRouteIds.contains(coverageRouteId) &&
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
              // Carry the alias into the rewrite, not just the coverage check. Synthesis looks
              // the route up by the descriptor's id and, finding no static route, tries to build
              // one from the AddedRoute extension — which NYCT does not send, so it dereferences
              // a null agency id and throws, failing the whole batch. Naming the static route the
              // realtime label denotes keeps the synthesized trip on the route it belongs to.
              var rewrittenBuilder = trip.toBuilder().setScheduleRelationship(newRel);
              if (!coverageRouteId.equals(trip.getRouteId())) {
                rewrittenBuilder.setRouteId(coverageRouteId);
              }
              var rewritten = rewrittenBuilder.build();
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
    if (trainNumberTripMatcher != null && !updates.isEmpty()) {
      LOG.info(
        "[feedIds={}] train-number matcher diag: {} (matches applied: " + trainNumberMatches + ")",
        feedIds,
        trainNumberTripMatcher.summarizeCounters()
      );
    }
    if (partialTripIdMatcher != null && !updates.isEmpty()) {
      LOG.info(
        "[feedIds={}] partial-matcher diag: {}, strippedEmptyStopTimeEvents=" +
          strippedEmptyStopTimeEvents +
          ", skippedInformationlessUpdates=" +
          skippedInformationlessUpdates +
          ", discardedDateMismatchedResolutions=" +
          discardedDateMismatchedResolutions +
          ", reanchoredStartDates=" +
          reanchoredStartDates,
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
    if (!unresolvedUncoveredRouteIds.isEmpty() && !tripReplacementPeriods.isEmpty()) {
      LOG.info(
        "[feedIds={}] unresolved RT trips on routes not covered by any active replacement period: {}",
        feedIds,
        unresolvedUncoveredRouteIds
      );
    }

    // Promote the per-batch diagnostics above from log lines to Micrometer counters
    // (issue #7): a shift in any of these is usually the first sign of an upstream feed
    // behavior change, and log-only counters are invisible to dashboards and alerts.
    String feedTag = String.join("+", feedIds);
    diagCount(feedTag, "stripped_empty_stop_time_events", strippedEmptyStopTimeEvents);
    diagCount(feedTag, "skipped_informationless_updates", skippedInformationlessUpdates);
    diagCount(feedTag, "discarded_date_mismatched_resolutions", discardedDateMismatchedResolutions);
    diagCount(feedTag, "reanchored_start_dates", reanchoredStartDates);
    diagCount(feedTag, "partial_trip_id_matches", partialTripIdMatches);
    diagCount(feedTag, "train_number_matches", trainNumberMatches);
    diagCount(feedTag, "cancelled_by_omission", cancelledByOmission);
    diagCount(feedTag, "converted_scheduled_to_added", convertedScheduledToAdded);
    diagCount(
      feedTag,
      "converted_scheduled_to_added_pattern_diverged",
      convertedScheduledToAddedDueToPatternDivergence
    );
    if (trainNumberTripMatcher != null) {
      diagCount(feedTag, "matcher_synthesized", trainNumberTripMatcher.synthesizedCount);
      diagCount(feedTag, "matcher_ambiguous", trainNumberTripMatcher.ambiguousCount);
      diagCount(feedTag, "matcher_no_candidates", trainNumberTripMatcher.noCandidatesCount);
      diagCount(
        feedTag,
        "matcher_candidates_no_active_service",
        trainNumberTripMatcher.candidatesButNoActiveServiceCount
      );
    }
    if (partialTripIdMatcher != null) {
      diagCount(feedTag, "partial_matcher_no_candidates", partialTripIdMatcher.noCandidatesCount);
      diagCount(
        feedTag,
        "partial_matcher_candidates_no_active_service",
        partialTripIdMatcher.candidatesButNoActiveServiceCount
      );
      diagCount(
        feedTag,
        "partial_matcher_route_not_found",
        partialTripIdMatcher.routeNotFoundCount
      );
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
   * Correct the service date of updates whose realtime anchoring disagrees with the static
   * schedule's anchoring of the same trip.
   * <p>
   * NYCT anchors all post-midnight realtime trips to the previous transit day, while the static
   * schedule anchors some of the same trips to the calendar date (both {@code 25:27}-on-yesterday
   * and {@code 01:27}-on-today style trips exist, interleaved). When the two anchors disagree,
   * absolute realtime times get converted to service-day-relative seconds against the wrong
   * midnight and every delay comes out {@code true_delay ± 86400}; timeless updates
   * (cancellations) silently target the wrong day's trip instance.
   * <p>
   * The correction only applies when it is provably safe — see
   * {@link #chooseStartDate(LocalDate, List, OptionalLong, int, int, java.time.ZoneId, Instant)}.
   *
   * @return the same instance when no correction applies, a rebuilt update otherwise
   */
  /**
   * True when the update resolves to a static trip whose scheduled anchor on the claimed
   * start date is more than half a day away from the update's first absolute time — i.e. no
   * start-date assignment can reconcile the resolution with the realtime times (typically:
   * the trip's true instance has no active variant in the static data, and matching latched
   * onto the adjacent day's instance). Timeless updates cannot be judged and return false.
   */
  private boolean resolutionContradictsSchedule(GtfsRealtime.TripUpdate tripUpdate, String feedId) {
    if (!tripUpdate.hasTrip()) {
      return false;
    }
    var descriptor = tripUpdate.getTrip();
    if (!descriptor.hasTripId() || descriptor.getTripId().isBlank() || !descriptor.hasStartDate()) {
      return false;
    }
    if (
      descriptor.hasScheduleRelationship() &&
      (descriptor.getScheduleRelationship() ==
          GtfsRealtime.TripDescriptor.ScheduleRelationship.NEW ||
        descriptor.getScheduleRelationship() ==
        GtfsRealtime.TripDescriptor.ScheduleRelationship.ADDED)
    ) {
      return false;
    }
    var firstRtTime = firstAbsoluteTime(tripUpdate);
    if (firstRtTime.isEmpty()) {
      return false;
    }
    var trip = transitEditorService.getTrip(new FeedScopedId(feedId, descriptor.getTripId()));
    if (trip == null) {
      return false;
    }
    LocalDate claimed;
    try {
      claimed = ServiceDateUtils.parseString(descriptor.getStartDate());
    } catch (ParseException e) {
      return false;
    }
    var pattern = transitEditorService.findPattern(trip);
    if (pattern == null) {
      return false;
    }
    var tripTimes = pattern.getScheduledTimetable().getTripTimes(trip.getId());
    if (tripTimes == null) {
      return false;
    }
    long anchor =
      ServiceDateUtils.asStartOfService(
        claimed,
        transitEditorService.getTimeZone()
      ).toEpochSecond() +
      tripTimes.getDepartureTime(0);
    long delta = firstRtTime.getAsLong() - anchor;
    // The first provided stop may be mid-trip, so delta legitimately reaches trip duration;
    // half a day cleanly separates that from a whole-day anchoring contradiction.
    if (Math.abs(delta) > Duration.ofHours(12).toSeconds()) {
      LOG.debug(
        "Discarding resolution of trip {} on {}: realtime times are {}s away from the schedule anchor",
        trip.getId(),
        claimed,
        delta
      );
      return true;
    }
    return false;
  }

  private GtfsRealtime.TripUpdate reanchorStartDate(
    GtfsRealtime.TripUpdate tripUpdate,
    String feedId
  ) {
    if (!tripUpdate.hasTrip()) {
      return tripUpdate;
    }
    var descriptor = tripUpdate.getTrip();
    if (!descriptor.hasTripId() || descriptor.getTripId().isBlank() || !descriptor.hasStartDate()) {
      return tripUpdate;
    }
    // NEW/ADDED trips create an instance rather than referencing one; their date is authoritative.
    if (
      descriptor.hasScheduleRelationship() &&
      (descriptor.getScheduleRelationship() ==
          GtfsRealtime.TripDescriptor.ScheduleRelationship.NEW ||
        descriptor.getScheduleRelationship() ==
        GtfsRealtime.TripDescriptor.ScheduleRelationship.ADDED)
    ) {
      return tripUpdate;
    }
    var trip = transitEditorService.getTrip(new FeedScopedId(feedId, descriptor.getTripId()));
    if (trip == null) {
      return tripUpdate;
    }
    LocalDate claimed;
    try {
      claimed = ServiceDateUtils.parseString(descriptor.getStartDate());
    } catch (ParseException e) {
      return tripUpdate;
    }
    var pattern = transitEditorService.findPattern(trip);
    if (pattern == null) {
      return tripUpdate;
    }
    var tripTimes = pattern.getScheduledTimetable().getTripTimes(trip.getId());
    if (tripTimes == null) {
      return tripUpdate;
    }

    var tripCalendars = transitEditorService.getTripCalendars();
    var activeDates = Stream.of(claimed, claimed.plusDays(1), claimed.minusDays(1))
      .filter(date -> tripCalendars.isActiveOn(trip.getServiceId(), date))
      .toList();

    var cancellation =
      descriptor.hasScheduleRelationship() &&
      (descriptor.getScheduleRelationship() ==
          GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED ||
        descriptor.getScheduleRelationship() ==
        GtfsRealtime.TripDescriptor.ScheduleRelationship.DELETED);
    var best = chooseStartDate(
      claimed,
      activeDates,
      firstAbsoluteTime(tripUpdate),
      cancellation,
      tripTimes.getDepartureTime(0),
      tripTimes.getArrivalTime(tripTimes.getNumStops() - 1),
      transitEditorService.getTimeZone(),
      instantNow.get()
    );
    if (best.equals(claimed)) {
      return tripUpdate;
    }
    LOG.debug(
      "Re-anchoring trip update for {} from start date {} to {}",
      trip.getId(),
      claimed,
      best
    );
    var rewritten = descriptor
      .toBuilder()
      .setStartDate(ServiceDateUtils.asCompactString(best))
      .build();
    return tripUpdate.toBuilder().setTrip(rewritten).build();
  }

  /**
   * Pick the service date the update most plausibly refers to. The rules only deviate from the
   * claimed date when the deviation is provably safe:
   * <ul>
   *   <li>When the update carries an absolute time, choose the active date under which the
   *   realtime times sit closest to the trip's schedule. Candidate anchors are ~24h apart, so
   *   within-trip offsets can never flip the choice; for a correctly anchored feed this always
   *   picks the claimed date.</li>
   *   <li>When the update is timeless (e.g. a cancellation) and the claimed date is active,
   *   keep it — unless that instance already completed more than {@link #REANCHOR_GRACE} ago
   *   and the following date is also active, in which case the update must refer to the
   *   upcoming instance (NYCT cancels tonight's post-midnight trips under yesterday's date).</li>
   *   <li>When the update is timeless and the claimed date is inactive, use the following date
   *   if active (realtime anchors to the previous transit day), else the previous one.</li>
   * </ul>
   */
  static LocalDate chooseStartDate(
    LocalDate claimed,
    List<LocalDate> activeDates,
    OptionalLong firstRtTime,
    boolean cancellation,
    int scheduledFirstDepartureSecs,
    int scheduledLastArrivalSecs,
    java.time.ZoneId zone,
    Instant now
  ) {
    if (activeDates.isEmpty() || List.of(claimed).equals(activeDates)) {
      return claimed;
    }
    if (firstRtTime.isPresent()) {
      long rt = firstRtTime.getAsLong();
      return activeDates
        .stream()
        .min(
          Comparator.comparingLong(date ->
            Math.abs(
              rt -
                (ServiceDateUtils.asStartOfService(date, zone).toEpochSecond() +
                  scheduledFirstDepartureSecs)
            )
          )
        )
        .orElse(claimed);
    }
    var next = claimed.plusDays(1);
    if (activeDates.contains(claimed)) {
      // Without absolute times the claimed date can only be overridden for cancellations:
      // delay-only updates carry no signal to disambiguate with, and relocating them would
      // move valid updates off the instance they belong to.
      if (!cancellation) {
        return claimed;
      }
      var claimedInstanceEnd = ServiceDateUtils.asStartOfService(claimed, zone)
        .toInstant()
        .plusSeconds(scheduledLastArrivalSecs);
      if (claimedInstanceEnd.plus(REANCHOR_GRACE).isBefore(now) && activeDates.contains(next)) {
        return next;
      }
      return claimed;
    }
    return activeDates.contains(next) ? next : activeDates.getFirst();
  }

  /**
   * The first absolute arrival or departure time carried by the update, if any.
   */
  private static OptionalLong firstAbsoluteTime(GtfsRealtime.TripUpdate tripUpdate) {
    for (var stu : tripUpdate.getStopTimeUpdateList()) {
      if (stu.hasArrival() && stu.getArrival().hasTime()) {
        return OptionalLong.of(stu.getArrival().getTime());
      }
      if (stu.hasDeparture() && stu.getDeparture().hasTime()) {
        return OptionalLong.of(stu.getDeparture().getTime());
      }
    }
    return OptionalLong.empty();
  }

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

  /**
   * Cumulative per-feed diagnostic counter (prometheus: {@code trip_updates_diag_<name>_total}).
   * Micrometer caches meter instances by name+tags, so calling through here per batch is cheap.
   */
  private static void diagCount(String feedTag, String name, int amount) {
    if (amount > 0) {
      Metrics.counter("trip.updates.diag." + name, "feedId", feedTag).increment(amount);
    }
  }
}
