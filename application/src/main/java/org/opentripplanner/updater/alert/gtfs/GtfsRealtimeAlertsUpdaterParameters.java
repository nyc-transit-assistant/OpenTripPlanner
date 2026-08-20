package org.opentripplanner.updater.alert.gtfs;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.opentripplanner.framework.io.HttpHeaders;
import org.opentripplanner.updater.spi.PollingGraphUpdaterParameters;

public record GtfsRealtimeAlertsUpdaterParameters(
  String configRef,
  List<String> feedIds,
  String url,
  int earlyStartSec,
  boolean fuzzyTripMatching,
  Duration frequency,
  HttpHeaders headers
) implements PollingGraphUpdaterParameters {
  @Override
  public String metricsUrl() {
    return url;
  }

  public GtfsRealtimeAlertsUpdaterParameters {
    Objects.requireNonNull(feedIds, "feedIds is required");
    if (feedIds.isEmpty()) {
      throw new IllegalArgumentException("feedIds must contain at least one feedId");
    }
    feedIds = List.copyOf(feedIds);
  }
}
