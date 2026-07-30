package org.opentripplanner.updater.trip;

import java.util.List;

public interface UrlUpdaterParameters {
  String url();
  String configRef();
  String feedId();

  /**
   * Static GTFS feeds this updater applies real-time data to. Defaults to a singleton list
   * containing {@link #feedId()} so that single-feed implementations (e.g. SIRI) need not
   * implement this method explicitly. GTFS-RT updaters that support a single real-time feed
   * being applied to multiple static feeds override this to return the configured list.
   */
  default List<String> feedIds() {
    return List.of(feedId());
  }

  default boolean producerMetrics() {
    return false;
  }
}
