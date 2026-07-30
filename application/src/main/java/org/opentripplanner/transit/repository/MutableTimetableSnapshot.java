package org.opentripplanner.transit.repository;

import java.time.LocalDate;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transit.model.timetable.RealTimeTripUpdate;

public interface MutableTimetableSnapshot extends ReadOnlyTimetableSnapshot {
  void update(RealTimeTripUpdate realTimeTripUpdate);

  ReadOnlyTimetableSnapshot createReadOnlySnapshot();

  void clear(String feedId);

  /**
   * Clear data of the snapshot for the provided feed id, optionally restricted to a subset of
   * routes. This supports the case where multiple realtime updaters share a feed id but each
   * is authoritative only for a subset of routes (e.g. NYCT's per-line GTFS-RT URLs all writing
   * to {@code mta-subway}). Without scoping, each updater's full-dataset clear would wipe data
   * just produced by the others.
   *
   * @param routeIds if non-null, only clear data whose route is in this set; if null, clear all
   *                 data for the feed (matches {@link #clear(String)})
   */
  void clear(String feedId, @Nullable Set<FeedScopedId> routeIds);

  boolean revertTripToScheduledTripPattern(FeedScopedId tripId, LocalDate serviceDate);

  boolean purgeExpiredData(LocalDate serviceDate);
}
