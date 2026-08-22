package org.opentripplanner.routing.fares;

import java.util.Set;

/**
 * A fare service factory that wants to know which trips are peak-fare trips. The MTA railroads
 * (LIRR, Metro-North) publish a per-train {@code peak_offpeak} flag in trips.txt — a non-standard
 * column the OneBusAway model parses natively — and it is authoritative: the published clock
 * rules mis-classify a few dozen weekday trips. {@link
 * org.opentripplanner.gtfs.graphbuilder.GtfsModule} feeds the flagged trip ids to factories
 * implementing this interface during graph build.
 */
public interface PeakTripAwareFareServiceFactory {
  /** Called once per feed with the trip ids (unscoped) whose {@code peak_offpeak} flag is 1. */
  void addPeakTrips(String feedId, Set<String> peakTripIds);
}
