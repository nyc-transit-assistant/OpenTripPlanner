package org.opentripplanner.standalone.config.routerconfig.updaters;

import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_9;

import java.util.List;
import org.opentripplanner.framework.application.OtpAppException;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;
import org.opentripplanner.standalone.config.framework.json.OtpVersion;

/**
 * Shared parsing for the {@code feedIds} field on GTFS-RT updater configs.
 * <p>
 * A single GTFS-RT feed may be applied to multiple static GTFS feeds; each real-time entity is
 * matched to the static feed that contains its trip/route/stop id. The plural {@code feedIds}
 * (an array, since 2.9) is the current form. The legacy singular {@code feedId} (a string) is
 * still accepted and treated as a one-element list — equivalent to the previous single-feed
 * behaviour.
 */
final class FeedIdsConfig {

  private FeedIdsConfig() {}

  /**
   * Read the {@code feedIds} array, falling back to the deprecated singular {@code feedId}.
   * Throws if neither is set, both are set, or the array is empty.
   *
   * @param feedIdSinceVersion when the legacy {@code feedId} key was first supported by this
   *                           updater type — used so the schema doc accurately reflects history
   */
  static List<String> read(NodeAdapter c, OtpVersion feedIdSinceVersion) {
    var feedIds = c
      .of("feedIds")
      .since(V2_9)
      .summary("The static GTFS feed ids the real-time data should be applied to.")
      .description(
        "A single GTFS-RT feed may be applied to multiple static GTFS feeds; each real-time " +
          "entity is matched to the static feed that contains its trip/route/stop id."
      )
      .asStringList(List.of());

    var legacyFeedId = c
      .of("feedId")
      .since(feedIdSinceVersion)
      .summary("Deprecated: prefer `feedIds`. Single static GTFS feed id to apply updates to.")
      .description(
        "Equivalent to specifying a one-element `feedIds` list. Retained for backwards " +
          "compatibility — new configurations should use `feedIds`."
      )
      .asString(null);

    if (!feedIds.isEmpty() && legacyFeedId != null) {
      throw new OtpAppException(
        "Updater config has both `feedIds` and the deprecated `feedId` — set only one."
      );
    }
    if (!feedIds.isEmpty()) {
      return feedIds;
    }
    if (legacyFeedId != null) {
      return List.of(legacyFeedId);
    }
    throw new OtpAppException("Updater config requires `feedIds` (or the deprecated `feedId`).");
  }
}
