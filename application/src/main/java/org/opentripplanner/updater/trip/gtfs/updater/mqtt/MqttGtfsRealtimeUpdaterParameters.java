package org.opentripplanner.updater.trip.gtfs.updater.mqtt;

import java.util.List;
import java.util.Objects;
import org.opentripplanner.updater.trip.UrlUpdaterParameters;
import org.opentripplanner.updater.trip.gtfs.interpolation.BackwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.interpolation.ForwardsDelayPropagationType;

public record MqttGtfsRealtimeUpdaterParameters(
  String configRef,
  List<String> feedIds,
  String url,
  String topic,
  int qos,
  boolean fuzzyTripMatching,
  boolean partialTripIdMatching,
  ForwardsDelayPropagationType forwardsDelayPropagationType,
  BackwardsDelayPropagationType backwardsDelayPropagationType
) implements UrlUpdaterParameters {
  public MqttGtfsRealtimeUpdaterParameters {
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
