package org.opentripplanner.ext.fares.service.gtfs.v2.custom;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentripplanner.ext.fares.service.gtfs.GtfsFaresService;
import org.opentripplanner.ext.fares.service.gtfs.v1.GtfsFareServiceFactory;
import org.opentripplanner.routing.fares.FareService;

/**
 * Builds the {@link NycFaresService}: the stock combined v1/v2 GTFS fare service assembled from
 * the feeds' fare data, wrapped with the OMNY cross-feed composition rules.
 */
public class NycFareServiceFactory extends GtfsFareServiceFactory {

  private NycFareParams params;

  @Override
  public FareService makeFareService() {
    if (params == null) {
      throw new IllegalStateException("fares type 'nyc' was not configured");
    }
    var stock = (GtfsFaresService) super.makeFareService();
    return new NycFaresService(stock, params);
  }

  @Override
  public void configure(JsonNode config) {
    params = NycFareParams.fromConfig(config);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName();
  }
}
