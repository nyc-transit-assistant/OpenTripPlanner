package org.opentripplanner.updater.trip.gtfs;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.service.TransitService;
import org.opentripplanner.utils.time.ServiceDateUtils;
import org.opentripplanner.utils.time.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves GTFS-RT trips whose realtime identity is a <em>train number</em> rather than a static
 * trip id.
 * <p>
 * MTA Metro-North's realtime feed emits an internal {@code trip_id} (e.g. {@code 3174341}) that
 * appears nowhere in the published static GTFS, so exact lookup fails for every update. The
 * train number is published as the {@code FeedEntity} id (trip updates) and as the vehicle
 * descriptor's label (vehicle positions), and matches static {@code trips.txt}'s
 * {@code trip_short_name} — where (train number, service date) is unique (verified: zero
 * collisions across the full schedule).
 * <p>
 * Callers pass the train number explicitly, since where it lives differs by container. On a
 * match a new {@code TripDescriptor} with the static trip id substituted is returned; otherwise
 * the original descriptor is returned unchanged so the regular lookup path decides what to do.
 * The stock {@link GtfsRealtimeFuzzyTripMatcher} cannot cover this case: it requires a
 * {@code direction_id}, which Metro-North does not send, and start-time equality, which is not
 * guaranteed.
 * <p>
 * Like {@link GtfsRealtimePartialTripIdMatcher}, an instance is built fresh per graph-writer run;
 * the short-name index is built lazily per feed on first use (static data cannot change within a
 * run).
 */
public class GtfsRealtimeTrainNumberTripMatcher {

  private static final Logger LOG = LoggerFactory.getLogger(
    GtfsRealtimeTrainNumberTripMatcher.class
  );

  /** Allowed drift between the realtime start_time and the static scheduled departure. */
  private static final int TIME_AGREEMENT_TOLERANCE = 120;

  private final TransitService transitService;

  /**
   * When non-null, unresolved trains that are ADDED or carry no trip id are synthesized:
   * they get the deterministic id {@code <idPrefix><train>-<date>} (so the same physical
   * train keeps one identity across polling cycles), the configured route when the
   * descriptor has none, and stay ADDED so downstream code builds them as extra service.
   * This is the NJT rail case: genuine unscheduled event shuttles (Meadowlands) appear as
   * ADDED entities with blank trip ids and no route.
   */
  @Nullable
  private final SynthesisConfig synthesis;

  public record SynthesisConfig(String idPrefix, @Nullable String defaultRouteId) {}

  /** feedId -> normalized train number -> trips carrying that trip_short_name. */
  private final Map<String, Map<String, List<Trip>>> indexByFeed = new HashMap<>();

  // Per-batch diagnostic counters, mirroring GtfsRealtimePartialTripIdMatcher.
  public int callCount;
  public int missingTrainNumberCount;
  public int alreadyResolvedCount;
  public int parseStartDateFailedCount;
  public int noCandidatesCount;
  public int candidatesButNoActiveServiceCount;
  public int ambiguousCount;
  public int matchedCount;
  public int matchedOnAlternateDateCount;
  public int synthesizedCount;

  public GtfsRealtimeTrainNumberTripMatcher(TransitService transitService) {
    this(transitService, null);
  }

  public GtfsRealtimeTrainNumberTripMatcher(
    TransitService transitService,
    @Nullable SynthesisConfig synthesis
  ) {
    this.transitService = transitService;
    this.synthesis = synthesis;
  }

  public String summarizeCounters() {
    return String.format(
      "calls=%d, matched=%d (alternateDate=%d, synthesized=%d, ambiguous=%d), missingTrainNumber=%d, alreadyResolved=%d, parseStartDateFailed=%d, noCandidates=%d, candidatesButNoActiveService=%d",
      callCount,
      matchedCount,
      matchedOnAlternateDateCount,
      synthesizedCount,
      ambiguousCount,
      missingTrainNumberCount,
      alreadyResolvedCount,
      parseStartDateFailedCount,
      noCandidatesCount,
      candidatesButNoActiveServiceCount
    );
  }

  /**
   * Resolve {@code trainNumber} + the descriptor's start date to a static trip and return a
   * descriptor rewritten with its trip id. When resolution fails the original descriptor is
   * returned unchanged.
   *
   * @param trainNumber the realtime train number as sent (leading zeros tolerated); for trip
   *                    updates this is the {@code FeedEntity} id, for vehicle positions the
   *                    vehicle descriptor's label.
   */
  public TripDescriptor match(String feedId, String trainNumber, TripDescriptor trip) {
    callCount++;
    if (
      trip.hasTripId() && transitService.containsTrip(new FeedScopedId(feedId, trip.getTripId()))
    ) {
      alreadyResolvedCount++;
      return trip;
    }
    String normalized = normalizeTrainNumber(trainNumber);
    if (normalized == null) {
      missingTrainNumberCount++;
      return trip;
    }
    // Metro-North's vehicle positions carry no start_date at all (only the trip updates do);
    // fall back to today in the transit model's zone — the ±1 day loop below covers trains
    // spanning midnight either way.
    LocalDate serviceDate;
    if (trip.hasStartDate()) {
      try {
        serviceDate = ServiceDateUtils.parseString(trip.getStartDate());
      } catch (ParseException e) {
        parseStartDateFailedCount++;
        return trip;
      }
    } else {
      serviceDate = LocalDate.now(transitService.getTimeZone());
    }

    List<Trip> candidates = indexFor(feedId).get(normalized);
    if (candidates == null || candidates.isEmpty()) {
      noCandidatesCount++;
      if (noCandidatesCount == 1) {
        LOG.warn(
          "Train-number match: no static trip in feed {} has trip_short_name {} (rt trip {}, start date {})",
          feedId,
          normalized,
          trip.getTripId(),
          serviceDate
        );
      }
      return synthesizeIfEligible(trip, normalized, serviceDate);
    }
    // Vehicle positions omit route_id; when the trip-update side supplies it, use it as an
    // extra guard against short-name collisions across routes.
    List<Trip> routeScoped = trip.hasRouteId()
      ? candidates
          .stream()
          .filter(t -> t.getRoute().getId().getId().equals(trip.getRouteId()))
          .toList()
      : candidates;
    if (routeScoped.isEmpty()) {
      routeScoped = candidates;
    }

    // Metro-North anchors post-midnight trains inconsistently between realtime and static: a
    // 00:08 departure claimed on start_date D frequently exists in static as a 24:08 trip on
    // service date D-1. Resolving it onto a D-anchored instance puts the applied times ±24h
    // off (the handler's contradiction check then reverts the whole resolution). So when the
    // descriptor carries a start_time, prefer the (date, trip) anchor whose scheduled first
    // departure agrees with it — same clock-shift arithmetic the fuzzy matcher uses — and
    // only fall back to service-membership alone when no anchor is time-consistent.
    var candidateDates = List.of(serviceDate, serviceDate.minusDays(1), serviceDate.plusDays(1));
    Integer claimedTime = trip.hasStartTime() ? TimeUtils.time(trip.getStartTime()) : null;
    for (boolean requireTimeAgreement : claimedTime != null
      ? List.of(true, false)
      : List.of(false)) {
      for (var candidateDate : candidateDates) {
        var activeServiceIds = transitService
          .getTripCalendars()
          .listServiceIdsOnServiceDate(candidateDate);
        int dayShiftSeconds = (int) (candidateDate.until(serviceDate).getDays()) * 24 * 60 * 60;
        List<Trip> active = routeScoped
          .stream()
          .filter(t -> activeServiceIds.contains(t.getServiceId()))
          .filter(t -> {
            if (!requireTimeAgreement) {
              return true;
            }
            Integer scheduled = scheduledFirstDepartureSeconds(t);
            return (
              scheduled != null &&
              Math.abs(scheduled - (claimedTime + dayShiftSeconds)) <= TIME_AGREEMENT_TOLERANCE
            );
          })
          .toList();
        if (active.isEmpty()) {
          continue;
        }
        if (active.size() > 1) {
          // Should not happen for MNR (verified unique); note it and take the first candidate
          // deterministically rather than dropping the update.
          ambiguousCount++;
        }
        matchedCount++;
        if (!candidateDate.equals(serviceDate)) {
          matchedOnAlternateDateCount++;
        }
        var builder = trip.toBuilder().setTripId(active.getFirst().getId().getId());
        if (trip.hasStartDate() && !candidateDate.equals(serviceDate)) {
          builder.setStartDate(ServiceDateUtils.asCompactString(candidateDate));
        }
        return builder.build();
      }
    }
    candidatesButNoActiveServiceCount++;
    if (candidatesButNoActiveServiceCount == 1) {
      LOG.warn(
        "Train-number match: {} candidate(s) for train {} in feed {} but none active on {} (±1d)",
        routeScoped.size(),
        normalized,
        feedId,
        serviceDate
      );
    }
    return synthesizeIfEligible(trip, normalized, serviceDate);
  }

  /**
   * Unresolvable train: when synthesis is configured and the entity is ADDED or carries no
   * trip id (a SCHEDULED entity with a real-but-unresolvable id is left for the regular
   * lookup path to report), give it a deterministic identity so it can be built as an added
   * trip and keeps that identity across polling cycles.
   */
  private TripDescriptor synthesizeIfEligible(
    TripDescriptor trip,
    String trainNumber,
    LocalDate serviceDate
  ) {
    if (synthesis == null) {
      return trip;
    }
    boolean eligible =
      trip.getScheduleRelationship() == ScheduleRelationship.ADDED ||
      !trip.hasTripId() ||
      trip.getTripId().isBlank();
    if (!eligible) {
      return trip;
    }
    var builder = trip
      .toBuilder()
      .setTripId(
        synthesis.idPrefix() + trainNumber + "-" + ServiceDateUtils.asCompactString(serviceDate)
      )
      .setScheduleRelationship(ScheduleRelationship.ADDED);
    if (!trip.hasRouteId() && synthesis.defaultRouteId() != null) {
      builder.setRouteId(synthesis.defaultRouteId());
    }
    synthesizedCount++;
    return builder.build();
  }

  /** Scheduled departure at the first stop, in seconds from service-date midnight. */
  private Integer scheduledFirstDepartureSeconds(Trip trip) {
    var pattern = transitService.findPattern(trip);
    if (pattern == null) {
      return null;
    }
    var tripTimes = pattern.getScheduledTimetable().getTripTimes(trip);
    return tripTimes == null ? null : tripTimes.getScheduledDepartureTime(0);
  }

  private Map<String, List<Trip>> indexFor(String feedId) {
    return indexByFeed.computeIfAbsent(feedId, fid -> {
      Map<String, List<Trip>> index = new HashMap<>();
      for (Trip trip : transitService.listTrips()) {
        if (!trip.getId().getFeedId().equals(fid)) {
          continue;
        }
        String shortName = normalizeTrainNumber(trip.getShortName());
        if (shortName == null) {
          continue;
        }
        index.computeIfAbsent(shortName, k -> new java.util.ArrayList<>()).add(trip);
      }
      LOG.info("Train-number match: indexed {} train numbers for feed {}", index.size(), fid);
      return index;
    });
  }

  /** Trimmed, leading zeros stripped; null when blank. */
  static String normalizeTrainNumber(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim().replaceFirst("^0+(?=\\d)", "");
    return trimmed.isEmpty() ? null : trimmed;
  }
}
