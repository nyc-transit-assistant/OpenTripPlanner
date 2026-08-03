package org.opentripplanner.updater.trip.gtfs.moduletests.addition;

import static com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.NEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.updater.spi.UpdateResultAssertions.assertSuccess;

import com.google.transit.realtime.GtfsRealtime;
import org.junit.jupiter.api.Test;
import org.opentripplanner.transit.model.TransitTestEnvironment;
import org.opentripplanner.transit.model.TransitTestEnvironmentBuilder;
import org.opentripplanner.transit.model.TripInput;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.updater.trip.RealtimeTestConstants;
import org.opentripplanner.updater.trip.gtfs.GtfsRtTestHelper;

/**
 * NYCT (and other producers) drop a stop's update once the vehicle departs it, so a NEW trip's
 * pattern would erode from the front on every FULL_DATASET cycle. These tests cover the
 * carry-forward: stops present in the previous cycle but missing from the current update are
 * prepended with their last observed times, with all-or-nothing guards for reroutes and
 * non-monotonic times.
 */
class CarriedForwardPastStopsTest implements RealtimeTestConstants {

  private final TransitTestEnvironmentBuilder envBuilder = TransitTestEnvironment.of();
  private final RegularStop stopA = envBuilder.stop(STOP_A_ID);
  private final RegularStop stopB = envBuilder.stop(STOP_B_ID);
  private final RegularStop stopC = envBuilder.stop(STOP_C_ID);
  private final RegularStop stopD = envBuilder.stop(STOP_D_ID);

  private final TransitTestEnvironment env = envBuilder
    .addTrip(
      TripInput.of(TRIP_1_ID)
        .addStop(stopA, "12:00")
        .addStop(stopB, "12:10")
        .addStop(stopC, "12:20")
    )
    .build();
  private final GtfsRtTestHelper rt = GtfsRtTestHelper.of(env);

  @Test
  void carriesDepartedStopsForwardWithLastObservedTimes() {
    var first = rt
      .tripUpdate(ADDED_TRIP_ID, NEW)
      .addStopTime(STOP_A_ID, "10:10")
      .addStopTime(STOP_B_ID, "10:20")
      .addStopTime(STOP_C_ID, "10:30")
      .addStopTime(STOP_D_ID, "10:40")
      .build();
    assertSuccess(rt.applyTripUpdate(first));

    // The producer has dropped A and B (vehicle departed); C's prediction shifted by a minute.
    var second = rt
      .tripUpdate(ADDED_TRIP_ID, NEW)
      .addStopTime(STOP_C_ID, "10:31")
      .addStopTime(STOP_D_ID, "10:41")
      .build();
    assertSuccess(rt.applyTripUpdate(second));

    assertEquals(
      "A U | A 10:10 10:10 | B 10:20 10:20 | C 10:31 10:31 | D 10:41 10:41",
      env.tripData(ADDED_TRIP_ID).showTimetable()
    );
  }

  @Test
  void carriesForwardAcrossSuccessiveCycles() {
    assertSuccess(
      rt.applyTripUpdate(
        rt
          .tripUpdate(ADDED_TRIP_ID, NEW)
          .addStopTime(STOP_A_ID, "10:10")
          .addStopTime(STOP_B_ID, "10:20")
          .addStopTime(STOP_C_ID, "10:30")
          .addStopTime(STOP_D_ID, "10:40")
          .build()
      )
    );
    assertSuccess(
      rt.applyTripUpdate(
        rt
          .tripUpdate(ADDED_TRIP_ID, NEW)
          .addStopTime(STOP_B_ID, "10:21")
          .addStopTime(STOP_C_ID, "10:31")
          .addStopTime(STOP_D_ID, "10:41")
          .build()
      )
    );
    // B rolled off in the third cycle: its carried time must be the 10:21 observed in the
    // second cycle, not the 10:20 from the first.
    assertSuccess(
      rt.applyTripUpdate(
        rt
          .tripUpdate(ADDED_TRIP_ID, NEW)
          .addStopTime(STOP_C_ID, "10:32")
          .addStopTime(STOP_D_ID, "10:42")
          .build()
      )
    );

    assertEquals(
      "A U | A 10:10 10:10 | B 10:21 10:21 | C 10:32 10:32 | D 10:42 10:42",
      env.tripData(ADDED_TRIP_ID).showTimetable()
    );
  }

  @Test
  void carriesDepartedStopsForwardForReplacementTrips() {
    // The R-train weekend scenario: a scheduled trip whose realtime pattern diverges is
    // handled as REPLACEMENT — its pattern must not erode as stops roll out of the feed.
    var first = rt
      .tripUpdate(TRIP_1_ID, GtfsRealtime.TripDescriptor.ScheduleRelationship.REPLACEMENT)
      .addStopTime(STOP_A_ID, "12:01")
      .addStopTime(STOP_B_ID, "12:11")
      .addStopTime(STOP_D_ID, "12:21")
      .build();
    assertSuccess(rt.applyTripUpdate(first));

    var second = rt
      .tripUpdate(TRIP_1_ID, GtfsRealtime.TripDescriptor.ScheduleRelationship.REPLACEMENT)
      .addStopTime(STOP_B_ID, "12:12")
      .addStopTime(STOP_D_ID, "12:22")
      .build();
    assertSuccess(rt.applyTripUpdate(second));

    assertEquals(
      "P U | A 12:01 12:01 | B 12:12 12:12 | D 12:22 12:22",
      env.tripData(TRIP_1_ID).showTimetable()
    );
  }

  @Test
  void reroutedTripCarriesNothing() {
    assertSuccess(
      rt.applyTripUpdate(
        rt
          .tripUpdate(ADDED_TRIP_ID, NEW)
          .addStopTime(STOP_A_ID, "10:10")
          .addStopTime(STOP_B_ID, "10:20")
          .build()
      )
    );
    // The next cycle's first stop is not part of the previous pattern: treat as a reroute and
    // rebuild from the update alone.
    assertSuccess(
      rt.applyTripUpdate(
        rt
          .tripUpdate(ADDED_TRIP_ID, NEW)
          .addStopTime(STOP_C_ID, "10:35")
          .addStopTime(STOP_D_ID, "10:45")
          .build()
      )
    );

    assertEquals(
      "A U | C 10:35 10:35 | D 10:45 10:45",
      env.tripData(ADDED_TRIP_ID).showTimetable()
    );
  }

  @Test
  void nonMonotonicCarriedTimesCarryNothing() {
    assertSuccess(
      rt.applyTripUpdate(
        rt
          .tripUpdate(ADDED_TRIP_ID, NEW)
          .addStopTime(STOP_A_ID, "10:10")
          .addStopTime(STOP_B_ID, "10:20")
          .addStopTime(STOP_C_ID, "10:30")
          .build()
      )
    );
    // C's new prediction jumped to before B's last observed departure: prepending B would
    // violate monotonicity, so nothing is carried.
    assertSuccess(
      rt.applyTripUpdate(
        rt
          .tripUpdate(ADDED_TRIP_ID, NEW)
          .addStopTime(STOP_C_ID, "10:15")
          .addStopTime(STOP_D_ID, "10:45")
          .build()
      )
    );

    assertEquals(
      "A U | C 10:15 10:15 | D 10:45 10:45",
      env.tripData(ADDED_TRIP_ID).showTimetable()
    );
  }
}
