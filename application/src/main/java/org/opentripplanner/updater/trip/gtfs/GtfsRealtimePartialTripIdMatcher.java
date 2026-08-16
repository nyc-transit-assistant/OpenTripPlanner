package org.opentripplanner.updater.trip.gtfs;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  /**
   * The static route id that a realtime route id denotes, for callers comparing a realtime route
   * against static-keyed data — notably the {@code trip_replacement_period} coverage check, which
   * would otherwise read SIR's realtime-only {@code SS} label as a route NYCT never claimed
   * authority over, and drop those trips instead of synthesizing them.
   * <p>
   * Returns the id unchanged when no alias applies. Safe to call unconditionally because the
   * aliases only cover realtime ids with no static route of their own.
   */
  static String staticRouteId(String realtimeRouteId) {
    return ROUTE_ALIASES.getOrDefault(realtimeRouteId, realtimeRouteId);
  }

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
  public int matchedOnAlternateDateCount;

  public GtfsRealtimePartialTripIdMatcher(TransitService transitService) {
    this.transitService = transitService;
  }

  public String summarizeCounters() {
    return String.format(
      "calls=%d, matched=%d (variantSuffix=%d, alternateDate=%d), missingFields=%d, alreadyResolved=%d, parseStartDateFailed=%d, routeNotFound=%d, noCandidates=%d, candidatesButNoActiveService=%d",
      callCount,
      matchedCount,
      matchedByVariantSuffixCount,
      matchedOnAlternateDateCount,
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

    // The inner test is a loop rather than suffixes.stream().anyMatch(): this runs once per trip
    // on the route for every realtime update, and allocation profiling attributed roughly a third
    // of the whole JVM's allocation to the Stream and spliterator built here — to examine one or
    // two strings. anyMatch over a short list is a short-circuiting OR, so this is the same test.
    var exactCandidates = allTripsOnRoute
      .stream()
      .filter(t -> endsWithAny(t, suffixes))
      .toList();

    // Fuzzy fallback: NYCT's L feed (and others) strips the route-variant suffix from the trip
    // id, sending e.g. `128650_L..S` while the static GTFS uses `..._128650_L..S01R`. Try
    // matching where the static id continues past the rt id with an alphanumeric variant
    // suffix and no underscore (so we don't accidentally cross a id segment boundary).
    //
    // Computed lazily, and at most once per call: building it compiles a regex per suffix and
    // runs it against every trip on the route, which profiling showed to be the single largest
    // consumer of CPU in the realtime apply path — while the large majority of updates resolve
    // on the exact-suffix path above and never look at it.
    List<Trip> fuzzyCandidates = null;

    // NYCT anchors all post-midnight realtime trips to the previous transit day, while parts of
    // the static schedule anchor the same trips to the calendar date. A trip that has no active
    // service on the claimed start date therefore frequently runs on the following date (and,
    // for the reverse anchoring mismatch, the previous one). Try the claimed date first, then
    // its neighbours; when a match is found on an alternate date, rewrite start_date as well so
    // downstream service-day anchoring is consistent with the matched static trip.
    Set<FeedScopedId> serviceIdsOnDate = null;
    for (var candidateDate : List.of(
      serviceDate,
      serviceDate.plusDays(1),
      serviceDate.minusDays(1)
    )) {
      var activeServiceIds = transitService
        .getTripCalendars()
        .listServiceIdsOnServiceDate(candidateDate);
      if (serviceIdsOnDate == null) {
        serviceIdsOnDate = activeServiceIds;
      }

      Trip match = exactCandidates
        .stream()
        .filter(t -> activeServiceIds.contains(t.getServiceId()))
        .findFirst()
        .orElse(null);

      boolean variantSuffixMatch = false;
      if (match == null) {
        if (fuzzyCandidates == null) {
          fuzzyCandidates = fuzzyCandidates(suffixes, allTripsOnRoute);
        }
        match = fuzzyCandidates
          .stream()
          .filter(t -> activeServiceIds.contains(t.getServiceId()))
          .min(Comparator.comparing(GtfsRealtimePartialTripIdMatcher::variantSortKey))
          .orElse(null);
        variantSuffixMatch = match != null;
      }

      if (match != null) {
        matchedCount++;
        if (variantSuffixMatch) {
          matchedByVariantSuffixCount++;
        }
        if (!candidateDate.equals(serviceDate)) {
          matchedOnAlternateDateCount++;
        }
        return rewritten(trip, match, effectiveRouteId, candidateDate, serviceDate);
      }
    }

    // Only the empty-exact branch reads the fuzzy set, so it stays unbuilt when exact candidates
    // exist — including on the path where they exist but none had active service.
    if (exactCandidates.isEmpty() && fuzzyCandidates == null) {
      fuzzyCandidates = fuzzyCandidates(suffixes, allTripsOnRoute);
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
    String effectiveRouteId,
    LocalDate matchedDate,
    LocalDate claimedDate
  ) {
    var builder = trip.toBuilder().setTripId(match.getId().getId());
    if (!effectiveRouteId.equals(trip.getRouteId())) {
      builder.setRouteId(effectiveRouteId);
    }
    if (!matchedDate.equals(claimedDate)) {
      builder.setStartDate(ServiceDateUtils.asCompactString(matchedDate));
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
   * Trips whose id continues past one of the suffixes with an alphanumeric route-variant suffix
   * and no underscore, so we don't accidentally cross an id segment boundary.
   * <p>
   * Extracted so callers can defer it: it compiles a pattern per suffix and applies it to every
   * trip on the route, and most updates match on the exact suffix and never need it.
   */
  private static List<Trip> fuzzyCandidates(List<String> suffixes, List<Trip> allTripsOnRoute) {
    var variantPatterns = suffixes
      .stream()
      .map(s -> Pattern.compile(".*" + Pattern.quote(s) + "(?<variant>[A-Z0-9]+)$"))
      .toList();
    return allTripsOnRoute
      .stream()
      .filter(t -> matchesAny(t, variantPatterns))
      .toList();
  }

  /** True when the trip's id ends with any of the suffixes. */
  private static boolean endsWithAny(Trip trip, List<String> suffixes) {
    var id = trip.getId().getId();
    for (var suffix : suffixes) {
      if (id.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  /** True when the trip's id matches any of the variant-suffix patterns. */
  private static boolean matchesAny(Trip trip, List<Pattern> patterns) {
    var id = trip.getId().getId();
    for (var pattern : patterns) {
      if (pattern.matcher(id).matches()) {
        return true;
      }
    }
    return false;
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
