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

  /**
   * The feed label used for metrics tags. For a single-feed updater this is the feed id; for a
   * combined-feed updater (one real-time feed applied to several static feeds), tagging with the
   * first feed id would misattribute the whole updater to one constituent (the MTA bus updater
   * showing as {@code mta-bus-bronx} for all boroughs). Derives a label from the ids' common
   * prefix when one exists ({@code mta-bus-*} -> {@code mta-bus}), otherwise joins them.
   */
  default String metricsFeedLabel() {
    return combinedFeedLabel(feedIds());
  }

  static String combinedFeedLabel(List<String> feedIds) {
    if (feedIds.size() == 1) {
      return feedIds.getFirst();
    }
    String prefix = feedIds.getFirst();
    for (String id : feedIds) {
      int i = 0;
      while (i < prefix.length() && i < id.length() && prefix.charAt(i) == id.charAt(i)) {
        i++;
      }
      prefix = prefix.substring(0, i);
    }
    int boundary = prefix.lastIndexOf('-');
    if (boundary > 0) {
      return prefix.substring(0, boundary);
    }
    return String.join(",", feedIds);
  }
}
