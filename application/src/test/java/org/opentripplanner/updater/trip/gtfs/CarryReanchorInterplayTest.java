package org.opentripplanner.updater.trip.gtfs;

import static com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.REPLACEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opentripplanner.updater.spi.UpdateResultAssertions.assertSuccess;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.transit.model.TransitTestEnvironment;
import org.opentripplanner.transit.model.TripInput;
import org.opentripplanner.updater.trip.RealtimeTestConstants;

/**
 * Audit probe: does the carry-forward harvest survive start-date re-anchoring?
 * The harvest keys past stops by the RAW claimed date; re-anchoring rewrites
 * the date before apply. If the keys disagree, re-anchored post-midnight trips
 * lose their origin at the departure transition all over again.
 */
class CarryReanchorInterplayTest implements RealtimeTestConstants {

  private static final ZoneId ZONE = ZoneId.of("America/New_York");
  private static final LocalDate SERVICE_DATE = LocalDate.of(2025, 8, 4);
  private static final LocalDate PREVIOUS_DAY = SERVICE_DATE.minusDays(1);

  @Test
  void carriesOriginAcrossDepartureForReanchoredTrip() {
    var envBuilder = TransitTestEnvironment.of(SERVICE_DATE, ZONE);
    var stopA = envBuilder.stop(STOP_A_ID);
    var stopB = envBuilder.stop(STOP_B_ID);
    var stopC = envBuilder.stop(STOP_C_ID);
    var env = envBuilder
      .addTrip(
        TripInput.of(TRIP_1_ID).addStop(stopA, "0:47").addStop(stopB, "0:57").addStop(stopC, "1:07")
      )
      .build();
    var rt = GtfsRtTestHelper.of(env);

    // Cycle 1: full pattern under yesterday's date (NYCT anchoring), REPLACEMENT.
    var first = rt
      .tripUpdate(TRIP_1_ID, PREVIOUS_DAY, REPLACEMENT)
      .addStopTime(STOP_A_ID, "24:48")
      .addStopTime(STOP_B_ID, "24:58")
      .addStopTime(STOP_C_ID, "25:08")
      .build();
    assertSuccess(rt.applyTripUpdates(List.of(first)));

    // Cycle 2: origin departed and dropped from the feed, still yesterday's date.
    var second = rt
      .tripUpdate(TRIP_1_ID, PREVIOUS_DAY, REPLACEMENT)
      .addStopTime(STOP_B_ID, "24:59")
      .addStopTime(STOP_C_ID, "25:09")
      .build();
    assertSuccess(rt.applyTripUpdates(List.of(second)));

    assertEquals(
      "P U | A 0:48 0:48 | B 0:59 0:59 | C 1:09 1:09",
      env.tripData(TRIP_1_ID).showTimetable()
    );
  }
}
