package org.opentripplanner.updater.trip.gtfs.updater.http;

import static org.opentripplanner.updater.trip.UpdateIncrementality.DIFFERENTIAL;
import static org.opentripplanner.updater.trip.UpdateIncrementality.FULL_DATASET;

import com.google.protobuf.ExtensionRegistry;
import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.NyctSubway;
import de.mfdz.MfdzRealtimeExtensions;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.opentripplanner.framework.io.HttpHeaders;
import org.opentripplanner.framework.io.OtpHttpClient;
import org.opentripplanner.framework.io.OtpHttpClientFactory;
import org.opentripplanner.updater.trip.UpdateIncrementality;
import org.opentripplanner.updater.trip.gtfs.TripReplacementPeriod;
import org.opentripplanner.utils.tostring.ToStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HttpTripUpdateSource {

  private static final Logger LOG = LoggerFactory.getLogger(HttpTripUpdateSource.class);
  /**
   * Feed id that is used to match trip ids in the TripUpdates
   */
  private final String feedId;
  private final String url;
  private final boolean entityIdAsTripId;
  private final HttpHeaders headers;
  private UpdateIncrementality updateIncrementality = FULL_DATASET;
  private final ExtensionRegistry registry = ExtensionRegistry.newInstance();
  private final OtpHttpClient otpHttpClient;
  private List<TripReplacementPeriod> lastTripReplacementPeriods = List.of();

  public HttpTripUpdateSource(PollingTripUpdaterParameters config) {
    this.entityIdAsTripId = config.trainNumberMatching();
    this.feedId = config.feedId();
    this.url = config.url();
    this.headers = HttpHeaders.of().acceptProtobuf().add(config.headers()).build();
    MfdzRealtimeExtensions.registerAllExtensions(registry);
    NyctSubway.registerAllExtensions(registry);
    otpHttpClient = new OtpHttpClientFactory().create(LOG);
  }

  public List<TripUpdate> getUpdates() {
    FeedMessage feedMessage;
    List<FeedEntity> feedEntityList;
    List<TripUpdate> updates = null;
    updateIncrementality = FULL_DATASET;
    lastTripReplacementPeriods = List.of();
    long startNanos = System.nanoTime();
    try {
      // Decode message
      feedMessage = otpHttpClient.getAndMap(URI.create(url), this.headers, response ->
        FeedMessage.parseFrom(response.body(), registry)
      );
      feedEntityList = feedMessage.getEntityList();

      // Change fullDataset value if this is an incremental update
      if (
        feedMessage.hasHeader() &&
        feedMessage.getHeader().hasIncrementality() &&
        feedMessage
          .getHeader()
          .getIncrementality()
          .equals(GtfsRealtime.FeedHeader.Incrementality.DIFFERENTIAL)
      ) {
        updateIncrementality = DIFFERENTIAL;
      }

      if (feedMessage.hasHeader()) {
        lastTripReplacementPeriods = TripReplacementPeriod.fromNyctFeedHeader(
          feedMessage.getHeader()
        );
      }

      // Create List of TripUpdates
      updates = new ArrayList<>(feedEntityList.size());
      for (FeedEntity feedEntity : feedEntityList) {
        if (feedEntity.hasTripUpdate()) {
          var tripUpdate = feedEntity.getTripUpdate();
          if (entityIdAsTripId && !feedEntity.getId().isBlank() && !tripUpdate.hasVehicle()) {
            // Train-number matching: some producers (MNR) publish the train number only as
            // the FeedEntity id, which is dropped below. Stash it in the vehicle label so the
            // matcher sees it; producers that already send a vehicle descriptor (NJT rail,
            // vehicle.id = train number) are left untouched.
            tripUpdate = tripUpdate
              .toBuilder()
              .setVehicle(GtfsRealtime.VehicleDescriptor.newBuilder().setLabel(feedEntity.getId()))
              .build();
          }
          updates.add(tripUpdate);
        }
      }

      LOG.info(
        "Fetched {} trip updates ({} entities, {} replacement periods) in {} ms from {}",
        updates.size(),
        feedEntityList.size(),
        lastTripReplacementPeriods.size(),
        (System.nanoTime() - startNanos) / 1_000_000,
        url
      );
    } catch (Exception e) {
      LOG.error("Failed to process GTFS-RT TripUpdates feed from {}", url, e);
    }
    return updates;
  }

  /**
   * Trip replacement periods extracted from the most recently fetched feed message, or an
   * empty list if the feed didn't carry the NYCT extension.
   */
  public List<TripReplacementPeriod> tripReplacementPeriodsOfLastUpdates() {
    return lastTripReplacementPeriods;
  }

  @Override
  public String toString() {
    return ToStringBuilder.of(this.getClass())
      .addStr("feedId", feedId)
      .addStr("url", url)
      .toString();
  }

  /**
   * @return the incrementality of the last list with updates, i.e. if all previous updates
   * should be disregarded
   */
  public UpdateIncrementality incrementalityOfLastUpdates() {
    return updateIncrementality;
  }
}
