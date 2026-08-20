package org.opentripplanner.updater.spi;

import java.time.Duration;

/**
 * This is named PollingGraphUpdaterConfig instead of Config in order to not conflict with the
 * config interfaces of child classes.
 */
public interface PollingGraphUpdaterParameters {
  Duration frequency();

  /** The config name/type for the updater. Used to reference the configuration element. */
  String configRef();

  /**
   * The URL this updater polls, for metric labeling — configRef alone is the updater TYPE
   * (all eight subway trip updaters share "STOP_TIME_UPDATER"), so per-updater health needs
   * the endpoint identity. Parameters that already implement {@link
   * org.opentripplanner.updater.trip.UrlUpdaterParameters} get it for free; others override.
   * Empty when the updater has no single meaningful URL (vehicle rental's GBFS tree).
   */
  default String metricsUrl() {
    return this instanceof org.opentripplanner.updater.trip.UrlUpdaterParameters u ? u.url() : "";
  }
}
