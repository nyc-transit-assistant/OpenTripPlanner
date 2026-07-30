package org.opentripplanner.updater.trip.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ExtensionRegistry;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TimeRange;
import com.google.transit.realtime.NyctSubway;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TripReplacementPeriodTest {

  // Anchor time for tests. Header timestamps are set near here; "now" for parsing is also
  // pinned so the freshness guard is deterministic.
  private static final long HEADER_TS = 1_700_000_000L;
  private static final Instant NOW = Instant.ofEpochSecond(HEADER_TS + 30);

  private static FeedHeader.Builder freshHeader() {
    return FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(HEADER_TS);
  }

  @Test
  void noNyctExtensionReturnsEmpty() {
    var header = freshHeader().build();
    assertTrue(TripReplacementPeriod.fromNyctFeedHeader(header, NOW).isEmpty());
  }

  @Test
  void emptyTripReplacementPeriodListReturnsEmpty() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder().setNyctSubwayVersion("1.0").build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    assertTrue(TripReplacementPeriod.fromNyctFeedHeader(header, NOW).isEmpty());
  }

  @Test
  void parsesValidPeriod() {
    // 30 min after header
    long endEpoch = HEADER_TS + 1800;
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("A")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(endEpoch))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    var periods = TripReplacementPeriod.fromNyctFeedHeader(header, NOW);

    assertEquals(1, periods.size());
    assertEquals("A", periods.get(0).routeId());
    assertEquals(Instant.ofEpochSecond(endEpoch), periods.get(0).endTime());
  }

  @Test
  void parsesMultiplePeriods() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("A")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 100))
      )
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("C")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 200))
      )
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("E")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 300))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    var periods = TripReplacementPeriod.fromNyctFeedHeader(header, NOW);

    assertEquals(3, periods.size());
    assertEquals("A", periods.get(0).routeId());
    assertEquals("C", periods.get(1).routeId());
    assertEquals("E", periods.get(2).routeId());
  }

  @Test
  void skipsPeriodMissingRouteId() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder().setReplacementPeriod(
          TimeRange.newBuilder().setEnd(HEADER_TS + 100)
        )
      )
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("A")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 200))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    var periods = TripReplacementPeriod.fromNyctFeedHeader(header, NOW);

    assertEquals(1, periods.size());
    assertEquals("A", periods.get(0).routeId());
  }

  @Test
  void skipsPeriodMissingReplacementPeriod() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(NyctSubway.TripReplacementPeriod.newBuilder().setRouteId("A"))
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("C")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 200))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    var periods = TripReplacementPeriod.fromNyctFeedHeader(header, NOW);

    assertEquals(1, periods.size());
    assertEquals("C", periods.get(0).routeId());
  }

  /**
   * Round-trip: serialize a FeedMessage carrying the NYCT extension and parse it back using the
   * same registry pattern that {@code HttpTripUpdateSource} and {@code MqttGtfsRealtimeUpdater}
   * use. Catches the regression where the production code forgets to register
   * {@code NyctSubway.registerAllExtensions(...)}.
   */
  @Test
  void parseFromBytesWithRegistryReturnsPeriods() throws Exception {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("A")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 1800))
      )
      .build();
    var bytes = FeedMessage.newBuilder()
      .setHeader(freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext))
      .build()
      .toByteArray();

    var registry = ExtensionRegistry.newInstance();
    NyctSubway.registerAllExtensions(registry);
    var parsed = FeedMessage.parseFrom(bytes, registry);

    var periods = TripReplacementPeriod.fromNyctFeedHeader(parsed.getHeader(), NOW);

    assertEquals(1, periods.size());
    assertEquals("A", periods.get(0).routeId());
    assertEquals(Instant.ofEpochSecond(HEADER_TS + 1800), periods.get(0).endTime());
  }

  /**
   * Without the registry registration the extension is silently dropped during parsing. This
   * test pins that behavior so the {@code parseFromBytesWithRegistryReturnsPeriods} test
   * remains meaningful — i.e. forgetting to register would actually break things.
   */
  @Test
  void parseFromBytesWithoutRegistryDropsExtension() throws Exception {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("A")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 1800))
      )
      .build();
    var bytes = FeedMessage.newBuilder()
      .setHeader(freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext))
      .build()
      .toByteArray();

    // Parse without registering the extension.
    var parsed = FeedMessage.parseFrom(bytes);

    assertTrue(TripReplacementPeriod.fromNyctFeedHeader(parsed.getHeader(), NOW).isEmpty());
  }

  @Test
  void skipsPeriodMissingEnd() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("A")
          .setReplacementPeriod(TimeRange.newBuilder().setStart(HEADER_TS + 50))
      )
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("C")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 200))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    var periods = TripReplacementPeriod.fromNyctFeedHeader(header, NOW);

    assertEquals(1, periods.size());
    assertEquals("C", periods.get(0).routeId());
  }

  /**
   * NYCT IRT (/gtfs) bug: the feed publishes {@code end == header.timestamp}, giving zero
   * forward authority. Substitute the documented 30-minute default look-ahead so the feed
   * actually covers the trips it reports.
   */
  @Test
  void rewritesEndEqualToHeaderTimestamp() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("1")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    var periods = TripReplacementPeriod.fromNyctFeedHeader(header, NOW);

    assertEquals(1, periods.size());
    assertEquals(Instant.ofEpochSecond(HEADER_TS + 1800), periods.get(0).endTime());
  }

  @Test
  void rewritesEndBeforeHeaderTimestamp() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("1")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS - 500))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    var periods = TripReplacementPeriod.fromNyctFeedHeader(header, NOW);

    assertEquals(1, periods.size());
    assertEquals(Instant.ofEpochSecond(HEADER_TS + 1800), periods.get(0).endTime());
  }

  @Test
  void staleFeedReturnsEmpty() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("A")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 1800))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    // 6 minutes after the header — over the 5-minute freshness threshold.
    var staleNow = Instant.ofEpochSecond(HEADER_TS + 6 * 60);

    assertTrue(TripReplacementPeriod.fromNyctFeedHeader(header, staleNow).isEmpty());
  }

  @Test
  void missingHeaderTimestampReturnsEmpty() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("A")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 1800))
      )
      .build();
    var header = FeedHeader.newBuilder()
      .setGtfsRealtimeVersion("2.0")
      .setExtension(NyctSubway.nyctFeedHeader, ext)
      .build();

    assertTrue(TripReplacementPeriod.fromNyctFeedHeader(header, NOW).isEmpty());
  }

  /**
   * NYCT IRT inconsistency: the replacement period uses {@code route_id="S"} for the Times
   * Square shuttle while the trip_updates use {@code route_id="GS"}. Emit a parallel period
   * under the "GS" alias so coverage matches the trip data.
   */
  @Test
  void emitsGsAliasForShuttleRoute() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("S")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 1800))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    var periods = TripReplacementPeriod.fromNyctFeedHeader(header, NOW);

    assertEquals(2, periods.size());
    assertEquals("S", periods.get(0).routeId());
    assertEquals("GS", periods.get(1).routeId());
    assertEquals(periods.get(0).endTime(), periods.get(1).endTime());
  }

  /**
   * NYCT IRT lists routes "6" and "7" but the trip descriptors use "6X" and "7X" for the
   * express variants on each line. Emit parallel periods so the express trips count as
   * covered by the parent route's authority.
   */
  @Test
  void emitsExpressAliasesForIrtRoutes() {
    var ext = NyctSubway.NyctFeedHeader.newBuilder()
      .setNyctSubwayVersion("1.0")
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("6")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 1800))
      )
      .addTripReplacementPeriod(
        NyctSubway.TripReplacementPeriod.newBuilder()
          .setRouteId("7")
          .setReplacementPeriod(TimeRange.newBuilder().setEnd(HEADER_TS + 1800))
      )
      .build();
    var header = freshHeader().setExtension(NyctSubway.nyctFeedHeader, ext).build();

    var periods = TripReplacementPeriod.fromNyctFeedHeader(header, NOW);

    assertEquals(4, periods.size());
    assertEquals("6", periods.get(0).routeId());
    assertEquals("6X", periods.get(1).routeId());
    assertEquals("7", periods.get(2).routeId());
    assertEquals("7X", periods.get(3).routeId());
  }
}
