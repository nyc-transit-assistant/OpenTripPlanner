package org.opentripplanner.updater.trip.gtfs.moduletests.cancellation;

import static com.google.common.truth.Truth.assertThat;
import static com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED;
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
import org.opentripplanner.updater.trip.RealtimeTestConstants;
import org.opentripplanner.updater.trip.gtfs.GtfsRtTestHelper;
import org.opentripplanner.updater.trip.gtfs.UnmatchedTripCanceler;

/**
 * Tests for ghost-trip cancellation ({@link UnmatchedTripCanceler}): a scheduled trip whose
 * first N stop departures have elapsed with no realtime match this service day is cancelled;
 * matched trips, not-yet-due trips, and already-finished trips are untouched, and the matched
 * memory persists across polling cycles.
 */
class UnmatchedTripCancelerTest implements RealtimeTestConstants {

  private static final LocalDate SERVICE_DATE = LocalDate.of(2024, 5, 7);
  private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
  /** Between DUE's 3rd-stop departure (12:10) and its last arrival (12:40). */
  private static final Instant NOW = SERVICE_DATE.atTime(12, 30).atZone(ZONE).toInstant();

  private final TransitTestEnvironmentBuilder envBuilder = TransitTestEnvironment.of(SERVICE_DATE);
  private final RegularStop stopA = envBuilder.stop(STOP_A_ID);
  private final RegularStop stopB = envBuilder.stop(STOP_B_ID);
  private final RegularStop stopC = envBuilder.stop(STOP_C_ID);
  private final RegularStop stopD = envBuilder.stop(STOP_D_ID);
  private final Route route = envBuilder.route("A");

  private final TransitTestEnvironment env = envBuilder
    // 3rd stop departs 12:10 (< NOW), last arrival 12:40 (> NOW): due for cancellation
    .addTrip(
      TripInput.of("Due")
        .withRoute(route)
        .addStop(stopA, "12:00")
        .addStop(stopB, "12:05")
        .addStop(stopC, "12:10")
        .addStop(stopD, "12:40")
    )
    // 3rd stop departs 12:45 (> NOW): not yet due
    .addTrip(
      TripInput.of("NotYetDue")
        .withRoute(route)
        .addStop(stopA, "12:25")
        .addStop(stopB, "12:35")
        .addStop(stopC, "12:45")
        .addStop(stopD, "13:00")
    )
    // finished before NOW: never cancelled, nothing left to cancel
    .addTrip(
      TripInput.of("Finished")
        .withRoute(route)
        .addStop(stopA, "10:00")
        .addStop(stopB, "10:05")
        .addStop(stopC, "10:10")
        .addStop(stopD, "10:20")
    )
    // 2 stops (< N): cutoff falls back to the last stop (12:20 > NOW): not yet due
    .addTrip(TripInput.of("Short").withRoute(route).addStop(stopA, "12:05").addStop(stopB, "12:50"))
    .build();

  private final GtfsRtTestHelper rt = GtfsRtTestHelper.of(env, NOW);

  private UnmatchedTripCanceler canceler() {
    return new UnmatchedTripCanceler(3, "test/updater");
  }

  @Test
  void cancelsUnmatchedTripAfterElapsedStops() {
    rt.withUnmatchedTripCanceler(canceler());
    var anyOtherTrip = rt.tripUpdate("NotYetDue", SCHEDULED).addDelayedStopTime(0, 30).build();

    assertNoFailure(rt.applyTripUpdates(List.of(anyOtherTrip), List.of(), FULL_DATASET));

    assertThat(state(env.tripData("Due").tripTimes())).isEqualTo(RtState.CANCELED);
    assertThat(state(env.tripData("NotYetDue").tripTimes())).isEqualTo(RtState.UPDATED);
    assertThat(state(env.tripData("Finished").tripTimes())).isEqualTo(RtState.SCHEDULED);
    assertThat(state(env.tripData("Short").tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void doesNotCancelMatchedTrip() {
    rt.withUnmatchedTripCanceler(canceler());
    var update = rt.tripUpdate("Due", SCHEDULED).addDelayedStopTime(0, 30).build();

    assertNoFailure(rt.applyTripUpdates(List.of(update), List.of(), FULL_DATASET));

    assertThat(state(env.tripData("Due").tripTimes())).isEqualTo(RtState.UPDATED);
  }

  @Test
  void matchedMemoryPersistsAcrossPolls() {
    // Poll 1 matches the trip; poll 2 no longer carries it (mid-route tracking loss).
    // The memory prevents cancellation — the trip reverts to plain schedule instead.
    rt.withUnmatchedTripCanceler(canceler());
    var update = rt.tripUpdate("Due", SCHEDULED).addDelayedStopTime(0, 30).build();
    var other = rt.tripUpdate("NotYetDue", SCHEDULED).addDelayedStopTime(0, 30).build();

    assertNoFailure(rt.applyTripUpdates(List.of(update), List.of(), FULL_DATASET));
    assertNoFailure(rt.applyTripUpdates(List.of(other), List.of(), FULL_DATASET));

    assertThat(state(env.tripData("Due").tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void emptyBatchDoesNotCancelAnything() {
    // An empty batch is a feed hiccup, not evidence that every scheduled trip is missing.
    rt.withUnmatchedTripCanceler(canceler());

    assertNoFailure(rt.applyTripUpdates(List.of(), List.of(), FULL_DATASET));

    assertThat(state(env.tripData("Due").tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void withoutCancelerNothingIsCancelled() {
    var anyOtherTrip = rt.tripUpdate("NotYetDue", SCHEDULED).addDelayedStopTime(0, 30).build();

    assertNoFailure(rt.applyTripUpdates(List.of(anyOtherTrip), List.of(), FULL_DATASET));

    assertThat(state(env.tripData("Due").tripTimes())).isEqualTo(RtState.SCHEDULED);
  }

  @Test
  void reappearingTripRecoversOnNextPoll() {
    rt.withUnmatchedTripCanceler(canceler());
    var other = rt.tripUpdate("NotYetDue", SCHEDULED).addDelayedStopTime(0, 30).build();
    var due = rt.tripUpdate("Due", SCHEDULED).addDelayedStopTime(0, 30).build();

    assertNoFailure(rt.applyTripUpdates(List.of(other), List.of(), FULL_DATASET));
    assertThat(state(env.tripData("Due").tripTimes())).isEqualTo(RtState.CANCELED);

    assertNoFailure(rt.applyTripUpdates(List.of(other, due), List.of(), FULL_DATASET));
    assertThat(state(env.tripData("Due").tripTimes())).isEqualTo(RtState.UPDATED);
  }

  private enum RtState {
    SCHEDULED,
    UPDATED,
    CANCELED,
    ADDED,
    MODIFIED,
  }

  private static RtState state(TripTimes<?> tripTimes) {
    if (tripTimes instanceof RealTimeTripTimes realTime) {
      if (realTime.isCanceled() || realTime.isDeleted()) {
        return RtState.CANCELED;
      }
      if (realTime.isAdded()) {
        return RtState.ADDED;
      }
      if (realTime.isTripPatternModified()) {
        return RtState.MODIFIED;
      }
      if (realTime.hasAnyUpdates()) {
        return RtState.UPDATED;
      }
    }
    return RtState.SCHEDULED;
  }
}
