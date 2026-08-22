package org.opentripplanner.ext.fares.service.gtfs.v2.custom;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.opentripplanner.ext.fares.service.gtfs.GtfsFaresService;
import org.opentripplanner.ext.fares.service.gtfs.v1.GtfsFareServiceFactory;
import org.opentripplanner.routing.fares.FareService;
import org.opentripplanner.routing.fares.PeakTripAwareFareServiceFactory;

/**
 * Builds the {@link NycFaresService}: the stock combined v1/v2 GTFS fare service assembled from
 * the feeds' fare data, wrapped with the OMNY cross-feed composition rules.
 */
public class NycFareServiceFactory
  extends GtfsFareServiceFactory
  implements PeakTripAwareFareServiceFactory {

  private NycFareParams params;
  private final Map<String, Set<String>> peakTripsByFeed = new HashMap<>();

  @Override
  public FareService makeFareService() {
    if (params == null) {
      throw new IllegalStateException("fares type 'nyc' was not configured");
    }
    var stock = (GtfsFaresService) super.makeFareService();
    var railroadTables = RailroadFareTables.of(
      params.railroadFeeds(),
      this.fareLegRules,
      this.stopAreas
    );
    return new NycFaresService(stock, params, railroadTables, Map.copyOf(peakTripsByFeed));
  }

  @Override
  public void configure(JsonNode config) {
    params = NycFareParams.fromConfig(config);
  }

  @Override
  public void addPeakTrips(String feedId, Set<String> peakTripIds) {
    peakTripsByFeed.merge(feedId, Set.copyOf(peakTripIds), (a, b) -> {
      var merged = new java.util.HashSet<>(a);
      merged.addAll(b);
      return merged;
    });
  }

  @Override
  public String toString() {
    return getClass().getSimpleName();
  }
}
