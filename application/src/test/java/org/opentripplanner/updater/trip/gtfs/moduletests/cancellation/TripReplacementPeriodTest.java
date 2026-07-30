package org.opentripplanner.updater.trip.gtfs.moduletests.cancellation;

import static com.google.common.truth.Truth.assertThat;
import static com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED;
import static com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED;
import static org.opentripplanner.updater.spi.UpdateResultAssertions.assertFailure;
import static org.opentripplanner.updater.spi.UpdateResultAssertions.assertNoFailure;
import static org.opentripplanner.updater.trip.UpdateIncrementality.FULL_DATASET;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.transit.model.TransitTestEnvironment;
import org.opentripplanner.transit.model.TransitTestEnvironmentBuilder;
import org.opentripplanner.transit.model.TripInput;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.timetable.RealTimeTripTimes;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.opentripplanner.updater.spi.UpdateErrorType;
import org.opentripplanner.updater.trip.RealtimeTestConstants;
import org.opentripplanner.updater.trip.gtfs.GtfsRtTestHelper;
import org.opentripplanner.updater.trip.gtfs.TripReplacementPeriod;

/**
 * Tests for the NYCT trip_replacement_period cancel-by-omission semantics in
 * {@code GtfsRealTimeTripUpdateAdapter}. Trips on routes covered by an active replacement
 * period are cancelled if they are absent from the realtime update batch and they overlap the
 * window — that is, they start before the window ends and have not yet reached their last stop.
 * Overlap rather than origin departure is the test, because at a mid-line stop nearly every
 * upcoming train left its terminal well before "now".
 */
class TripReplacementPeriodTest implements RealtimeTestConstants {

  private static final LocalDate SERVICE_DATE = LocalDate.of(2024, 5, 7);
  private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
  private static final Instant NOW = SERVICE_DATE.atTime(11, 50).atZone(ZONE).toInstant();
  private static final Instant WINDOW_END = NOW.plusSeconds(30 * 60);

  private final TransitTestEnvironmentBuilder envBuilder = TransitTestEnvironment.of(SERVICE_DATE);
  private final RegularStop stopA = envBuilder.stop(STOP_A_ID);
  private final RegularStop stopB = envBuilder.stop(STOP_B_ID);
  private final RegularStop stopC = envBuilder.stop(STOP_C_ID);
  private final Route routeA = envBuilder.route("A");
  private final Route routeB = envBuilder.route("B");

  private final TransitTestEnvironment env = envBuilder
    // Route A: trip 1 inside window, trip 2 outside window
    .addTrip(
      TripInput.of(TRIP_1_ID).withRoute(routeA).addStop(stopA, "12:00").addStop(stopB, "12:10")
    )
    .addTrip(
      TripInput.of(TRIP_2_ID).withRoute(routeA).addStop(stopA, "13:00").addStop(stopB, "13:10")
    )
    // Route B: trip inside window but on a different route
    .addTrip(
      TripInput.of("RouteBTrip").withRoute(routeB).addStop(stopA, "12:05").addStop(stopB, "12:15")
    )
    .build();

  private final GtfsRtTestHelper rt = GtfsRtTestHelper.of(env, NOW);

  @Test
  void cancelsScheduledTripOnRouteWhenAbsentFromFeed() {
    var period = new TripReplacementPeriod("A", WINDOW_END);

    assertNoFailure(rt.applyTripUpdates(List.of(), List.of(period), FULL_DATASET));

    assertThat(state(env.tripData(TRIP_1_ID).tripTimes())).isEqualTo(RtState.CANCELED);
  }

  @Test
  void doesNotCancelTripPresentInFeed() {
    var period = new TripReplacementPeriod("A", WINDOW_END);
    var update = rt
      .tripUpdate(TRIP_1_ID, SCHEDULED)
      .addDelayedStopTime(0, 60)
      .addDelayedStopTime(1, 60)
      .build();

    assertNoFailure(rt.applyTripUpdates(List.of(update), List.of(period), FULL_DATASET));

    assertThat(state(env.tripData(TRIP_1_ID).tripTimes())).isEqualTo(RtState.UPDATED);
  }

  @Test
  void doesNotCancelTripDepartingAfterWindowEnd() {
    var period = new TripReplacementPeriod("A", WINDOW_END);

    assertNoFailure(rt.applyTripUpdates(List.of(), List.of(period), FULL_DATASET));

    // TRIP_2 departs at 13:00, the window ends at 12:20 — out of scope.
    assertThat(state(env.tripData(TRIP_2_ID).tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void doesNotCancelTripOnRouteNotInReplacementPeriods() {
    var period = new TripReplacementPeriod("A", WINDOW_END);

    assertNoFailure(rt.applyTripUpdates(List.of(), List.of(period), FULL_DATASET));

    // Route B is not in the replacement period list.
    assertThat(state(env.tripData("RouteBTrip").tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void emptyReplacementPeriodsLeavesAllTripsScheduled() {
    assertNoFailure(rt.applyTripUpdates(List.of(), List.of(), FULL_DATASET));

    assertThat(state(env.tripData(TRIP_1_ID).tripTimes())).isEqualTo(RtState.SCHEDULED);
    assertThat(state(env.tripData(TRIP_2_ID).tripTimes())).isEqualTo(RtState.SCHEDULED);
    assertThat(state(env.tripData("RouteBTrip").tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void replacementPeriodInPastIsIgnored() {
    var pastEnd = NOW.minusSeconds(60);
    var period = new TripReplacementPeriod("A", pastEnd);

    assertNoFailure(rt.applyTripUpdates(List.of(), List.of(period), FULL_DATASET));

    assertThat(state(env.tripData(TRIP_1_ID).tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void replacementPeriodForUnknownRouteIsIgnored() {
    var period = new TripReplacementPeriod("ROUTE_DOES_NOT_EXIST", WINDOW_END);

    assertNoFailure(rt.applyTripUpdates(List.of(), List.of(period), FULL_DATASET));

    assertThat(state(env.tripData(TRIP_1_ID).tripTimes())).isEqualTo(RtState.SCHEDULED);
    assertThat(state(env.tripData(TRIP_2_ID).tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void cancelsTripAlreadyEnRoute() {
    // TRIP_1 runs 12:00 -> 12:10. At 12:05 it has left its origin but is still running, so the
    // feed is authoritative about it: absent from the batch means it is not running.
    var midRunNow = SERVICE_DATE.atTime(12, 5).atZone(ZONE).toInstant();
    var midRunRt = GtfsRtTestHelper.of(env, midRunNow);
    var period = new TripReplacementPeriod("A", midRunNow.plusSeconds(30 * 60));

    assertNoFailure(midRunRt.applyTripUpdates(List.of(), List.of(period), FULL_DATASET));

    assertThat(state(env.tripData(TRIP_1_ID).tripTimes())).isEqualTo(RtState.CANCELED);
  }

  @Test
  void doesNotCancelTripThatHasAlreadyFinished() {
    // TRIP_1 reaches its last stop at 12:10. At 12:15 there is nothing left to cancel, and
    // rewriting history would strand a trip a client may still be showing.
    var afterNow = SERVICE_DATE.atTime(12, 15).atZone(ZONE).toInstant();
    var afterRt = GtfsRtTestHelper.of(env, afterNow);
    var period = new TripReplacementPeriod("A", afterNow.plusSeconds(30 * 60));

    assertNoFailure(afterRt.applyTripUpdates(List.of(), List.of(period), FULL_DATASET));

    assertThat(state(env.tripData(TRIP_1_ID).tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void cancelsMultipleAbsentTripsOnSameRoute() {
    // Add a third trip on route A inside the window.
    var envWithThreeBuilder = TransitTestEnvironment.of(SERVICE_DATE);
    var sa = envWithThreeBuilder.stop(STOP_A_ID);
    var sb = envWithThreeBuilder.stop(STOP_B_ID);
    var rA = envWithThreeBuilder.route("A");
    var envWithThree = envWithThreeBuilder
      .addTrip(TripInput.of("ATrip1").withRoute(rA).addStop(sa, "12:00").addStop(sb, "12:05"))
      .addTrip(TripInput.of("ATrip2").withRoute(rA).addStop(sa, "12:05").addStop(sb, "12:10"))
      .addTrip(TripInput.of("ATrip3").withRoute(rA).addStop(sa, "12:10").addStop(sb, "12:15"))
      .build();
    var rt2 = GtfsRtTestHelper.of(envWithThree, NOW);

    var period = new TripReplacementPeriod("A", WINDOW_END);
    assertNoFailure(rt2.applyTripUpdates(List.of(), List.of(period), FULL_DATASET));

    assertThat(state(envWithThree.tripData("ATrip1").tripTimes())).isEqualTo(RtState.CANCELED);
    assertThat(state(envWithThree.tripData("ATrip2").tripTimes())).isEqualTo(RtState.CANCELED);
    assertThat(state(envWithThree.tripData("ATrip3").tripTimes())).isEqualTo(RtState.CANCELED);
  }

  /**
   * NYCT's L feed strips the route-variant suffix from the trip id, sending e.g. {@code
   * 120650_L..S} while the static id is {@code ..._120650_L..S01R}. The partial matcher must
   * fall back to a variant-suffix match so the trip resolves and updates the static schedule
   * in place rather than appearing as a separate ADDED trip.
   */
  @Test
  void partialTripIdMatchByVariantSuffix() {
    var staticTripId = "L0S6-L-1053-S32_120650_L..S01R";
    var realtimeSuffix = "120650_L..S";

    var lBuilder = TransitTestEnvironment.of(SERVICE_DATE);
    var sa = lBuilder.stop(STOP_A_ID);
    var sb = lBuilder.stop(STOP_B_ID);
    var rL = lBuilder.route("L");
    var lEnv = lBuilder
      .addTrip(TripInput.of(staticTripId).withRoute(rL).addStop(sa, "12:00").addStop(sb, "12:10"))
      .build();
    var lRt = GtfsRtTestHelper.of(lEnv, NOW);

    var update = lRt
      .tripUpdate(realtimeSuffix, SCHEDULED)
      .withRouteId("L")
      .addDelayedStopTime(0, 60)
      .addDelayedStopTime(1, 60)
      .build();

    assertNoFailure(lRt.applyTripUpdates(List.of(update), List.of(), FULL_DATASET, true));

    // The trip resolved via variant-suffix match — UPDATED, not ADDED, and its scheduled
    // counterpart is the same record.
    assertThat(state(lEnv.tripData(staticTripId).tripTimes())).isEqualTo(RtState.UPDATED);
  }

  /**
   * NYC subway integration: the realtime feed sends a partial {@code trip_id} that is a suffix
   * of the static GTFS id. Once the partial matcher rewrites the descriptor to the full static
   * id, that resolved id must be added to the seen-set so cancel-by-omission does not then
   * cancel the same trip.
   */
  @Test
  void partialTripIdMatchExcludesTripFromCancellation() {
    var staticTripId = "A20111204SAT_021150_2..N08R";
    var realtimeSuffix = "021150_2..N08R";

    var subwayBuilder = TransitTestEnvironment.of(SERVICE_DATE);
    var sa = subwayBuilder.stop(STOP_A_ID);
    var sb = subwayBuilder.stop(STOP_B_ID);
    var rN = subwayBuilder.route("N");
    var subwayEnv = subwayBuilder
      .addTrip(TripInput.of(staticTripId).withRoute(rN).addStop(sa, "12:00").addStop(sb, "12:10"))
      .build();
    var subwayRt = GtfsRtTestHelper.of(subwayEnv, NOW);

    var update = subwayRt
      .tripUpdate(realtimeSuffix, SCHEDULED)
      .withRouteId("N")
      .addDelayedStopTime(0, 60)
      .addDelayedStopTime(1, 60)
      .build();
    var period = new TripReplacementPeriod("N", WINDOW_END);

    assertNoFailure(
      subwayRt.applyTripUpdates(List.of(update), List.of(period), FULL_DATASET, true)
    );

    // Trip was matched (via partial id) and present in the feed — must not be cancelled.
    assertThat(state(subwayEnv.tripData(staticTripId).tripTimes())).isEqualTo(RtState.UPDATED);
  }

  /**
   * Inside a replacement window the realtime feed is authoritative. An RT trip that doesn't
   * resolve to a static trip — e.g. NYCT's L feed where realtime trip ids drop the trailing
   * route-variant suffix — is rewritten to schedule_relationship=NEW so it flows through the
   * ADDED-trip path instead of being dropped with TRIP_NOT_FOUND.
   */
  @Test
  void rewritesUnresolvedScheduledTripOnCoveredRouteAsAdded() {
    var period = new TripReplacementPeriod("A", WINDOW_END);
    var update = rt
      .tripUpdate("UnresolvableRtId", SCHEDULED)
      .withRouteId("A")
      .addStopTime(STOP_A_ID, "12:05")
      .addStopTime(STOP_B_ID, "12:15")
      .build();

    assertNoFailure(rt.applyTripUpdates(List.of(update), List.of(period), FULL_DATASET));

    var added = env.tripData("UnresolvableRtId");
    assertThat(added.trip()).isNotNull();
    assertThat(state(added.tripTimes())).isEqualTo(RtState.ADDED);
  }

  @Test
  void doesNotRewriteUnresolvedTripWhenRouteNotCovered() {
    var update = rt
      .tripUpdate("UnresolvableRtId", SCHEDULED)
      .withRouteId("A")
      .addStopTime(STOP_A_ID, "12:05")
      .addStopTime(STOP_B_ID, "12:15")
      .build();

    var result = rt.applyTripUpdates(List.of(update), List.of(), FULL_DATASET);

    assertFailure(UpdateErrorType.TRIP_NOT_FOUND, result);
  }

  /**
   * A trip that already resolves to a static trip on a covered route must continue down the
   * SCHEDULED handler path — the ADDED rewrite is only meant for unresolved trips.
   */
  @Test
  void doesNotRewriteResolvedTripOnCoveredRoute() {
    var period = new TripReplacementPeriod("A", WINDOW_END);
    var update = rt
      .tripUpdate(TRIP_1_ID, SCHEDULED)
      .addDelayedStopTime(0, 60)
      .addDelayedStopTime(1, 60)
      .build();

    assertNoFailure(rt.applyTripUpdates(List.of(update), List.of(period), FULL_DATASET));

    assertThat(state(env.tripData(TRIP_1_ID).tripTimes())).isEqualTo(RtState.UPDATED);
  }

  /**
   * A CANCELED descriptor must not be rewritten to NEW even on a covered route — the rewrite
   * only applies to schedule_relationship=SCHEDULED.
   */
  @Test
  void doesNotRewriteCanceledDescriptorOnCoveredRoute() {
    var period = new TripReplacementPeriod("A", WINDOW_END);
    var update = rt.tripUpdate("UnresolvableRtId", CANCELED).withRouteId("A").build();

    var result = rt.applyTripUpdates(List.of(update), List.of(period), FULL_DATASET);

    // No rewrite: cancellation of a non-existent trip remains a "trip not found" failure.
    assertFailure(UpdateErrorType.NO_TRIP_FOR_CANCELLATION_FOUND, result);
  }

  /**
   * NYCT regularly reports trips with stops not present in the resolved static pattern (e.g.
   * an extra origin stop or a mid-trip reroute). When this happens on a covered route the
   * trip is rewritten to NEW so OTP synthesizes a fresh pattern from the RT data instead of
   * failing with INVALID_STOP_REFERENCE.
   */
  @Test
  void rewritesTripWithStopPatternDivergenceOnCoveredRouteAsAdded() {
    var period = new TripReplacementPeriod("A", WINDOW_END);
    // TRIP_1's static pattern is [stopA, stopB]. The RT update adds stopC which is not in
    // that pattern.
    var update = rt
      .tripUpdate(TRIP_1_ID, SCHEDULED)
      .withRouteId("A")
      .addStopTime(STOP_A_ID, "12:01")
      .addStopTime(STOP_C_ID, "12:05")
      .addStopTime(STOP_B_ID, "12:11")
      .build();

    assertNoFailure(rt.applyTripUpdates(List.of(update), List.of(period), FULL_DATASET));

    // The rewrite uses REPLACEMENT semantics: the trip identity is preserved and the stop
    // pattern is replaced, so the trip ends up with realTimeState MODIFIED.
    var modified = env.tripData(TRIP_1_ID);
    assertThat(state(modified.tripTimes())).isEqualTo(RtState.MODIFIED);
  }

  @Test
  void doesNotRewriteTripWithStopPatternDivergenceWhenRouteNotCovered() {
    var update = rt
      .tripUpdate(TRIP_1_ID, SCHEDULED)
      .withRouteId("A")
      .addStopTime(STOP_A_ID, "12:01")
      .addStopTime(STOP_C_ID, "12:05")
      .addStopTime(STOP_B_ID, "12:11")
      .build();

    var result = rt.applyTripUpdates(List.of(update), List.of(), FULL_DATASET);

    assertFailure(UpdateErrorType.INVALID_STOP_REFERENCE, result);
  }

  @Test
  void multipleReplacementPeriodsCancelOnEachRoute() {
    var periods = List.of(
      new TripReplacementPeriod("A", WINDOW_END),
      new TripReplacementPeriod("B", WINDOW_END)
    );

    assertNoFailure(rt.applyTripUpdates(List.of(), periods, FULL_DATASET));

    assertThat(state(env.tripData(TRIP_1_ID).tripTimes())).isEqualTo(RtState.CANCELED);
    assertThat(state(env.tripData("RouteBTrip").tripTimes())).isEqualTo(RtState.CANCELED);
    // Trip 2 still outside the window.
    assertThat(state(env.tripData(TRIP_2_ID).tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  /**
   * The RealTimeState enum was removed from the core model upstream; this mirrors the
   * predicate order of {@code RealtimeStateMapper} for assertion purposes.
   */
  private enum RtState {
    SCHEDULED,
    UPDATED,
    CANCELED,
    ADDED,
    MODIFIED,
  }

  private static RtState state(TripTimes<?> tripTimes) {
    if (tripTimes instanceof RealTimeTripTimes rt) {
      if (rt.isCanceled() || rt.isDeleted()) {
        return RtState.CANCELED;
      }
      if (rt.isAdded()) {
        return RtState.ADDED;
      }
      if (rt.isTripPatternModified()) {
        return RtState.MODIFIED;
      }
      if (rt.hasAnyUpdates()) {
        return RtState.UPDATED;
      }
    }
    return RtState.SCHEDULED;
  }
}
