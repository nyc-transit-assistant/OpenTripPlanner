package org.opentripplanner.updater.trip.gtfs;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.service.TransitService;
import org.opentripplanner.utils.time.ServiceDateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves partial GTFS-RT trip ids back to their full static GTFS counterpart.
 * <p>
 * Some agencies — most notably the MTA New York City Subway — emit a {@code trip_id} in the
 * GTFS-realtime feed that is a suffix of the corresponding static {@code trip_id}. For example,
 * static {@code A20111204SAT_021150_2..N08R} appears in the realtime feed as
 * {@code 021150_2..N08R}: the prefix {@code A20111204SAT_} (sub-division, base-schedule date,
 * service code) is stripped. The MTA spec guarantees the suffix is unique within a day type.
 * <p>
 * Stock OTP looks up trips by exact {@link FeedScopedId} match, so these updates would all fail
 * with {@code TRIP_NOT_FOUND}. This matcher is intended to run before the standard lookup: it
 * inspects the {@code TripDescriptor}, finds the static trip on the same route whose id ends with
 * the realtime id and whose service is active on the given start date, and returns a new
 * {@code TripDescriptor} with the full trip id substituted in.
 * <p>
 * If no candidate is found the original {@code TripDescriptor} is returned unchanged so the
 * regular lookup path can decide what to do (typically: report {@code TRIP_NOT_FOUND}).
 */
public class GtfsRealtimePartialTripIdMatcher {

  private static final Logger LOG = LoggerFactory.getLogger(GtfsRealtimePartialTripIdMatcher.class);

  private final TransitService transitService;

  /**
   * Realtime route ids NYCT uses that don't exist in the static GTFS. The SIR feed labels
   * shuttle-pattern trains {@code SS} while the static feed only has {@code SI}.
   */
  private static final Map<String, String> ROUTE_ALIASES = Map.of("SS", "SI");

  // Per-batch diagnostic counters. Reset implicitly because a fresh matcher is built per
  // graph-writer run.
  public int callCount;
  public int missingFieldsCount;
  public int alreadyResolvedCount;
  public int parseStartDateFailedCount;
  public int routeNotFoundCount;
  public int noCandidatesCount;
  public int candidatesButNoActiveServiceCount;
  public int matchedCount;
  public int matchedByVariantSuffixCount;

  public GtfsRealtimePartialTripIdMatcher(TransitService transitService) {
    this.transitService = transitService;
  }

  public String summarizeCounters() {
    return String.format(
      "calls=%d, matched=%d (variantSuffix=%d), missingFields=%d, alreadyResolved=%d, parseStartDateFailed=%d, routeNotFound=%d, noCandidates=%d, candidatesButNoActiveService=%d",
      callCount,
      matchedCount,
      matchedByVariantSuffixCount,
      missingFieldsCount,
      alreadyResolvedCount,
      parseStartDateFailedCount,
      routeNotFoundCount,
      noCandidatesCount,
      candidatesButNoActiveServiceCount
    );
  }

  /**
   * If the {@code trip_id} on {@code trip} doesn't directly resolve in the static schedule,
   * try to find a static trip whose id ends with the realtime id (delimited by {@code _}) and
   * whose service runs on the descriptor's start date.
   *
   * @return a possibly rewritten {@code TripDescriptor} — same instance if no match was found,
   *         a new builder copy with the full trip id otherwise.
   */
  public TripDescriptor match(String feedId, TripDescriptor trip) {
    callCount++;
    if (!trip.hasTripId() || !trip.hasRouteId() || !trip.hasStartDate()) {
      missingFieldsCount++;
      return trip;
    }
    var realtimeTripId = trip.getTripId();
    if (transitService.containsTrip(new FeedScopedId(feedId, realtimeTripId))) {
      alreadyResolvedCount++;
      return trip;
    }

    LocalDate serviceDate;
    try {
      serviceDate = ServiceDateUtils.parseString(trip.getStartDate());
    } catch (ParseException e) {
      parseStartDateFailedCount++;
      return trip;
    }

    var effectiveRouteId = trip.getRouteId();
    Route route = transitService.getRoute(new FeedScopedId(feedId, effectiveRouteId));
    if (route == null && ROUTE_ALIASES.containsKey(effectiveRouteId)) {
      effectiveRouteId = ROUTE_ALIASES.get(effectiveRouteId);
      route = transitService.getRoute(new FeedScopedId(feedId, effectiveRouteId));
    }
    if (route == null) {
      routeNotFoundCount++;
      return trip;
    }

    var serviceIdsOnDate = transitService.getCalendarService().getServiceIdsOnDate(serviceDate);
    // The path separator's dot count is not stable between realtime and static ids: the SIR
    // realtime feed sends `116600_SI.N03R` while the active static schedule uses
    // `..._116600_SI..N03R` (and older supplement trips the single-dot form). Try the id as
    // sent first, then its dot-swapped twin.
    var suffixes = tripIdDotAlternates(realtimeTripId)
      .stream()
      .map(id -> "_" + id)
      .toList();

    var allTripsOnRoute = transitService
      .findPatterns(route)
      .stream()
      .flatMap(p -> p.scheduledTripsAsStream())
      .toList();

    var exactCandidates = allTripsOnRoute
      .stream()
      .filter(t -> suffixes.stream().anyMatch(s -> t.getId().getId().endsWith(s)))
      .toList();

    Trip match = exactCandidates
      .stream()
      .filter(t -> serviceIdsOnDate.contains(t.getServiceId()))
      .findFirst()
      .orElse(null);

    if (match != null) {
      matchedCount++;
      return rewritten(trip, match, effectiveRouteId);
    }

    // Fuzzy fallback: NYCT's L feed (and others) strips the route-variant suffix from the trip
    // id, sending e.g. `128650_L..S` while the static GTFS uses `..._128650_L..S01R`. Try
    // matching where the static id continues past the rt id with an alphanumeric variant
    // suffix and no underscore (so we don't accidentally cross a id segment boundary).
    var variantPatterns = suffixes
      .stream()
      .map(s -> Pattern.compile(".*" + Pattern.quote(s) + "(?<variant>[A-Z0-9]+)$"))
      .toList();
    var fuzzyCandidates = allTripsOnRoute
      .stream()
      .filter(t -> variantPatterns.stream().anyMatch(p -> p.matcher(t.getId().getId()).matches()))
      .toList();
    Trip fuzzyMatch = fuzzyCandidates
      .stream()
      .filter(t -> serviceIdsOnDate.contains(t.getServiceId()))
      .min(Comparator.comparing(GtfsRealtimePartialTripIdMatcher::variantSortKey))
      .orElse(null);

    if (fuzzyMatch != null) {
      matchedCount++;
      matchedByVariantSuffixCount++;
      return rewritten(trip, fuzzyMatch, effectiveRouteId);
    }

    var allCandidates = exactCandidates.isEmpty() ? fuzzyCandidates : exactCandidates;
    if (allCandidates.isEmpty()) {
      noCandidatesCount++;
      if (noCandidatesCount == 1) {
        LOG.warn(
          "Partial match: no static trip on route {} ends with suffix(es) {} (rt trip {}, service date {})",
          trip.getRouteId(),
          suffixes,
          realtimeTripId,
          serviceDate
        );
      }
    } else {
      candidatesButNoActiveServiceCount++;
      if (candidatesButNoActiveServiceCount == 1) {
        LOG.warn(
          "Partial match: {} candidate(s) on route {} match suffix(es) {} (or with variant) but none have a service id active on {} — candidates' service ids: {}, active service ids count: {}",
          allCandidates.size(),
          trip.getRouteId(),
          suffixes,
          serviceDate,
          allCandidates
            .stream()
            .map(t -> t.getServiceId().toString())
            .toList(),
          serviceIdsOnDate.size()
        );
      }
    }
    return trip;
  }

  private static TripDescriptor rewritten(
    TripDescriptor trip,
    Trip match,
    String effectiveRouteId
  ) {
    var builder = trip.toBuilder().setTripId(match.getId().getId());
    if (!effectiveRouteId.equals(trip.getRouteId())) {
      builder.setRouteId(effectiveRouteId);
    }
    return builder.build();
  }

  /**
   * The realtime id as sent plus, when it contains a dot-run path separator
   * ({@code 116600_SI.N03R} / {@code 110400_L..N}), the same id with the opposite dot count —
   * NYCT is not consistent about which form a feed uses versus the static schedule.
   */
  static List<String> tripIdDotAlternates(String realtimeTripId) {
    if (realtimeTripId.contains("..")) {
      return List.of(realtimeTripId, realtimeTripId.replaceFirst("\\.\\.", "."));
    }
    if (realtimeTripId.contains(".")) {
      return List.of(realtimeTripId, realtimeTripId.replaceFirst("\\.", ".."));
    }
    return List.of(realtimeTripId);
  }

  /**
   * Sort key for picking among multiple route-variant candidates. Prefers shorter variants and,
   * within the same length, prefers `01R` (the regular daily pattern) over alternates.
   */
  static String variantSortKey(Trip candidate) {
    var id = candidate.getId().getId();
    var underscore = id.lastIndexOf('_');
    var lastSegment = underscore >= 0 ? id.substring(underscore + 1) : id;
    var dotDot = lastSegment.indexOf("..");
    var variant = dotDot >= 0 && dotDot + 3 < lastSegment.length()
      ? lastSegment.substring(dotDot + 3)
      : "";
    // Ordering: by length first, then alphabetically. `01R` (3 chars) < `02R` (3) < `01X004` (6).
    return String.format("%02d%s", variant.length(), variant);
  }
}
