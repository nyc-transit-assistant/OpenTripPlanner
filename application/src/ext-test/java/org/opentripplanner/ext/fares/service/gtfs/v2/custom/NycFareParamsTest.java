package org.opentripplanner.ext.fares.service.gtfs.v2.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.ext.fares.FaresConfiguration;

class NycFareParamsTest {

  private static final String CONFIG = """
    {
      "type": "nyc",
      "subwayFeed": "mta-subway",
      "omnyFeeds": ["mta-subway", "mta-bus-brooklyn"],
      "freeOutOfSystemTransfers": [["mta-subway:CX613", "mta-subway:B08"]],
      "transferWindowMinutes": 120
    }
    """;

  @Test
  void parsesFullConfig() throws Exception {
    var params = NycFareParams.fromConfig(new ObjectMapper().readTree(CONFIG));
    assertEquals("mta-subway", params.subwayFeed());
    assertEquals(Duration.ofMinutes(120), params.transferWindow());
    var pair = params.freeOutOfSystemTransfers().iterator().next();
    assertTrue(
      pair.matches(
        FeedScopedId.parseStrict("mta-subway:B08"),
        FeedScopedId.parseStrict("mta-subway:CX613")
      )
    );
  }

  @Test
  void faresConfigurationResolvesNycType() throws Exception {
    var factory = FaresConfiguration.fromConfig(new ObjectMapper().readTree(CONFIG));
    assertInstanceOf(NycFareServiceFactory.class, factory);
  }

  @Test
  void rejectsMissingSubwayFeed() throws Exception {
    var config = new ObjectMapper().readTree("{\"type\": \"nyc\", \"omnyFeeds\": [\"a\"]}");
    assertThrows(IllegalArgumentException.class, () -> NycFareParams.fromConfig(config));
  }

  @Test
  void rejectsSubwayFeedOutsideOmnyGroup() throws Exception {
    var config = new ObjectMapper().readTree(
      "{\"type\": \"nyc\", \"subwayFeed\": \"mta-subway\", \"omnyFeeds\": [\"other\"]}"
    );
    assertThrows(IllegalArgumentException.class, () -> NycFareParams.fromConfig(config));
  }
}
