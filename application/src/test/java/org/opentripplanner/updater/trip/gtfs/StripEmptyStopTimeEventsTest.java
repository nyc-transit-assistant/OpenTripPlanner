package org.opentripplanner.updater.trip.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.transit.realtime.GtfsRealtime;
import org.junit.jupiter.api.Test;

/**
 * NYCT prepends a degenerate StopTimeUpdate — the terminal, out of sequence, with an empty
 * departure StopTimeEvent — to trips that haven't started yet. The spec forbids empty events and
 * stock OTP rejects the whole trip update over it, losing every real prediction it carries.
 */
class StripEmptyStopTimeEventsTest {

  private static GtfsRealtime.TripUpdate.StopTimeUpdate stuWithTimes(String stop, long t) {
    return GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
      .setStopId(stop)
      .setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(t))
      .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder().setTime(t + 30))
      .build();
  }

  @Test
  void nyctDegenerateFirstEntryIsDropped() {
    var update = GtfsRealtime.TripUpdate.newBuilder()
      .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("006300_L..N"))
      // the junk placeholder: empty departure event, nothing else
      .addStopTimeUpdate(
        GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
          .setStopId("L01N")
          .setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder())
      )
      .addStopTimeUpdate(stuWithTimes("L17N", 1_785_475_412L))
      .addStopTimeUpdate(stuWithTimes("L16N", 1_785_475_502L))
      .build();

    var cleaned = GtfsRealTimeUpdateHandler.stripEmptyStopTimeEvents(update);
    assertEquals(2, cleaned.getStopTimeUpdateCount());
    assertEquals("L17N", cleaned.getStopTimeUpdate(0).getStopId());
    assertTrue(cleaned.getStopTimeUpdate(0).getArrival().hasTime());
  }

  @Test
  void skippedAndNoDataEntriesSurviveWithoutTimes() {
    var update = GtfsRealtime.TripUpdate.newBuilder()
      .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("t"))
      .addStopTimeUpdate(
        GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
          .setStopId("A")
          .setScheduleRelationship(
            GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED
          )
          .setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder())
      )
      .build();
    var cleaned = GtfsRealTimeUpdateHandler.stripEmptyStopTimeEvents(update);
    assertEquals(1, cleaned.getStopTimeUpdateCount());
    assertFalse(cleaned.getStopTimeUpdate(0).hasArrival());
  }

  @Test
  void untouchedUpdateReturnsSameInstance() {
    var update = GtfsRealtime.TripUpdate.newBuilder()
      .setTrip(GtfsRealtime.TripDescriptor.newBuilder().setTripId("t"))
      .addStopTimeUpdate(stuWithTimes("A", 1_785_475_412L))
      .build();
    assertSame(update, GtfsRealTimeUpdateHandler.stripEmptyStopTimeEvents(update));
  }
}
