package org.opentripplanner.updater.trip.metrics;

import io.micrometer.core.instrument.Tag;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.updater.spi.UpdateResult;
import org.opentripplanner.updater.trip.UrlUpdaterParameters;

public class TripUpdateMetrics {

  public static final Consumer<UpdateResult> NOOP = ignored -> {};
  protected List<Tag> baseTags;

  TripUpdateMetrics(UrlUpdaterParameters parameters) {
    this.baseTags = List.of(
      Tag.of("configRef", parameters.configRef()),
      Tag.of("url", parameters.url()),
      Tag.of("feedId", parameters.metricsFeedLabel()),
      Tag.of("updater", updaterLabel(parameters.url()))
    );
  }

  /**
   * A stable per-updater label. All eight subway updaters share {@code feedId="mta-subway"}
   * and the same configRef, leaving only the full URL to tell them apart — which is what
   * forced the dashboards into deep label_replace chains that broke when the URLs moved
   * behind the feed proxy. The URL path ("nyct/gtfs-ace") is the stable identity: it names
   * the upstream feed regardless of which host serves it.
   */
  public static String updaterLabel(String url) {
    try {
      String path = URI.create(url).getPath();
      if (path != null && !path.isBlank()) {
        return path.startsWith("/") ? path.substring(1) : path;
      }
    } catch (IllegalArgumentException ignored) {
      // fall through to the raw url
    }
    return url;
  }

  public static Consumer<UpdateResult> batch(UrlUpdaterParameters parameters) {
    return getConsumer(() -> {
      var metrics = new BatchTripUpdateMetrics(parameters);
      return metrics::setGauges;
    });
  }

  public static Consumer<UpdateResult> streaming(UrlUpdaterParameters parameters) {
    return getConsumer(() -> {
      var metrics = new StreamingTripUpdateMetrics(parameters);
      return metrics::setCounters;
    });
  }

  private static Consumer<UpdateResult> getConsumer(Supplier<Consumer<UpdateResult>> maker) {
    if (OTPFeature.ActuatorAPI.isOn()) {
      return maker.get();
    } else {
      return NOOP;
    }
  }
}
