package org.opentripplanner.updater.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CombinedFeedLabelTest {

  @Test
  void singleFeedKeepsItsId() {
    assertEquals("mta-subway", UrlUpdaterParameters.combinedFeedLabel(List.of("mta-subway")));
  }

  @Test
  void commonPrefixCollapses() {
    assertEquals(
      "mta-bus",
      UrlUpdaterParameters.combinedFeedLabel(
        List.of(
          "mta-bus-bronx",
          "mta-bus-brooklyn",
          "mta-bus-manhattan",
          "mta-bus-queens",
          "mta-bus-staten-island",
          "mta-bus-company"
        )
      )
    );
  }

  @Test
  void noSharedPrefixJoins() {
    assertEquals("alpha,beta", UrlUpdaterParameters.combinedFeedLabel(List.of("alpha", "beta")));
  }
}
