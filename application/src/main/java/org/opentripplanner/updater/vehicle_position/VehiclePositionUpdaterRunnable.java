package org.opentripplanner.updater.vehicle_position;

import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.opentripplanner.service.realtimevehicles.RealtimeVehicleRepository;
import org.opentripplanner.standalone.config.routerconfig.updaters.VehiclePositionsUpdaterConfig;
import org.opentripplanner.updater.GraphWriterRunnable;
import org.opentripplanner.updater.RealTimeUpdateContext;
import org.opentripplanner.updater.trip.gtfs.GtfsRealtimeTrainNumberTripMatcher;

class VehiclePositionUpdaterRunnable implements GraphWriterRunnable {

  private final List<VehiclePosition> updates;
  private final RealtimeVehicleRepository realtimeVehicleRepository;
  private final List<String> feedIds;
  private final boolean fuzzyTripMatching;
  private final boolean trainNumberMatching;
  private final Set<VehiclePositionsUpdaterConfig.VehiclePositionFeature> vehiclePositionFeatures;

  public VehiclePositionUpdaterRunnable(
    RealtimeVehicleRepository realtimeVehicleRepository,
    Set<VehiclePositionsUpdaterConfig.VehiclePositionFeature> vehiclePositionFeatures,
    List<String> feedIds,
    boolean fuzzyTripMatching,
    boolean trainNumberMatching,
    List<VehiclePosition> updates
  ) {
    this.updates = Objects.requireNonNull(updates);
    this.feedIds = List.copyOf(Objects.requireNonNull(feedIds));
    if (this.feedIds.isEmpty()) {
      throw new IllegalArgumentException("feedIds must contain at least one feedId");
    }
    this.realtimeVehicleRepository = realtimeVehicleRepository;
    this.fuzzyTripMatching = fuzzyTripMatching;
    this.trainNumberMatching = trainNumberMatching;
    this.vehiclePositionFeatures = vehiclePositionFeatures;
  }

  @Override
  public void run(RealTimeUpdateContext context) {
    RealtimeVehiclePatternMatcher matcher = new RealtimeVehiclePatternMatcher(
      feedIds,
      context.transitService()::getTrip,
      context.transitService()::findPattern,
      context.transitService()::findPattern,
      context.transitService().getTripCalendars()::listServiceDates,
      realtimeVehicleRepository,
      context.transitService().getTimeZone(),
      fuzzyTripMatching ? context.gtfsRealtimeFuzzyTripMatcher() : null,
      trainNumberMatching ? new GtfsRealtimeTrainNumberTripMatcher(context.transitService()) : null,
      vehiclePositionFeatures
    );
    // Apply new vehicle positions
    matcher.applyRealtimeVehicleUpdates(updates);
  }
}
