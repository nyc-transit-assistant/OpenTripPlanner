package org.opentripplanner.updater.trip.gtfs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.transit.realtime.GtfsRealtime;
import org.junit.jupiter.api.Test;

/**
 * NYCT emits trip updates that carry no information: zero-STU stray shells for rerouted trains
 * dropped into sibling feeds, and unstarted-trip placeholders that become empty once the
 * sanitizer strips their degenerate event. These must be skipped as benign, not failed —
 * while cancellations (legitimately STU-less) and empty NEW trips (genuinely broken) must not.
 */
class InformationlessUpdateTest {

  private static GtfsRealtime.TripUpdate.Builder update(
    GtfsRealtime.TripDescriptor.ScheduleRelationship rel
  ) {
    var trip = GtfsRealtime.TripDescriptor.newBuilder().setTripId("t");
    if (rel != null) {
      trip.setScheduleRelationship(rel);
    }
    return GtfsRealtime.TripUpdate.newBuilder().setTrip(trip);
  }

  @Test
  void emptyScheduledUpdateIsInformationless() {
    assertTrue(
      GtfsRealTimeTripUpdateAdapter.isInformationlessUpdate(
        update(GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED).build()
      )
    );
    assertTrue(GtfsRealTimeTripUpdateAdapter.isInformationlessUpdate(update(null).build()));
  }

  @Test
  void cancellationsAndNewTripsAreNot() {
    assertFalse(
      GtfsRealTimeTripUpdateAdapter.isInformationlessUpdate(
        update(GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED).build()
      )
    );
    assertFalse(
      GtfsRealTimeTripUpdateAdapter.isInformationlessUpdate(
        update(GtfsRealtime.TripDescriptor.ScheduleRelationship.NEW).build()
      )
    );
  }

  @Test
  void updatesWithStopTimeUpdatesAreNot() {
    var u = update(GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED)
      .addStopTimeUpdate(
        GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
          .setStopId("A")
          .setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(1_785_000_000L))
      )
      .build();
    assertFalse(GtfsRealTimeTripUpdateAdapter.isInformationlessUpdate(u));
  }
}
