package org.opentripplanner.updater.trip.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedIdForTestFactory;
import org.opentripplanner.transit.model.TransitTestEnvironment;
import org.opentripplanner.transit.model.TransitTestEnvironmentBuilder;
import org.opentripplanner.transit.model.TripInput;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.site.RegularStop;

/**
 * The MTA Metro-North case: realtime trip ids are internal numbers unknown to the static GTFS,
 * and the train number (FeedEntity id / vehicle label) matches static trip_short_name.
 */
public class GtfsRealtimeTrainNumberTripMatcherTest {

  private static final String FEED_ID = FeedScopedIdForTestFactory.FEED_ID;
  private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 16);
  private static final String GTFS_SERVICE_DATE = "20260816";
  private static final String STATIC_TRIP_ID = "246125524612837+2273724+0";
  private static final String INTERNAL_RT_TRIP_ID = "3174341";
  private static final String TRAIN_NUMBER = "6300";

  private final TransitTestEnvironmentBuilder builder = TransitTestEnvironment.of(SERVICE_DATE);
  private final RegularStop STOP_1 = builder.stop("s1");
  private final RegularStop STOP_2 = builder.stop("s2");
  private final Route ROUTE_NEW_HAVEN = builder.route("3");

  private final TransitTestEnvironment env = builder
    .addTrip(
      TripInput.of(STATIC_TRIP_ID)
        .withShortName(TRAIN_NUMBER)
        .withRoute(ROUTE_NEW_HAVEN)
        .addStop(STOP_1, "00:25:00")
        .addStop(STOP_2, "01:10:00")
    )
    .build();

  private GtfsRealtimeTrainNumberTripMatcher matcher() {
    return new GtfsRealtimeTrainNumberTripMatcher(env.transitService());
  }

  private static TripDescriptor.Builder descriptor() {
    return TripDescriptor.newBuilder()
      .setTripId(INTERNAL_RT_TRIP_ID)
      .setStartDate(GTFS_SERVICE_DATE)
      .setRouteId("3");
  }

  @Test
  void resolvesTrainNumberToStaticTripId() {
    var matched = matcher().match(FEED_ID, TRAIN_NUMBER, descriptor().build());
    assertEquals(STATIC_TRIP_ID, matched.getTripId());
    assertEquals(GTFS_SERVICE_DATE, matched.getStartDate());
  }

  @Test
  void leadingZerosOnTrainNumberAreIgnored() {
    var matched = matcher().match(FEED_ID, "0" + TRAIN_NUMBER, descriptor().build());
    assertEquals(STATIC_TRIP_ID, matched.getTripId());
  }

  @Test
  void resolvesWithoutRouteId() {
    // Vehicle positions omit route_id; the train number alone must suffice.
    var trip = descriptor().clearRouteId().build();
    var matched = matcher().match(FEED_ID, TRAIN_NUMBER, trip);
    assertEquals(STATIC_TRIP_ID, matched.getTripId());
  }

  @Test
  void alreadyResolvedTripIdPassesThrough() {
    var trip = descriptor().setTripId(STATIC_TRIP_ID).build();
    var matched = matcher().match(FEED_ID, TRAIN_NUMBER, trip);
    assertEquals(STATIC_TRIP_ID, matched.getTripId());
  }

  @Test
  void unknownTrainNumberLeavesDescriptorUntouched() {
    var trip = descriptor().build();
    var matched = matcher().match(FEED_ID, "9999", trip);
    assertEquals(INTERNAL_RT_TRIP_ID, matched.getTripId());
  }

  @Test
  void inactiveServiceDateFallsBackToNeighbourDatesThenGivesUp() {
    // Two days out is beyond the ±1 day fallback window — no match.
    var trip = descriptor().setStartDate("20260820").build();
    var matched = matcher().match(FEED_ID, TRAIN_NUMBER, trip);
    assertEquals(INTERNAL_RT_TRIP_ID, matched.getTripId());
  }

  @Test
  void neighbourDateMatchRewritesStartDate() {
    // Claimed date is the day before the active service date; the ±1 day fallback should
    // land on the real date and rewrite start_date to match.
    var trip = descriptor().setStartDate("20260815").build();
    var matched = matcher().match(FEED_ID, TRAIN_NUMBER, trip);
    assertEquals(STATIC_TRIP_ID, matched.getTripId());
    assertEquals(GTFS_SERVICE_DATE, matched.getStartDate());
  }

  @Test
  void vehiclePositionDescriptorWithoutStartDateFallsBackToToday() {
    // Metro-North vehicle positions carry only a trip id equal to the train number — no
    // start date, no route. The matcher must fall back to today's service date.
    var todayBuilder = TransitTestEnvironment.of(LocalDate.now());
    var s1 = todayBuilder.stop("t1");
    var s2 = todayBuilder.stop("t2");
    var route = todayBuilder.route("3");
    var todayEnv = todayBuilder
      .addTrip(
        TripInput.of(STATIC_TRIP_ID)
          .withShortName(TRAIN_NUMBER)
          .withRoute(route)
          .addStop(s1, "10:00:00")
          .addStop(s2, "11:00:00")
      )
      .build();
    var matcher = new GtfsRealtimeTrainNumberTripMatcher(todayEnv.transitService());
    var trip = TripDescriptor.newBuilder().setTripId(TRAIN_NUMBER).build();
    var matched = matcher.match(FEED_ID, TRAIN_NUMBER, trip);
    assertEquals(STATIC_TRIP_ID, matched.getTripId());
    assertEquals("", matched.getStartDate());
  }

  @Test
  void postMidnightTrainAnchorsToPreviousServiceDate() {
    // MNR claims start_date=D start_time=00:08 for a train that static encodes as a 24:08
    // departure on service date D-1. The matcher must pick the D-1 anchor (time-consistent)
    // and rewrite start_date, or the applied times come out +24h and get reverted.
    var b = TransitTestEnvironment.of(SERVICE_DATE.minusDays(1));
    var s1 = b.stop("p1");
    var s2 = b.stop("p2");
    var route = b.route("3");
    var lateEnv = b
      .addTrip(
        TripInput.of(STATIC_TRIP_ID)
          .withShortName(TRAIN_NUMBER)
          .withRoute(route)
          .addStop(s1, "24:08:00")
          .addStop(s2, "25:10:00")
      )
      .build();
    var matcher = new GtfsRealtimeTrainNumberTripMatcher(lateEnv.transitService());
    var trip = TripDescriptor.newBuilder()
      .setTripId(INTERNAL_RT_TRIP_ID)
      .setStartDate(GTFS_SERVICE_DATE)
      .setStartTime("00:08:00")
      .setRouteId("3")
      .build();
    var matched = matcher.match(FEED_ID, TRAIN_NUMBER, trip);
    assertEquals(STATIC_TRIP_ID, matched.getTripId());
    assertEquals("20260815", matched.getStartDate());
  }

  @Test
  void synthesisGivesUnresolvedAddedTrainsADeterministicIdentity() {
    // The NJT rail case: a genuine unscheduled train arrives ADDED with no trip id and no
    // route. With synthesis configured it must get `<prefix><train>-<date>`, the default
    // route, and stay ADDED — the same identity every polling cycle.
    var matcher = new GtfsRealtimeTrainNumberTripMatcher(
      env.transitService(),
      new GtfsRealtimeTrainNumberTripMatcher.SynthesisConfig("NJT-", "17")
    );
    var trip = TripDescriptor.newBuilder()
      .setScheduleRelationship(TripDescriptor.ScheduleRelationship.ADDED)
      .setStartDate(GTFS_SERVICE_DATE)
      .build();
    var matched = matcher.match(FEED_ID, "4501", trip);
    assertEquals("NJT-4501-" + GTFS_SERVICE_DATE, matched.getTripId());
    assertEquals("17", matched.getRouteId());
    assertEquals(TripDescriptor.ScheduleRelationship.ADDED, matched.getScheduleRelationship());
  }

  @Test
  void synthesisNeverTouchesScheduledEntitiesWithRealIds() {
    // A SCHEDULED entity whose id simply doesn't resolve is someone else's problem
    // (TRIP_NOT_FOUND); synthesizing it would fabricate service.
    var matcher = new GtfsRealtimeTrainNumberTripMatcher(
      env.transitService(),
      new GtfsRealtimeTrainNumberTripMatcher.SynthesisConfig("NJT-", "17")
    );
    var trip = TripDescriptor.newBuilder()
      .setTripId("stale-but-real-id")
      .setScheduleRelationship(TripDescriptor.ScheduleRelationship.SCHEDULED)
      .setStartDate(GTFS_SERVICE_DATE)
      .build();
    var matched = matcher.match(FEED_ID, "4501", trip);
    assertEquals("stale-but-real-id", matched.getTripId());
  }

  @Test
  void resolvableAddedTrainIsRescheduledNotSynthesized() {
    // NJT mislabels some scheduled trains ADDED (late-night next-service-day departures);
    // when the train number resolves, the static identity wins over synthesis.
    var matcher = new GtfsRealtimeTrainNumberTripMatcher(
      env.transitService(),
      new GtfsRealtimeTrainNumberTripMatcher.SynthesisConfig("NJT-", "17")
    );
    var trip = TripDescriptor.newBuilder()
      .setScheduleRelationship(TripDescriptor.ScheduleRelationship.ADDED)
      .setStartDate(GTFS_SERVICE_DATE)
      .setRouteId("3")
      .build();
    var matched = matcher.match(FEED_ID, TRAIN_NUMBER, trip);
    assertEquals(STATIC_TRIP_ID, matched.getTripId());
  }

  @Test
  void blankButPresentTripIdDoesNotThrowAndSynthesizes() {
    // NJT rail ADDED entities carry trip_id set to EMPTY STRING (proto2 presence). The
    // already-resolved check must not construct a FeedScopedId from it — that throws and
    // kills the whole batch (production incident 2026-08-16).
    var matcher = new GtfsRealtimeTrainNumberTripMatcher(
      env.transitService(),
      new GtfsRealtimeTrainNumberTripMatcher.SynthesisConfig("NJT-", "17")
    );
    var trip = TripDescriptor.newBuilder()
      .setTripId("")
      .setScheduleRelationship(TripDescriptor.ScheduleRelationship.ADDED)
      .setStartDate(GTFS_SERVICE_DATE)
      .build();
    var matched = matcher.match(FEED_ID, "4501", trip);
    assertEquals("NJT-4501-" + GTFS_SERVICE_DATE, matched.getTripId());
  }

  @Test
  void blankTrainNumberLeavesDescriptorUntouched() {
    var matched = matcher().match(FEED_ID, "  ", descriptor().build());
    assertEquals(INTERNAL_RT_TRIP_ID, matched.getTripId());
    assertNull(GtfsRealtimeTrainNumberTripMatcher.normalizeTrainNumber(null));
  }
}
