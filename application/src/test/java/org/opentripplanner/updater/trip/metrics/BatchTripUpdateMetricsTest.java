package org.opentripplanner.updater.trip.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.updater.spi.UpdateResult;
import org.opentripplanner.updater.trip.UrlUpdaterParameters;

class BatchTripUpdateMetricsTest {

  private static UrlUpdaterParameters params(String configRef) {
    return new UrlUpdaterParameters() {
      @Override
      public String url() {
        return "http://gtfs-feed-proxy:4200/nyct/gtfs-ace";
      }

      @Override
      public String configRef() {
        return configRef;
      }

      @Override
      public String feedId() {
        return "mta-subway";
      }
    };
  }

  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    Metrics.addRegistry(registry);
  }

  @AfterEach
  void tearDown() {
    Metrics.removeRegistry(registry);
    registry.close();
  }

  @Test
  void updaterLabelIsTheUrlPath() {
    assertEquals("nyct/gtfs-ace", TripUpdateMetrics.updaterLabel(params("x").url()));
    assertEquals("not a url", TripUpdateMetrics.updaterLabel("not a url"));
  }

  @Test
  void countersAccumulateAcrossBatches() {
    OTPFeature.ActuatorAPI.testOn(() -> {
      var metrics = BatchTripUpdateMetrics.createBatch(params("counters-test"));
      metrics.setGauges(UpdateResult.of(List.of(), List.of()));
      metrics.setGauges(UpdateResult.of(List.of(), List.of()));

      assertEquals(2.0, counterValue("batch.trip.updates.attempts", "counters-test"));
      assertTrue(gaugeValue("batch.trip.updates.last_attempt_epoch", "counters-test") > 0);
      assertTrue(gaugeValue("batch.trip.updates.last_success_epoch", "counters-test") > 0);
      assertEquals(
        "nyct/gtfs-ace",
        registry
          .get("batch.trip.updates.attempts")
          .tag("configRef", "counters-test")
          .counter()
          .getId()
          .getTag("updater")
      );
    });
  }

  @Test
  void crashIsRecordedWithoutTouchingSuccess() {
    OTPFeature.ActuatorAPI.testOn(() -> {
      var metrics = BatchTripUpdateMetrics.createBatch(params("crash-test"));
      metrics.recordCrash(new RuntimeException("boom"));

      assertEquals(1.0, counterValue("batch.trip.updates.attempts", "crash-test"));
      assertEquals(1.0, counterValue("batch.trip.updates.crashed", "crash-test"));
      assertTrue(gaugeValue("batch.trip.updates.last_attempt_epoch", "crash-test") > 0);
      // the freeze signature: attempts advanced, success timestamp did not
      assertEquals(0.0, gaugeValue("batch.trip.updates.last_success_epoch", "crash-test"));
    });
  }

  private double counterValue(String name, String configRef) {
    return registry.get(name).tag("configRef", configRef).counter().count();
  }

  private double gaugeValue(String name, String configRef) {
    return registry.get(name).tag("configRef", configRef).gauge().value();
  }
}
