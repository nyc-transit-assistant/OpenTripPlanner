package org.opentripplanner.updater.trip.gtfs;

import com.google.transit.realtime.NyctSubway;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A time window during which a GTFS-realtime feed is the authoritative source for a single
 * route — any scheduled trip on that route departing within the window that is not present in
 * the realtime feed should be treated as cancelled.
 * <p>
 * Originates in the {@code NyctFeedHeader.trip_replacement_period} extension defined by the MTA
 * NYC Subway GTFS-realtime profile, but the concept is generic enough to apply to any agency
 * that opts into cancel-by-omission semantics.
 */
public record TripReplacementPeriod(String routeId, Instant endTime) {
  /**
   * If a feed's {@code header.timestamp} is older than this, we don't trust any of its claimed
   * replacement-period authority — likely a cached or stuck upstream.
   */
  private static final Duration MAX_FEED_AGE = Duration.ofMinutes(5);

  /**
   * NYCT spec value when a feed publishes a degenerate {@code end} (== or before
   * header.timestamp). Matches the L feed's actual look-ahead and the documented intent.
   */
  private static final Duration DEFAULT_LOOKAHEAD = Duration.ofMinutes(30);

  public static List<TripReplacementPeriod> fromNyctFeedHeader(
    com.google.transit.realtime.GtfsRealtime.FeedHeader header
  ) {
    return fromNyctFeedHeader(header, Instant.now());
  }

  /**
   * NYCT route aliases: when a replacement period covers the key route, also cover each
   * value route. The IRT feed lists e.g. "6" and "7" but the trip descriptors use "6X" and
   * "7X" for the express variants on those lines, and "S" / "GS" for the shuttle.
   */
  private static final java.util.Map<String, java.util.List<String>> ROUTE_ALIASES =
    java.util.Map.of(
      "S",
      java.util.List.of("GS"),
      "6",
      java.util.List.of("6X"),
      "7",
      java.util.List.of("7X")
    );

  /**
   * Pull the {@code trip_replacement_period} list off the NYCT extension on the FeedHeader,
   * if present. Returns an empty list when the proto extension is absent, the feed declares
   * no periods, or the feed itself is stale.
   * <p>
   * Two NYCT data quirks are corrected here:
   * <ul>
   *   <li>The IRT (1234567+S) feed publishes {@code end == header.timestamp}, granting zero
   *       forward authority. When this happens we substitute the documented 30-minute default
   *       look-ahead so the feed actually covers the trips it's reporting.</li>
   *   <li>The IRT feed lists e.g. {@code "6"} and {@code "7"} but trip descriptors use
   *       {@code "6X"} and {@code "7X"} for the express variants of those lines, and
   *       {@code "S"} for the shuttle while the trips use {@code "GS"}. We emit parallel
   *       periods under each alias so coverage matches the trip data.</li>
   * </ul>
   * If the feed header timestamp is missing or older than 5 minutes, the period list is
   * dropped entirely — a stale feed shouldn't be granted authority over the schedule.
   */
  public static List<TripReplacementPeriod> fromNyctFeedHeader(
    com.google.transit.realtime.GtfsRealtime.FeedHeader header,
    Instant now
  ) {
    if (!header.hasExtension(NyctSubway.nyctFeedHeader)) {
      return List.of();
    }
    var ext = header.getExtension(NyctSubway.nyctFeedHeader);
    if (ext.getTripReplacementPeriodCount() == 0) {
      return List.of();
    }
    if (!header.hasTimestamp()) {
      return List.of();
    }
    var headerTs = Instant.ofEpochSecond(header.getTimestamp());
    if (Duration.between(headerTs, now).compareTo(MAX_FEED_AGE) > 0) {
      return List.of();
    }

    var out = new ArrayList<TripReplacementPeriod>(ext.getTripReplacementPeriodCount());
    for (var period : ext.getTripReplacementPeriodList()) {
      if (!period.hasRouteId() || !period.hasReplacementPeriod()) {
        continue;
      }
      var range = period.getReplacementPeriod();
      if (!range.hasEnd()) {
        continue;
      }
      var end = Instant.ofEpochSecond(range.getEnd());
      if (!end.isAfter(headerTs)) {
        end = headerTs.plus(DEFAULT_LOOKAHEAD);
      }
      var routeId = period.getRouteId();
      out.add(new TripReplacementPeriod(routeId, end));
      var aliases = ROUTE_ALIASES.get(routeId);
      if (aliases != null) {
        for (var alias : aliases) {
          out.add(new TripReplacementPeriod(alias, end));
        }
      }
    }
    return List.copyOf(out);
  }
}
