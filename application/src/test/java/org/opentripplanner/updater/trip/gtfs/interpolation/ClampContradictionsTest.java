package org.opentripplanner.updater.trip.gtfs.interpolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.framework.DataValidationException;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.model.timetable.RealTimeTripTimesBuilder;
import org.opentripplanner.transit.model.timetable.ScheduledTripTimes;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripTimesFactory;

/**
 * A contradiction between two explicitly PROVIDED stop times — mixed prediction sources put
 * stop 5's arrival before stop 4's departure. Interpolation cannot repair this (it only
 * re-fills runs that had no realtime information), so without the clamp the whole update is
 * rejected as a negative hop and every prediction on the trip is lost.
 */
class ClampContradictionsTest {

  static final Trip TRIP = TimetableRepositoryForTest.trip("TRIP_ID").build();
  static final ScheduledTripTimes SCHEDULED = TripTimesFactory.tripTimes(
    TRIP,
    TimetableRepositoryForTest.of().stopTimesEvery5Minutes(10, TRIP, "00:00"),
    new Deduplicator()
  );

  private static RealTimeTripTimesBuilder providedContradiction() {
    var builder = SCHEDULED.createRealTimeWithoutScheduledTimes();
    builder.withArrivalDelay(0, 0);
    builder.withDepartureDelay(0, 0);
    // Stop 4: running 3 minutes late. Stop 5: predicted EARLIER than stop 4's departure.
    builder.withArrivalDelay(4, 180);
    builder.withDepartureDelay(4, 180);
    int stop4Departure = SCHEDULED.getScheduledDepartureTime(4) + 180;
    builder.withArrivalTime(5, stop4Departure - 60);
    builder.withDepartureTime(5, stop4Departure - 30);
    return builder;
  }

  @Test
  void interpolationAloneCannotSaveProvidedContradiction() {
    var builder = providedContradiction();
    new DefaultForwardsDelayInterpolator(true).interpolateDelay(builder);
    assertThrows(DataValidationException.class, builder::build);
  }

  @Test
  void clampSavesProvidedContradiction() {
    var builder = providedContradiction();
    assertTrue(new DefaultForwardsDelayInterpolator(true, true).interpolateDelay(builder));
    var tripTimes = builder.build();
    int stop4Departure = SCHEDULED.getScheduledDepartureTime(4) + 180;
    // The contradiction is flattened to a zero-length hop at the previous departure...
    assertEquals(stop4Departure, tripTimes.getArrivalTime(5));
    // ...and the trip's own later predictions survive monotonic.
    int prevDep = -1;
    for (int i = 0; i < 10; i++) {
      assertTrue(tripTimes.getArrivalTime(i) >= prevDep, "hop into stop " + i);
      assertTrue(tripTimes.getDepartureTime(i) >= tripTimes.getArrivalTime(i), "dwell at " + i);
      prevDep = tripTimes.getDepartureTime(i);
    }
  }

  @Test
  void clampLeavesCleanUpdatesUntouched() {
    var builder = SCHEDULED.createRealTimeWithoutScheduledTimes();
    builder.withArrivalDelay(0, 60);
    builder.withDepartureDelay(0, 60);
    new DefaultForwardsDelayInterpolator(true, true).interpolateDelay(builder);
    var tripTimes = builder.build();
    assertEquals(SCHEDULED.getArrivalTime(9) + 60, tripTimes.getArrivalTime(9));
  }
}
