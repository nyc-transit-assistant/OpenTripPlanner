package org.opentripplanner.updater.vehicle_position;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.opentripplanner.framework.io.HttpHeaders;
import org.opentripplanner.standalone.config.routerconfig.updaters.VehiclePositionsUpdaterConfig;
import org.opentripplanner.updater.spi.PollingGraphUpdaterParameters;

public record VehiclePositionsUpdaterParameters(
  String configRef,
  List<String> feedIds,
  URI url,
  Duration frequency,
  HttpHeaders headers,
  boolean fuzzyTripMatching,
  boolean trainNumberMatching,
  Set<VehiclePositionsUpdaterConfig.VehiclePositionFeature> vehiclePositionFeatures
) implements PollingGraphUpdaterParameters {
  public VehiclePositionsUpdaterParameters {
    Objects.requireNonNull(feedIds, "feedIds is required");
    if (feedIds.isEmpty()) {
      throw new IllegalArgumentException("feedIds must contain at least one feedId");
    }
    feedIds = List.copyOf(feedIds);
    Objects.requireNonNull(url, "url is required");
  }
}
