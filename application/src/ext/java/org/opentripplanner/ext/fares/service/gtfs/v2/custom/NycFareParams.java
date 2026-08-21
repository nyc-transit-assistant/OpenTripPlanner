package org.opentripplanner.ext.fares.service.gtfs.v2.custom;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.Serializable;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.opentripplanner.core.model.id.FeedScopedId;

/**
 * Configuration for the NYC (OMNY) fare service. The MTA's fare system spans several GTFS
 * datasets (the subway feed and the borough bus feeds), so the parameters name the feeds that
 * share the single OMNY fare context and the tariff's designated free out-of-system
 * subway-to-subway transfer pairs (Passenger Tariff supplement, Appendix I).
 *
 * <pre>
 * "fares": {
 *   "type": "nyc",
 *   "subwayFeed": "mta-subway",
 *   "omnyFeeds": ["mta-subway", "mta-bus-bronx", ...],
 *   "freeOutOfSystemTransfers": [["mta-subway:CX613", "mta-subway:B08"]],
 *   "transferWindowMinutes": 120
 * }
 * </pre>
 */
public record NycFareParams(
  String subwayFeed,
  Set<String> omnyFeeds,
  Set<OosPair> freeOutOfSystemTransfers,
  Duration transferWindow
) implements Serializable {
  public NycFareParams {
    Objects.requireNonNull(subwayFeed);
    Objects.requireNonNull(omnyFeeds);
    Objects.requireNonNull(freeOutOfSystemTransfers);
    Objects.requireNonNull(transferWindow);
    if (!omnyFeeds.contains(subwayFeed)) {
      throw new IllegalArgumentException("omnyFeeds must contain the subway feed " + subwayFeed);
    }
  }

  /** An unordered pair of station (or stop) ids joined by a free out-of-system transfer. */
  public record OosPair(FeedScopedId a, FeedScopedId b) implements Serializable {
    public boolean matches(FeedScopedId x, FeedScopedId y) {
      return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
    }
  }

  public static NycFareParams fromConfig(JsonNode config) {
    var subwayFeed = config.path("subwayFeed").asText(null);
    if (subwayFeed == null) {
      throw new IllegalArgumentException("fares config of type 'nyc' requires 'subwayFeed'");
    }
    var omnyFeeds = new HashSet<String>();
    for (JsonNode feed : config.path("omnyFeeds")) {
      omnyFeeds.add(feed.asText());
    }
    var pairs = new HashSet<OosPair>();
    for (JsonNode pair : config.path("freeOutOfSystemTransfers")) {
      if (pair.size() != 2) {
        throw new IllegalArgumentException(
          "freeOutOfSystemTransfers entries must be pairs of feed-scoped ids: " + pair
        );
      }
      pairs.add(
        new OosPair(
          FeedScopedId.parseStrict(pair.get(0).asText()),
          FeedScopedId.parseStrict(pair.get(1).asText())
        )
      );
    }
    var minutes = config.path("transferWindowMinutes").asInt(120);
    return new NycFareParams(subwayFeed, omnyFeeds, pairs, Duration.ofMinutes(minutes));
  }
}
