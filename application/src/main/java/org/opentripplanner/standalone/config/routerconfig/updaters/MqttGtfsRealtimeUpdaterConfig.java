package org.opentripplanner.standalone.config.routerconfig.updaters;

import static org.opentripplanner.standalone.config.framework.json.EnumMapper.docEnumValueList;
import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_0;
import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_2;
import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_8;
import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_9;

import org.opentripplanner.standalone.config.framework.json.NodeAdapter;
import org.opentripplanner.updater.trip.gtfs.interpolation.BackwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.interpolation.ForwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.updater.mqtt.MqttGtfsRealtimeUpdaterParameters;

public class MqttGtfsRealtimeUpdaterConfig {

  public static MqttGtfsRealtimeUpdaterParameters create(String configRef, NodeAdapter c) {
    return new MqttGtfsRealtimeUpdaterParameters(
      configRef,
      FeedIdsConfig.read(c, V2_0),
      c.of("url").since(V2_0).summary("URL of the MQTT broker.").asString(),
      c.of("topic").since(V2_0).summary("The topic to subscribe to.").asString(),
      c.of("qos").since(V2_0).summary("QOS level.").asInt(0),
      c
        .of("fuzzyTripMatching")
        .since(V2_0)
        .summary("Whether to match trips fuzzily.")
        .asBoolean(false),
      c
        .of("partialTripIdMatching")
        .since(V2_9)
        .summary("Resolve realtime trip ids that are a suffix of the static GTFS trip id.")
        .description(
          "Some agencies (notably MTA New York City Subway) emit a realtime `trip_id` that " +
            "is a suffix of the corresponding static GTFS `trip_id`. When enabled, the realtime " +
            "id is matched against the static id by suffix, scoped to the same route and " +
            "service date, before falling back to exact lookup."
        )
        .asBoolean(false),
      c
        .of("forwardsDelayPropagationType")
        .since(V2_8)
        .summary(ForwardsDelayPropagationType.DEFAULT.typeDescription())
        .description(docEnumValueList(ForwardsDelayPropagationType.values()))
        .asEnum(ForwardsDelayPropagationType.DEFAULT),
      c
        .of("backwardsDelayPropagationType")
        .since(V2_2)
        .summary(BackwardsDelayPropagationType.REQUIRED_NO_DATA.typeDescription())
        .description(docEnumValueList(BackwardsDelayPropagationType.values()))
        .asEnum(BackwardsDelayPropagationType.REQUIRED_NO_DATA)
    );
  }
}
