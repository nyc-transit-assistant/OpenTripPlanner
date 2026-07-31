package org.opentripplanner.updater.trip.gtfs.interpolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.framework.DataValidationException;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.model.timetable.ScheduledTripTimes;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripTimesFactory;

/**
 * The NYC Subway express scenario: the feed provides times only for stops the train actually
 * serves, silently omitting the locals it skips. Flat forward propagation fills the omitted run
 * with the pre-express delay, and the express's early arrival at the next provided stop then
 * reads as a negative hop, rejecting the entire update. INTERPOLATE_CONTRADICTIONS instead
 * re-fills the run by ratio interpolation, as the spec prescribes for explicitly SKIPPED stops.
 */
class ContradictionInterpolationTest {

  static final Trip TRIP = TimetableRepositoryForTest.trip("TRIP_ID").build();
  static final int STOP_COUNT = 20;
  static final ScheduledTripTimes SCHEDULED_TRIP_TIMES = TripTimesFactory.tripTimes(
    TRIP,
    TimetableRepositoryForTest.of().stopTimesEvery5Minutes(STOP_COUNT, TRIP, "00:00"),
    new Deduplicator()
  );

  private static org.opentripplanner.transit.model.timetable.RealTimeTripTimesBuilder expressScenario() {
    var builder = SCHEDULED_TRIP_TIMES.createRealTimeWithoutScheduledTimes();
    // On time at the first stop; then provided again only at stop 10, twenty minutes EARLIER
    // than schedule — the train expressed past stops 1..9. (Anchored at stop 0 so the forwards
    // interpolator alone produces a complete timetable; production also runs the backwards
    // interpolator for stops before the first update.)
    builder.withArrivalDelay(0, 0);
    builder.withDepartureDelay(0, 0);
    int early = 20 * 60;
    builder.withArrivalTime(10, builder.getScheduledArrivalTime(10) - early);
    builder.withDepartureTime(10, builder.getScheduledDepartureTime(10) - early);
    return builder;
  }

  @Test
  void defaultPropagationRejectsTheExpressUpdate() {
    var builder = expressScenario();
    new DefaultForwardsDelayInterpolator().interpolateDelay(builder);
    // Flat propagation gives stops 3..9 their scheduled times (delay 0); stop 10's provided
    // arrival is then earlier than stop 9's departure — the whole update dies in validation.
    assertThrows(DataValidationException.class, builder::build);
  }

  @Test
  void contradictionInterpolationSavesTheExpressUpdate() {
    var builder = expressScenario();
    assertTrue(new DefaultForwardsDelayInterpolator(true).interpolateDelay(builder));
    var tripTimes = builder.build();

    // Provided times are preserved exactly.
    assertEquals(builder.getScheduledArrivalTime(10) - 20 * 60, tripTimes.getArrivalTime(10));
    // The omitted run is monotonic between the anchors.
    for (int i = 1; i <= 10; i++) {
      assertTrue(
        tripTimes.getArrivalTime(i) >= tripTimes.getDepartureTime(i - 1),
        "non-decreasing at " + i
      );
    }
    // Interior stops sit strictly inside the anchor window, not at the flat-propagated schedule.
    assertTrue(tripTimes.getArrivalTime(9) <= tripTimes.getArrivalTime(10));
    assertTrue(tripTimes.getArrivalTime(1) >= tripTimes.getDepartureTime(0));
    // And the tail beyond the last provided stop still gets flat propagation of the new delay.
    assertEquals(tripTimes.getArrivalTime(11), tripTimes.getArrivalTime(10) + 5 * 60);
  }
}
