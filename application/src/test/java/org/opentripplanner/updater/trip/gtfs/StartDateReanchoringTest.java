package org.opentripplanner.updater.trip.gtfs;

import static com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED;
import static com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.updater.spi.UpdateResultAssertions.assertSuccess;
import static org.opentripplanner.updater.trip.UpdateIncrementality.FULL_DATASET;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentripplanner.transit.model.TransitTestEnvironment;
import org.opentripplanner.transit.model.TripInput;
import org.opentripplanner.updater.trip.RealtimeTestConstants;
import org.opentripplanner.utils.time.ServiceDateUtils;

/**
 * NYCT anchors all post-midnight realtime trips to the previous transit day, while the static
 * schedule anchors some of the same trips to the calendar date. Without correction, absolute
 * realtime times are converted against the wrong midnight and every delay comes out
 * {@code true_delay ± 86400} (a one-minute-late train shows as ~24h early), and timeless
 * cancellations target the wrong day's instance, leaving ghost trains on departure boards.
 * These tests cover the re-anchoring that corrects the claimed start date.
 */
class StartDateReanchoringTest implements RealtimeTestConstants {

  private static final ZoneId ZONE = ZoneId.of("America/New_York");
  private static final LocalDate SERVICE_DATE = LocalDate.of(2025, 8, 4);
  private static final LocalDate PREVIOUS_DAY = SERVICE_DATE.minusDays(1);

  /**
   * End-to-end: updates arriving with NYCT's previous-transit-day anchoring are applied to the
   * correct service-day instance with sane delays.
   */
  @Nested
  class Integration {

    /**
     * A post-midnight trip statically anchored to the calendar date (departs 0:47 on the
     * service date) receives a realtime update carrying yesterday's start date and absolute
     * times one minute behind schedule. The update must land on the calendar-date instance
     * with a +60s delay — not on yesterday's instance, and not with a −86340s delay.
     */
    @Test
    void reanchorsDelayUpdateFromPreviousTransitDay() {
      var envBuilder = TransitTestEnvironment.of(SERVICE_DATE, ZONE);
      var stopA = envBuilder.stop(STOP_A_ID);
      var stopB = envBuilder.stop(STOP_B_ID);
      var env = envBuilder
        .addTrip(TripInput.of(TRIP_1_ID).addStop(stopA, "0:47").addStop(stopB, "0:57"))
        .build();
      var rt = GtfsRtTestHelper.of(env);

      // Times are parsed relative to the builder's start date; 24:48 on the previous day is
      // 0:48 on the service date.
      var update = rt
        .tripUpdate(TRIP_1_ID, PREVIOUS_DAY, SCHEDULED)
        .addStopTime(STOP_A_ID, "24:48")
        .addStopTime(STOP_B_ID, "24:58")
        .build();

      assertSuccess(rt.applyTripUpdates(List.of(update)));

      var tripTimes = env.tripData(TRIP_1_ID).tripTimes();
      assertEquals(60, tripTimes.getDepartureDelay(0));
      assertEquals(60, tripTimes.getArrivalDelay(1));
    }

    /**
     * A timeless cancellation under yesterday's date must cancel the upcoming calendar-date
     * instance instead of failing (or worse: leaving tonight's train uncancelled — a ghost
     * train riders would wait for).
     */
    @Test
    void reanchorsCancellationFromPreviousTransitDay() {
      var envBuilder = TransitTestEnvironment.of(SERVICE_DATE, ZONE);
      var stopA = envBuilder.stop(STOP_A_ID);
      var stopB = envBuilder.stop(STOP_B_ID);
      var env = envBuilder
        .addTrip(TripInput.of(TRIP_1_ID).addStop(stopA, "0:47").addStop(stopB, "0:57"))
        .build();
      // Just after midnight before the trip departs.
      var now = SERVICE_DATE.atTime(0, 5).atZone(ZONE).toInstant();
      var rt = GtfsRtTestHelper.of(env, now);

      var update = rt.tripUpdate(TRIP_1_ID, PREVIOUS_DAY, CANCELED).build();

      assertSuccess(rt.applyTripUpdates(List.of(update)));
      assertTrue(env.tripData(TRIP_1_ID).tripTimes().isCanceled());
    }

    /**
     * The partial trip id matcher must find a static trip whose service is active on the day
     * after the claimed start date (NYCT: realtime claims the previous transit day for trips
     * the static schedule anchors to the calendar date) and rewrite the start date along with
     * the trip id.
     */
    @Test
    void partialMatcherMatchesOnFollowingServiceDate() {
      var staticTripId = "A20250801WKD_002870_4..N01R";
      var realtimeTripId = "002870_4..N01R";

      var envBuilder = TransitTestEnvironment.of(SERVICE_DATE, ZONE);
      var stopA = envBuilder.stop(STOP_A_ID);
      var stopB = envBuilder.stop(STOP_B_ID);
      var route = envBuilder.route("4");
      var env = envBuilder
        .addTrip(
          TripInput.of(staticTripId).withRoute(route).addStop(stopA, "0:47").addStop(stopB, "0:57")
        )
        .build();
      var rt = GtfsRtTestHelper.of(env);

      var update = rt
        .tripUpdate(realtimeTripId, PREVIOUS_DAY, SCHEDULED)
        .withRouteId("4")
        .addStopTime(STOP_A_ID, "24:48")
        .addStopTime(STOP_B_ID, "24:58")
        .build();

      assertSuccess(rt.applyTripUpdates(List.of(update), List.of(), FULL_DATASET, true));

      var tripTimes = env.tripData(staticTripId).tripTimes();
      assertEquals(60, tripTimes.getDepartureDelay(0));
    }
  }

  /**
   * Unit tests for the date-selection rules in
   * {@link GtfsRealTimeUpdateHandler#chooseStartDate}.
   */
  @Nested
  class ChooseStartDate {

    private static final LocalDate CLAIMED = SERVICE_DATE;
    private static final LocalDate NEXT = CLAIMED.plusDays(1);
    private static final LocalDate PREVIOUS = CLAIMED.minusDays(1);
    // A trip running 0:47 -> 0:57 relative to its service date.
    private static final int FIRST_DEP = 47 * 60;
    private static final int LAST_ARR = 57 * 60;
    private static final Instant IRRELEVANT_NOW = CLAIMED.atTime(12, 0).atZone(ZONE).toInstant();

    private static long startOfService(LocalDate date) {
      return ServiceDateUtils.asStartOfService(date, ZONE).toEpochSecond();
    }

    @Test
    void keepsClaimedDateWhenOnlyActiveDate() {
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(CLAIMED),
        OptionalLong.of(startOfService(CLAIMED) + FIRST_DEP + 60),
        false,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        IRRELEVANT_NOW
      );
      assertEquals(CLAIMED, chosen);
    }

    @Test
    void keepsClaimedDateWhenNoActiveDates() {
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(),
        OptionalLong.empty(),
        true,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        IRRELEVANT_NOW
      );
      assertEquals(CLAIMED, chosen);
    }

    @Test
    void timesPickNearestAnchorWhenBothDatesActive() {
      // Realtime time is one minute behind the NEXT-date instance's schedule.
      var rtTime = startOfService(NEXT) + FIRST_DEP + 60;
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(CLAIMED, NEXT),
        OptionalLong.of(rtTime),
        false,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        IRRELEVANT_NOW
      );
      assertEquals(NEXT, chosen);
    }

    @Test
    void timesKeepClaimedDateWhenAnchoringIsCorrect() {
      var rtTime = startOfService(CLAIMED) + FIRST_DEP + 60;
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(CLAIMED, NEXT),
        OptionalLong.of(rtTime),
        false,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        IRRELEVANT_NOW
      );
      assertEquals(CLAIMED, chosen);
    }

    @Test
    void timesPickPreviousDateForReverseAnchoringMismatch() {
      var rtTime = startOfService(PREVIOUS) + FIRST_DEP + 60;
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(NEXT, PREVIOUS),
        OptionalLong.of(rtTime),
        false,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        IRRELEVANT_NOW
      );
      assertEquals(PREVIOUS, chosen);
    }

    @Test
    void timelessKeepsClaimedDateWhileInstanceIsCurrent() {
      // Now is before the claimed instance has even departed.
      var now = CLAIMED.atTime(0, 5).atZone(ZONE).toInstant();
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(CLAIMED, NEXT),
        OptionalLong.empty(),
        true,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        now
      );
      assertEquals(CLAIMED, chosen);
    }

    @Test
    void timelessMovesToNextDateWhenClaimedInstanceLongFinished() {
      // The claimed instance arrived at 0:57; 20 hours later a cancellation under the claimed
      // date must refer to the upcoming instance (NYCT cancels tonight's post-midnight trips
      // under yesterday's date).
      var now = CLAIMED.atTime(21, 0).atZone(ZONE).toInstant();
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(CLAIMED, NEXT),
        OptionalLong.empty(),
        true,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        now
      );
      assertEquals(NEXT, chosen);
    }

    @Test
    void timelessKeepsClaimedDateWhenNextDateInactive() {
      var now = CLAIMED.atTime(21, 0).atZone(ZONE).toInstant();
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(CLAIMED, PREVIOUS),
        OptionalLong.empty(),
        true,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        now
      );
      assertEquals(CLAIMED, chosen);
    }

    @Test
    void timelessPrefersNextDateWhenClaimedInactive() {
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(NEXT, PREVIOUS),
        OptionalLong.empty(),
        true,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        IRRELEVANT_NOW
      );
      assertEquals(NEXT, chosen);
    }

    @Test
    void timelessFallsBackToPreviousDateWhenOnlyItIsActive() {
      var chosen = GtfsRealTimeUpdateHandler.chooseStartDate(
        CLAIMED,
        List.of(PREVIOUS),
        OptionalLong.empty(),
        true,
        FIRST_DEP,
        LAST_ARR,
        ZONE,
        IRRELEVANT_NOW
      );
      assertEquals(PREVIOUS, chosen);
    }
  }
}
