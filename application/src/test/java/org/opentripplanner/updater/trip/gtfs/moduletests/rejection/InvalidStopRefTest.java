package org.opentripplanner.updater.trip.gtfs.moduletests.rejection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.updater.spi.UpdateErrorType.INVALID_STOP_REFERENCE;
import static org.opentripplanner.updater.spi.UpdateErrorType.INVALID_STOP_SEQUENCE;
import static org.opentripplanner.updater.spi.UpdateResultAssertions.assertFailure;
import static org.opentripplanner.updater.spi.UpdateResultAssertions.assertSuccess;

import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import org.junit.jupiter.api.Test;
import org.opentripplanner.transit.model.TransitTestEnvironment;
import org.opentripplanner.transit.model.TransitTestEnvironmentBuilder;
import org.opentripplanner.transit.model.TripInput;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.updater.trip.RealtimeTestConstants;
import org.opentripplanner.updater.trip.gtfs.GtfsRtTestHelper;

class InvalidStopRefTest implements RealtimeTestConstants {

  private final TransitTestEnvironmentBuilder builder = TransitTestEnvironment.of();
  private final RegularStop stopA = builder.stop(STOP_A_ID);
  private final RegularStop stopB = builder.stop(STOP_B_ID);
  private final TripInput tripInput = TripInput.of(TRIP_1_ID)
    .addStop(stopA, "10:00", "10:00")
    .addStop(stopB, "10:10", "10:10");

  @Test
  void unknownStopId() {
    var env = builder.addTrip(tripInput).build();
    var rt = GtfsRtTestHelper.of(env);
    var update = rt.tripUpdateScheduled(TRIP_1_ID).addStopTime("unknown stop", "10:00").build();

    assertFailure(INVALID_STOP_REFERENCE, rt.applyTripUpdate(update));
  }

  @Test
  void knownAndUnknownStopId() {
    var env = builder.addTrip(tripInput).build();
    var rt = GtfsRtTestHelper.of(env);
    var update = rt
      .tripUpdateScheduled(TRIP_1_ID)
      .addStopTime(STOP_A_ID, "10:00")
      .addStopTime("unknown stop", "10:00")
      .build();

    assertFailure(INVALID_STOP_REFERENCE, rt.applyTripUpdate(update));
  }

  @Test
  void invalidStopSequence() {
    var env = builder.addTrip(tripInput).build();
    var rt = GtfsRtTestHelper.of(env);
    var update = rt.tripUpdateScheduled(TRIP_1_ID).addDelayedStopTime(100, 60).build();
    assertFailure(INVALID_STOP_SEQUENCE, rt.applyTripUpdate(update));
  }

  /**
   * NYCT's L feed reports stop_sequence as 0-indexed while the static schedule is 1-indexed,
   * so a strict sequence lookup fails. When the update also carries a stop_id we fall back to
   * it instead of rejecting the update.
   */
  @Test
  void invalidStopSequenceFallsBackToStopId() {
    var env = builder.addTrip(tripInput).build();
    var rt = GtfsRtTestHelper.of(env);
    var update = rt
      .tripUpdateScheduled(TRIP_1_ID)
      .addRawStopTime(
        StopTimeUpdate.newBuilder()
          .setStopSequence(999)
          .setStopId(STOP_A_ID)
          .setDeparture(StopTimeEvent.newBuilder().setDelay(60))
          .build()
      )
      .addRawStopTime(
        StopTimeUpdate.newBuilder()
          .setStopSequence(1000)
          .setStopId(STOP_B_ID)
          .setDeparture(StopTimeEvent.newBuilder().setDelay(60))
          .build()
      )
      .build();

    assertSuccess(rt.applyTripUpdate(update));
  }

  /**
   * NYCT's L feed reports stop_sequence as 0-indexed while the static schedule is 1-indexed,
   * so seq=N silently lands on the *next* stop in the pattern (static seq=N) when looked up
   * strictly. When the update also carries a stop_id that disagrees with the sequence-resolved
   * position, the stop_id is authoritative — otherwise A's explicit delay would silently land
   * on B and A would stay [ND].
   */
  @Test
  void mismatchedStopSequenceAndStopIdPrefersStopId() {
    var env = builder.addTrip(tripInput).build();
    var rt = GtfsRtTestHelper.of(env);
    var update = rt
      .tripUpdateScheduled(TRIP_1_ID)
      .addRawStopTime(
        StopTimeUpdate.newBuilder()
          .setStopSequence(2)
          .setStopId(STOP_A_ID)
          .setArrival(StopTimeEvent.newBuilder().setDelay(60))
          .setDeparture(StopTimeEvent.newBuilder().setDelay(60))
          .build()
      )
      .build();

    assertSuccess(rt.applyTripUpdate(update));
    // A carries the explicit delay (real-time times shown, no [ND] flag); B is forward-delay
    // propagated from A. Without the fix, A would be [ND] and B would carry the delay.
    assertEquals("U | A 10:01 10:01 | B 10:11 10:11", env.tripData(TRIP_1_ID).showTimetable());
  }

  @Test
  void validAndInvalidStopSequence() {
    var env = builder.addTrip(tripInput).build();
    var rt = GtfsRtTestHelper.of(env);
    var update = rt
      .tripUpdateScheduled(TRIP_1_ID)
      .addDelayedStopTime(0, 60)
      .addDelayedStopTime(100, 60)
      .build();
    assertFailure(INVALID_STOP_SEQUENCE, rt.applyTripUpdate(update));
  }

  /**
   * No stop id or stop sequence leads to a graceful failure.
   */
  @Test
  void noStopRef() {
    var env = builder.addTrip(tripInput).build();
    var rt = GtfsRtTestHelper.of(env);
    var update = rt
      .tripUpdateScheduled(TRIP_1_ID)
      .addRawStopTime(
        StopTimeUpdate.newBuilder().setDeparture(StopTimeEvent.newBuilder().setDelay(60)).build()
      )
      .build();
    assertFailure(INVALID_STOP_REFERENCE, rt.applyTripUpdate(update));
  }
}
