package org.opentripplanner.updater.trip.gtfs.updater.http;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.opentripplanner.framework.io.HttpHeaders;
import org.opentripplanner.updater.spi.PollingGraphUpdaterParameters;
import org.opentripplanner.updater.trip.UrlUpdaterParameters;
import org.opentripplanner.updater.trip.gtfs.interpolation.BackwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.interpolation.ForwardsDelayPropagationType;

public record PollingTripUpdaterParameters(
  String configRef,
  Duration frequency,
  boolean fuzzyTripMatching,
  boolean partialTripIdMatching,
  boolean trainNumberMatching,
  boolean scopedFullDatasetClear,
  ForwardsDelayPropagationType forwardsDelayPropagationType,
  BackwardsDelayPropagationType backwardsDelayPropagationType,

  List<String> feedIds,
  String url,
  HttpHeaders headers
) implements PollingGraphUpdaterParameters, UrlUpdaterParameters {
  public PollingTripUpdaterParameters {
    Objects.requireNonNull(feedIds, "feedIds is required");
    if (feedIds.isEmpty()) {
      throw new IllegalArgumentException("feedIds must contain at least one feedId");
    }
    feedIds = List.copyOf(feedIds);
  }

  @Override
  public String feedId() {
    return feedIds.getFirst();
  }
}
