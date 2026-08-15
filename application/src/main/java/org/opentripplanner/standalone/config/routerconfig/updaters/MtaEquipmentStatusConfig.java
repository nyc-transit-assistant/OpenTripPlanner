package org.opentripplanner.standalone.config.routerconfig.updaters;

import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_9;

import java.time.Duration;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;
import org.opentripplanner.updater.equipment.MtaEquipmentStatusUpdaterParameters;

public class MtaEquipmentStatusConfig {

  public static MtaEquipmentStatusUpdaterParameters create(String configRef, NodeAdapter c) {
    return new MtaEquipmentStatusUpdaterParameters(
      configRef,
      c
        .of("url")
        .since(V2_9)
        .summary("URL of the MTA elevator/escalator current-outages feed (nyct_ene.json shape).")
        .asString(),
      c
        .of("frequency")
        .since(V2_9)
        .summary("How often the feed is polled.")
        .asDuration(Duration.ofMinutes(1)),
      c
        .of("staleAfter")
        .since(V2_9)
        .summary(
          "How long the last successful poll stays trustworthy. When exceeded, equipment " +
            "status degrades to unknown (null) rather than serving frozen data."
        )
        .asDuration(Duration.ofMinutes(30)),
      HttpHeadersConfig.headers(c, V2_9)
    );
  }
}
