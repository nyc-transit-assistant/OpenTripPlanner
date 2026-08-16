<!--
  NOTE! Part of this document is generated. Make sure you edit the template, not the generated doc.

   - Template directory is:  /doc/templates
   - Generated directory is: /doc/user
-->

GTFS feeds contain _schedule_ data that is published by an agency or operator in advance. The feed
does not account for unexpected service changes or traffic disruptions that occur from day to day.
Thus, this kind of data is also referred to as 'static' data or 'scheduled' arrival and departure
times.

[GTFS-Realtime](https://gtfs.org/realtime/) complements GTFS with additional kinds of feeds. In
contrast to the base GTFS schedule feed, they provide _real-time_ updates (_'dynamic'_ data) and are
updated from minute to minute.

## Alerts

Alerts are text messages attached to GTFS objects, informing riders of disruptions and changes. The
information is downloaded in a single HTTP request and polled regularly.

<!-- real-time-alerts BEGIN -->
<!-- NOTE! This section is auto-generated. Do not change, change doc in code instead. -->

| Config Parameter          |       Type      | Summary                                                                       |  Req./Opt. | Default Value | Since |
|---------------------------|:---------------:|-------------------------------------------------------------------------------|:----------:|---------------|:-----:|
| type = "real-time-alerts" |      `enum`     | The type of the updater.                                                      | *Required* |               |  1.5  |
| earlyStartSec             |    `integer`    | How long before the posted start of an event it should be displayed to users  | *Optional* | `0`           |  1.5  |
| [feedId](#u_0_feedId)     |     `string`    | Deprecated: prefer `feedIds`. Single static GTFS feed id to apply updates to. | *Optional* |               |  1.5  |
| frequency                 |    `duration`   | How often the URL should be fetched.                                          | *Optional* | `"PT1M"`      |  1.5  |
| fuzzyTripMatching         |    `boolean`    | Whether to match trips fuzzily.                                               | *Optional* | `false`       |  1.5  |
| url                       |     `string`    | URL to fetch the GTFS-RT feed from.                                           | *Required* |               |  1.5  |
| [feedIds](#u_0_feedIds)   |    `string[]`   | The static GTFS feed ids the real-time data should be applied to.             | *Optional* |               |  2.9  |
| [headers](#u_0_headers)   | `map of string` | HTTP headers to add to the request. Any header key, value can be inserted.    | *Optional* |               |  2.3  |


##### Parameter details

<h4 id="u_0_feedId">feedId</h4>

**Since version:** `1.5` ∙ **Type:** `string` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[0] 

Deprecated: prefer `feedIds`. Single static GTFS feed id to apply updates to.

Equivalent to specifying a one-element `feedIds` list. Retained for backwards compatibility — new configurations should use `feedIds`.

<h4 id="u_0_feedIds">feedIds</h4>

**Since version:** `2.9` ∙ **Type:** `string[]` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[0] 

The static GTFS feed ids the real-time data should be applied to.

A single GTFS-RT feed may be applied to multiple static GTFS feeds; each real-time entity is matched to the static feed that contains its trip/route/stop id.

<h4 id="u_0_headers">headers</h4>

**Since version:** `2.3` ∙ **Type:** `map of string` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[0] 

HTTP headers to add to the request. Any header key, value can be inserted.



##### Example configuration

```JSON
// router-config.json
{
  "updaters" : [
    {
      "type" : "real-time-alerts",
      "frequency" : "30s",
      "url" : "http://developer.trimet.org/ws/V1/FeedSpecAlerts/appID/0123456789ABCDEF",
      "feedIds" : [
        "TriMet"
      ],
      "headers" : {
        "Some-Header" : "A-Value"
      }
    }
  ]
}
```

<!-- real-time-alerts END -->

## TripUpdates via HTTP(S)

TripUpdates report on the status of scheduled trips as they happen, providing observed and predicted
arrival and departure times for the remainder of the trip. The information is downloaded in a single
HTTP request and polled regularly.

<!-- stop-time-updater BEGIN -->
<!-- NOTE! This section is auto-generated. Do not change, change doc in code instead. -->

| Config Parameter                                                      |       Type      | Summary                                                                               |  Req./Opt. | Default Value        | Since |
|-----------------------------------------------------------------------|:---------------:|---------------------------------------------------------------------------------------|:----------:|----------------------|:-----:|
| type = "stop-time-updater"                                            |      `enum`     | The type of the updater.                                                              | *Required* |                      |  1.5  |
| [backwardsDelayPropagationType](#u__5__backwardsDelayPropagationType) |      `enum`     | How backwards propagation should be handled.                                          | *Optional* | `"required-no-data"` |  2.2  |
| [feedId](#u__5__feedId)                                               |     `string`    | Deprecated: prefer `feedIds`. Single static GTFS feed id to apply updates to.         | *Optional* |                      |  1.5  |
| [forwardsDelayPropagationType](#u__5__forwardsDelayPropagationType)   |      `enum`     | How forwards propagation should be handled.                                           | *Optional* | `"default"`          |  2.8  |
| frequency                                                             |    `duration`   | How often the data should be downloaded.                                              | *Optional* | `"PT1M"`             |  1.5  |
| fuzzyTripMatching                                                     |    `boolean`    | If the trips should be matched fuzzily.                                               | *Optional* | `false`              |  1.5  |
| [partialTripIdMatching](#u__5__partialTripIdMatching)                 |    `boolean`    | Resolve realtime trip ids that are a suffix of the static GTFS trip id.               | *Optional* | `false`              |  2.9  |
| [scopedFullDatasetClear](#u__5__scopedFullDatasetClear)               |    `boolean`    | Never clear the whole feed on FULL_DATASET updates; clear only this updater's routes. | *Optional* | `false`              |  2.9  |
| [trainNumberMatching](#u__5__trainNumberMatching)                     |    `boolean`    | Resolve realtime trips by train number instead of trip id.                            | *Optional* | `false`              |  2.9  |
| [trainNumberSynthesisIdPrefix](#u__5__trainNumberSynthesisIdPrefix)   |     `string`    | Synthesize unresolved ADDED/id-less trains as `<prefix><train>-<date>`.               | *Optional* |                      |  2.9  |
| trainNumberSynthesisRouteId                                           |     `string`    | Route id assigned to synthesized trains whose descriptor carries none.                | *Optional* |                      |  2.9  |
| [url](#u__5__url)                                                     |     `string`    | The URL of the GTFS-RT resource.                                                      | *Required* |                      |  1.5  |
| [feedIds](#u__5__feedIds)                                             |    `string[]`   | The static GTFS feed ids the real-time data should be applied to.                     | *Optional* |                      |  2.9  |
| [headers](#u__5__headers)                                             | `map of string` | HTTP headers to add to the request. Any header key, value can be inserted.            | *Optional* |                      |  2.3  |


##### Parameter details

<h4 id="u__5__backwardsDelayPropagationType">backwardsDelayPropagationType</h4>

**Since version:** `2.2` ∙ **Type:** `enum` ∙ **Cardinality:** `Optional` ∙ **Default value:** `"required-no-data"`   
**Path:** /updaters/[5]   
**Enum values:** `none` | `required-no-data` | `required` | `always`

How backwards propagation should be handled.

 - `none` Do not propagate delays backwards. Reject real-time updates if the times are not specified
   from the beginning of the trip.
 - `required-no-data` Default value. Only propagates delays backwards when it is required to ensure that the times
       are increasing, and it sets the NO_DATA flag on the stops so these automatically updated times
       are not exposed through APIs.
 - `required` Only propagates delays backwards when it is required to ensure that the times are increasing.
       The updated times are exposed through APIs.
 - `always` Propagates delays backwards on stops with no estimates regardless if it's required or not.
       The updated times are exposed through APIs.


<h4 id="u__5__feedId">feedId</h4>

**Since version:** `1.5` ∙ **Type:** `string` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[5] 

Deprecated: prefer `feedIds`. Single static GTFS feed id to apply updates to.

Equivalent to specifying a one-element `feedIds` list. Retained for backwards compatibility — new configurations should use `feedIds`.

<h4 id="u__5__forwardsDelayPropagationType">forwardsDelayPropagationType</h4>

**Since version:** `2.8` ∙ **Type:** `enum` ∙ **Cardinality:** `Optional` ∙ **Default value:** `"default"`   
**Path:** /updaters/[5]   
**Enum values:** `none` | `default` | `interpolate-contradictions` | `clamp-contradictions`

How forwards propagation should be handled.

 - `none` Do not propagate delays forwards. Reject real-time updates if not all arrival / departure times
   are specified until the end of the trip.
   Note that this will also reject all updates containing `NO_DATA`, or all updates containing
   `SKIPPED` stops without a time provided. Only use this value when you can guarantee that the
   real-time feed contains all departure and arrival times for all future stops, including
   `SKIPPED` stops.
 - `default` Default value. Propagate delays forwards for stops without arrival / departure times given.
   For `NO_DATA` stops, the scheduled time is used unless a previous delay fouls the scheduled time
   at the stop, in such case the minimum amount of delay is propagated to make the times
   non-decreasing.
   For `SKIPPED` stops without time given, interpolate the estimated time using the ratio between
   scheduled and real times from the previous to the next stop.
 - `interpolate-contradictions` Like `DEFAULT`, with one addition: when a run of stops without any realtime information is
   followed by a provided time that contradicts plain forward propagation — the propagated
   times would be later than the next stop's provided time — the run is filled by
   interpolating between the surrounding provided times (the same treatment `DEFAULT` gives
   explicitly `SKIPPED` stops) instead of rejecting the whole update as a negative hop.
   Use this for feeds that silently omit skipped stops rather than marking them `SKIPPED`,
   such as the NYC Subway feeds, where express runs otherwise lose all realtime.
 - `clamp-contradictions` Like `INTERPOLATE_CONTRADICTIONS`, with a final safety net: after interpolation, any
   remaining contradiction between two explicitly provided times — a negative hop or dwell
   that interpolation cannot reach because both ends were given by the feed — is repaired by
   clamping the offending time forward to the previous departure. The trip survives with a
   degenerate zero-length hop instead of losing every prediction it carries. Repairs are
   logged at debug level. Use for feeds that emit occasionally contradictory predictions
   (mixed prediction sources) where discarding the whole trip is worse than a flattened hop.


<h4 id="u__5__partialTripIdMatching">partialTripIdMatching</h4>

**Since version:** `2.9` ∙ **Type:** `boolean` ∙ **Cardinality:** `Optional` ∙ **Default value:** `false`   
**Path:** /updaters/[5] 

Resolve realtime trip ids that are a suffix of the static GTFS trip id.

Some agencies (notably MTA New York City Subway) emit a realtime `trip_id` that is a suffix of the corresponding static GTFS `trip_id`. When enabled, the realtime id is matched against the static id by suffix, scoped to the same route and service date, before falling back to exact lookup.

<h4 id="u__5__scopedFullDatasetClear">scopedFullDatasetClear</h4>

**Since version:** `2.9` ∙ **Type:** `boolean` ∙ **Cardinality:** `Optional` ∙ **Default value:** `false`   
**Path:** /updaters/[5] 

Never clear the whole feed on FULL_DATASET updates; clear only this updater's routes.

Set this on every updater when several realtime updaters share one static feed id (e.g. NYCT's per-line GTFS-RT URLs all writing to one subway feed). Each FULL_DATASET update then clears only the routes this updater is authoritative for — its declared trip replacement periods plus the routes present in the batch — instead of wiping data the sibling updaters just applied.

<h4 id="u__5__trainNumberMatching">trainNumberMatching</h4>

**Since version:** `2.9` ∙ **Type:** `boolean` ∙ **Cardinality:** `Optional` ∙ **Default value:** `false`   
**Path:** /updaters/[5] 

Resolve realtime trips by train number instead of trip id.

Some agencies (notably MTA Metro-North) emit realtime trip ids that appear nowhere in the static GTFS; the FeedEntity id (trip updates) and vehicle label (vehicle positions) carry the train number, which matches the static `trip_short_name`. When enabled, the train number and start date are resolved against the static schedule and the trip id is rewritten before exact lookup.

<h4 id="u__5__trainNumberSynthesisIdPrefix">trainNumberSynthesisIdPrefix</h4>

**Since version:** `2.9` ∙ **Type:** `string` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[5] 

Synthesize unresolved ADDED/id-less trains as `<prefix><train>-<date>`.

Only with `trainNumberMatching`. When a train number resolves to no static trip and the entity is ADDED or has no trip id (NJT rail's unscheduled event shuttles), give it this deterministic id so it is built as an added trip and keeps one identity across polling cycles. Unset disables synthesis.

<h4 id="u__5__url">url</h4>

**Since version:** `1.5` ∙ **Type:** `string` ∙ **Cardinality:** `Required`   
**Path:** /updaters/[5] 

The URL of the GTFS-RT resource.

`file:` URLs are also supported if you want to read a file from the local disk.

<h4 id="u__5__feedIds">feedIds</h4>

**Since version:** `2.9` ∙ **Type:** `string[]` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[5] 

The static GTFS feed ids the real-time data should be applied to.

A single GTFS-RT feed may be applied to multiple static GTFS feeds; each real-time entity is matched to the static feed that contains its trip/route/stop id.

<h4 id="u__5__headers">headers</h4>

**Since version:** `2.3` ∙ **Type:** `map of string` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[5] 

HTTP headers to add to the request. Any header key, value can be inserted.



##### Example configuration

```JSON
// router-config.json
{
  "updaters" : [
    {
      "type" : "stop-time-updater",
      "frequency" : "1m",
      "backwardsDelayPropagationType" : "REQUIRED_NO_DATA",
      "url" : "http://developer.trimet.org/ws/V1/TripUpdate/appID/0123456789ABCDEF",
      "feedIds" : [
        "TriMet"
      ],
      "headers" : {
        "Authorization" : "A-Token"
      }
    }
  ]
}
```

<!-- stop-time-updater END -->

## Streaming TripUpdates via MQTT

This updater connects to an MQTT broker and processes TripUpdates in a streaming fashion. This means
that they will be applied individually in near-real-time rather than in batches at a certain
interval.

This system powers the real-time updates in Helsinki and more information can be found
[on Github](https://github.com/HSLdevcom/transitdata).

<!-- mqtt-gtfs-rt-updater BEGIN -->
<!-- NOTE! This section is auto-generated. Do not change, change doc in code instead. -->

| Config Parameter                                                      |    Type    | Summary                                                                       |  Req./Opt. | Default Value        | Since |
|-----------------------------------------------------------------------|:----------:|-------------------------------------------------------------------------------|:----------:|----------------------|:-----:|
| type = "mqtt-gtfs-rt-updater"                                         |   `enum`   | The type of the updater.                                                      | *Required* |                      |  1.5  |
| [backwardsDelayPropagationType](#u__6__backwardsDelayPropagationType) |   `enum`   | How backwards propagation should be handled.                                  | *Optional* | `"required-no-data"` |  2.2  |
| [feedId](#u__6__feedId)                                               |  `string`  | Deprecated: prefer `feedIds`. Single static GTFS feed id to apply updates to. | *Optional* |                      |  2.0  |
| [forwardsDelayPropagationType](#u__6__forwardsDelayPropagationType)   |   `enum`   | How forwards propagation should be handled.                                   | *Optional* | `"default"`          |  2.8  |
| fuzzyTripMatching                                                     |  `boolean` | Whether to match trips fuzzily.                                               | *Optional* | `false`              |  2.0  |
| [partialTripIdMatching](#u__6__partialTripIdMatching)                 |  `boolean` | Resolve realtime trip ids that are a suffix of the static GTFS trip id.       | *Optional* | `false`              |  2.9  |
| qos                                                                   |  `integer` | QOS level.                                                                    | *Optional* | `0`                  |  2.0  |
| topic                                                                 |  `string`  | The topic to subscribe to.                                                    | *Required* |                      |  2.0  |
| url                                                                   |  `string`  | URL of the MQTT broker.                                                       | *Required* |                      |  2.0  |
| [feedIds](#u__6__feedIds)                                             | `string[]` | The static GTFS feed ids the real-time data should be applied to.             | *Optional* |                      |  2.9  |


##### Parameter details

<h4 id="u__6__backwardsDelayPropagationType">backwardsDelayPropagationType</h4>

**Since version:** `2.2` ∙ **Type:** `enum` ∙ **Cardinality:** `Optional` ∙ **Default value:** `"required-no-data"`   
**Path:** /updaters/[6]   
**Enum values:** `none` | `required-no-data` | `required` | `always`

How backwards propagation should be handled.

 - `none` Do not propagate delays backwards. Reject real-time updates if the times are not specified
   from the beginning of the trip.
 - `required-no-data` Default value. Only propagates delays backwards when it is required to ensure that the times
       are increasing, and it sets the NO_DATA flag on the stops so these automatically updated times
       are not exposed through APIs.
 - `required` Only propagates delays backwards when it is required to ensure that the times are increasing.
       The updated times are exposed through APIs.
 - `always` Propagates delays backwards on stops with no estimates regardless if it's required or not.
       The updated times are exposed through APIs.


<h4 id="u__6__feedId">feedId</h4>

**Since version:** `2.0` ∙ **Type:** `string` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[6] 

Deprecated: prefer `feedIds`. Single static GTFS feed id to apply updates to.

Equivalent to specifying a one-element `feedIds` list. Retained for backwards compatibility — new configurations should use `feedIds`.

<h4 id="u__6__forwardsDelayPropagationType">forwardsDelayPropagationType</h4>

**Since version:** `2.8` ∙ **Type:** `enum` ∙ **Cardinality:** `Optional` ∙ **Default value:** `"default"`   
**Path:** /updaters/[6]   
**Enum values:** `none` | `default` | `interpolate-contradictions` | `clamp-contradictions`

How forwards propagation should be handled.

 - `none` Do not propagate delays forwards. Reject real-time updates if not all arrival / departure times
   are specified until the end of the trip.
   Note that this will also reject all updates containing `NO_DATA`, or all updates containing
   `SKIPPED` stops without a time provided. Only use this value when you can guarantee that the
   real-time feed contains all departure and arrival times for all future stops, including
   `SKIPPED` stops.
 - `default` Default value. Propagate delays forwards for stops without arrival / departure times given.
   For `NO_DATA` stops, the scheduled time is used unless a previous delay fouls the scheduled time
   at the stop, in such case the minimum amount of delay is propagated to make the times
   non-decreasing.
   For `SKIPPED` stops without time given, interpolate the estimated time using the ratio between
   scheduled and real times from the previous to the next stop.
 - `interpolate-contradictions` Like `DEFAULT`, with one addition: when a run of stops without any realtime information is
   followed by a provided time that contradicts plain forward propagation — the propagated
   times would be later than the next stop's provided time — the run is filled by
   interpolating between the surrounding provided times (the same treatment `DEFAULT` gives
   explicitly `SKIPPED` stops) instead of rejecting the whole update as a negative hop.
   Use this for feeds that silently omit skipped stops rather than marking them `SKIPPED`,
   such as the NYC Subway feeds, where express runs otherwise lose all realtime.
 - `clamp-contradictions` Like `INTERPOLATE_CONTRADICTIONS`, with a final safety net: after interpolation, any
   remaining contradiction between two explicitly provided times — a negative hop or dwell
   that interpolation cannot reach because both ends were given by the feed — is repaired by
   clamping the offending time forward to the previous departure. The trip survives with a
   degenerate zero-length hop instead of losing every prediction it carries. Repairs are
   logged at debug level. Use for feeds that emit occasionally contradictory predictions
   (mixed prediction sources) where discarding the whole trip is worse than a flattened hop.


<h4 id="u__6__partialTripIdMatching">partialTripIdMatching</h4>

**Since version:** `2.9` ∙ **Type:** `boolean` ∙ **Cardinality:** `Optional` ∙ **Default value:** `false`   
**Path:** /updaters/[6] 

Resolve realtime trip ids that are a suffix of the static GTFS trip id.

Some agencies (notably MTA New York City Subway) emit a realtime `trip_id` that is a suffix of the corresponding static GTFS `trip_id`. When enabled, the realtime id is matched against the static id by suffix, scoped to the same route and service date, before falling back to exact lookup.

<h4 id="u__6__feedIds">feedIds</h4>

**Since version:** `2.9` ∙ **Type:** `string[]` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[6] 

The static GTFS feed ids the real-time data should be applied to.

A single GTFS-RT feed may be applied to multiple static GTFS feeds; each real-time entity is matched to the static feed that contains its trip/route/stop id.



##### Example configuration

```JSON
// router-config.json
{
  "updaters" : [
    {
      "type" : "mqtt-gtfs-rt-updater",
      "url" : "tcp://pred.rt.hsl.fi",
      "topic" : "gtfsrt/v2/fi/hsl/tu",
      "feedIds" : [
        "HSL"
      ],
      "fuzzyTripMatching" : true
    }
  ]
}
```

<!-- mqtt-gtfs-rt-updater END -->

## Vehicle Positions

VehiclePositions give the location of some or all vehicles currently in service, in terms of
geographic coordinates or position relative to their scheduled stops. The information is downloaded
in a single HTTP request and polled regularly.

<!-- vehicle-positions BEGIN -->
<!-- NOTE! This section is auto-generated. Do not change, change doc in code instead. -->

| Config Parameter             |       Type      | Summary                                                                       |  Req./Opt. | Default Value | Since |
|------------------------------|:---------------:|-------------------------------------------------------------------------------|:----------:|---------------|:-----:|
| type = "vehicle-positions"   |      `enum`     | The type of the updater.                                                      | *Required* |               |  1.5  |
| [feedId](#u__7__feedId)      |     `string`    | Deprecated: prefer `feedIds`. Single static GTFS feed id to apply updates to. | *Optional* |               |  2.2  |
| frequency                    |    `duration`   | How often the positions should be updated.                                    | *Optional* | `"PT1M"`      |  2.2  |
| fuzzyTripMatching            |    `boolean`    | Whether to match trips fuzzily.                                               | *Optional* | `false`       |  2.5  |
| trainNumberMatching          |    `boolean`    | Resolve realtime trips by train number (vehicle label) instead of trip id.    | *Optional* | `false`       |  2.9  |
| trainNumberSynthesisIdPrefix |     `string`    | Synthetic id prefix for unresolved trains — must match the trip updater's.    | *Optional* |               |  2.9  |
| trainNumberSynthesisRouteId  |     `string`    | Route id for synthesized trains — must match the trip updater's.              | *Optional* |               |  2.9  |
| url                          |      `uri`      | The URL of GTFS-RT protobuf HTTP resource to download the positions from.     | *Required* |               |  2.2  |
| [features](#u__7__features)  |    `enum set`   | Which features of GTFS RT vehicle positions should be loaded into OTP.        | *Optional* |               |  2.5  |
| [feedIds](#u__7__feedIds)    |    `string[]`   | The static GTFS feed ids the real-time data should be applied to.             | *Optional* |               |  2.9  |
| [headers](#u__7__headers)    | `map of string` | HTTP headers to add to the request. Any header key, value can be inserted.    | *Optional* |               |  2.3  |


##### Parameter details

<h4 id="u__7__feedId">feedId</h4>

**Since version:** `2.2` ∙ **Type:** `string` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[7] 

Deprecated: prefer `feedIds`. Single static GTFS feed id to apply updates to.

Equivalent to specifying a one-element `feedIds` list. Retained for backwards compatibility — new configurations should use `feedIds`.

<h4 id="u__7__features">features</h4>

**Since version:** `2.5` ∙ **Type:** `enum set` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[7]   
**Enum values:** `position` | `stop-position` | `occupancy`

Which features of GTFS RT vehicle positions should be loaded into OTP.

<h4 id="u__7__feedIds">feedIds</h4>

**Since version:** `2.9` ∙ **Type:** `string[]` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[7] 

The static GTFS feed ids the real-time data should be applied to.

A single GTFS-RT feed may be applied to multiple static GTFS feeds; each real-time entity is matched to the static feed that contains its trip/route/stop id.

<h4 id="u__7__headers">headers</h4>

**Since version:** `2.3` ∙ **Type:** `map of string` ∙ **Cardinality:** `Optional`   
**Path:** /updaters/[7] 

HTTP headers to add to the request. Any header key, value can be inserted.



##### Example configuration

```JSON
// router-config.json
{
  "updaters" : [
    {
      "type" : "vehicle-positions",
      "url" : "https://s3.amazonaws.com/kcm-alerts-realtime-prod/vehiclepositions.pb",
      "feedIds" : [
        "1"
      ],
      "frequency" : "1m",
      "headers" : {
        "Header-Name" : "Header-Value"
      },
      "fuzzyTripMatching" : false,
      "features" : [
        "position"
      ]
    }
  ]
}
```

<!-- vehicle-positions END -->
