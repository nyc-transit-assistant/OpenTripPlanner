package org.opentripplanner.ext.fares.service.gtfs.v2.custom;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
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
 *   "transferWindowMinutes": 120,
 *   "reducedFarePeakExclusion": {
 *     "productId": "express_single",
 *     "peakWindows": ["06:00-10:00", "15:00-19:00"]
 *   }
 * }
 * </pre>
 */
public record NycFareParams(
  String subwayFeed,
  Set<String> omnyFeeds,
  Set<OosPair> freeOutOfSystemTransfers,
  Duration transferWindow,
  @Nullable ReducedFarePeakExclusion reducedFarePeakExclusion,
  Set<String> railroadFeeds
) implements Serializable {
  public NycFareParams {
    Objects.requireNonNull(subwayFeed);
    Objects.requireNonNull(omnyFeeds);
    Objects.requireNonNull(freeOutOfSystemTransfers);
    Objects.requireNonNull(transferWindow);
    Objects.requireNonNull(railroadFeeds);
    if (!omnyFeeds.contains(subwayFeed)) {
      throw new IllegalArgumentException("omnyFeeds must contain the subway feed " + subwayFeed);
    }
  }

  public NycFareParams(
    String subwayFeed,
    Set<String> omnyFeeds,
    Set<OosPair> freeOutOfSystemTransfers,
    Duration transferWindow
  ) {
    this(subwayFeed, omnyFeeds, freeOutOfSystemTransfers, transferWindow, null, Set.of());
  }

  public NycFareParams(
    String subwayFeed,
    Set<String> omnyFeeds,
    Set<OosPair> freeOutOfSystemTransfers,
    Duration transferWindow,
    @Nullable ReducedFarePeakExclusion reducedFarePeakExclusion
  ) {
    this(
      subwayFeed,
      omnyFeeds,
      freeOutOfSystemTransfers,
      transferWindow,
      reducedFarePeakExclusion,
      Set.of()
    );
  }

  /** An unordered pair of station (or stop) ids joined by a free out-of-system transfer. */
  public record OosPair(FeedScopedId a, FeedScopedId b) implements Serializable {
    public boolean matches(FeedScopedId x, FeedScopedId y) {
      return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
    }
  }

  /**
   * The reduced fare on the named product (by local id) is valid off-peak only; during the peak
   * windows the reduced rider pays the full (highest) price of the same product. NYC: reduced
   * express bus fare is off-peak only (weekday 6–10 a.m. and 3–7 p.m. peaks charge full fare).
   */
  public record ReducedFarePeakExclusion(
    String productId,
    String reducedCategory,
    List<PeakWindow> peakWindows,
    Set<DayOfWeek> days,
    ZoneId timezone
  ) implements Serializable {
    public ReducedFarePeakExclusion {
      Objects.requireNonNull(productId);
      Objects.requireNonNull(reducedCategory);
      Objects.requireNonNull(peakWindows);
      Objects.requireNonNull(days);
      Objects.requireNonNull(timezone);
    }

    public boolean isPeak(ZonedDateTime time) {
      var local = time.withZoneSameInstant(timezone);
      if (!days.contains(local.getDayOfWeek())) {
        return false;
      }
      var t = local.toLocalTime();
      return peakWindows.stream().anyMatch(w -> !t.isBefore(w.start()) && t.isBefore(w.end()));
    }
  }

  public record PeakWindow(LocalTime start, LocalTime end) implements Serializable {}

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
    var railroads = new HashSet<String>();
    for (JsonNode feed : config.path("railroads")) {
      railroads.add(feed.asText());
    }
    return new NycFareParams(
      subwayFeed,
      omnyFeeds,
      pairs,
      Duration.ofMinutes(minutes),
      peakExclusionFromConfig(config.path("reducedFarePeakExclusion")),
      railroads
    );
  }

  @Nullable
  private static ReducedFarePeakExclusion peakExclusionFromConfig(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    var productId = node.path("productId").asText(null);
    if (productId == null) {
      throw new IllegalArgumentException("reducedFarePeakExclusion requires 'productId'");
    }
    var windows = new java.util.ArrayList<PeakWindow>();
    for (JsonNode w : node.path("peakWindows")) {
      var parts = w.asText().split("-");
      if (parts.length != 2) {
        throw new IllegalArgumentException("peakWindows entries must look like '06:00-10:00'");
      }
      windows.add(new PeakWindow(LocalTime.parse(parts[0]), LocalTime.parse(parts[1])));
    }
    if (windows.isEmpty()) {
      throw new IllegalArgumentException("reducedFarePeakExclusion requires 'peakWindows'");
    }
    var days = EnumSet.noneOf(DayOfWeek.class);
    for (JsonNode d : node.path("days")) {
      days.add(DayOfWeek.valueOf(d.asText()));
    }
    if (days.isEmpty()) {
      days = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    }
    return new ReducedFarePeakExclusion(
      productId,
      node.path("reducedCategory").asText("reduced"),
      List.copyOf(windows),
      days,
      ZoneId.of(node.path("timezone").asText("America/New_York"))
    );
  }
}
