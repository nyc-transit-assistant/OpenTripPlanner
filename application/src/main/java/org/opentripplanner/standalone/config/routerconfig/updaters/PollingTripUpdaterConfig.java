package org.opentripplanner.standalone.config.routerconfig.updaters;

import static org.opentripplanner.standalone.config.framework.json.EnumMapper.docEnumValueList;
import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V1_5;
import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_2;
import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_3;
import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_8;
import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_9;

import java.time.Duration;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;
import org.opentripplanner.updater.trip.gtfs.interpolation.BackwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.interpolation.ForwardsDelayPropagationType;
import org.opentripplanner.updater.trip.gtfs.updater.http.PollingTripUpdaterParameters;

public class PollingTripUpdaterConfig {

  public static PollingTripUpdaterParameters create(String configRef, NodeAdapter c) {
    var url = c
      .of("url")
      .since(V1_5)
      .summary("The URL of the GTFS-RT resource.")
      .description(
        "`file:` URLs are also supported if you want to read a file from the local disk."
      )
      .asString();

    var headers = HttpHeadersConfig.headers(c, V2_3);

    return new PollingTripUpdaterParameters(
      configRef,
      c
        .of("frequency")
        .since(V1_5)
        .summary("How often the data should be downloaded.")
        .asDuration(Duration.ofMinutes(1)),
      c
        .of("fuzzyTripMatching")
        .since(V1_5)
        .summary("If the trips should be matched fuzzily.")
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
        .of("trainNumberMatching")
        .since(V2_9)
        .summary("Resolve realtime trips by train number instead of trip id.")
        .description(
          "Some agencies (notably MTA Metro-North) emit realtime trip ids that appear " +
            "nowhere in the static GTFS; the FeedEntity id (trip updates) and vehicle label " +
            "(vehicle positions) carry the train number, which matches the static " +
            "`trip_short_name`. When enabled, the train number and start date are resolved " +
            "against the static schedule and the trip id is rewritten before exact lookup."
        )
        .asBoolean(false),
      c
        .of("trainNumberSynthesisIdPrefix")
        .since(V2_9)
        .summary("Synthesize unresolved ADDED/id-less trains as `<prefix><train>-<date>`.")
        .description(
          "Only with `trainNumberMatching`. When a train number resolves to no static trip " +
            "and the entity is ADDED or has no trip id (NJT rail's unscheduled event " +
            "shuttles), give it this deterministic id so it is built as an added trip and " +
            "keeps one identity across polling cycles. Unset disables synthesis."
        )
        .asString(null),
      c
        .of("trainNumberSynthesisRouteId")
        .since(V2_9)
        .summary("Route id assigned to synthesized trains whose descriptor carries none.")
        .asString(null),
      c
        .of("scopedFullDatasetClear")
        .since(V2_9)
        .summary(
          "Never clear the whole feed on FULL_DATASET updates; clear only this updater's routes."
        )
        .description(
          "Set this on every updater when several realtime updaters share one static feed id " +
            "(e.g. NYCT's per-line GTFS-RT URLs all writing to one subway feed). Each " +
            "FULL_DATASET update then clears only the routes this updater is authoritative " +
            "for — its declared trip replacement periods plus the routes present in the " +
            "batch — instead of wiping data the sibling updaters just applied."
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
        .asEnum(BackwardsDelayPropagationType.REQUIRED_NO_DATA),
      FeedIdsConfig.read(c, V1_5),
      url,
      headers
    );
  }
}
