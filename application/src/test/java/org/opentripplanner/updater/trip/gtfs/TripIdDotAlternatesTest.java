package org.opentripplanner.updater.trip.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TripIdDotAlternatesTest {

  @Test
  void singleDotGainsDoubleDotTwin() {
    assertEquals(
      List.of("116600_SI.N03R", "116600_SI..N03R"),
      GtfsRealtimePartialTripIdMatcher.tripIdDotAlternates("116600_SI.N03R")
    );
  }

  @Test
  void doubleDotGainsSingleDotTwin() {
    assertEquals(
      List.of("110400_L..N", "110400_L.N"),
      GtfsRealtimePartialTripIdMatcher.tripIdDotAlternates("110400_L..N")
    );
  }

  @Test
  void dotlessIdStaysAlone() {
    assertEquals(
      List.of("12345_GS"),
      GtfsRealtimePartialTripIdMatcher.tripIdDotAlternates("12345_GS")
    );
  }
}
