package org.opentripplanner.updater.trip.gtfs.interpolation;

import org.opentripplanner.core.model.doc.DocumentedEnum;

public enum ForwardsDelayPropagationType implements DocumentedEnum<ForwardsDelayPropagationType> {
  NONE,
  DEFAULT,
  INTERPOLATE_CONTRADICTIONS,
  CLAMP_CONTRADICTIONS;

  @Override
  public String typeDescription() {
    return "How forwards propagation should be handled.";
  }

  @Override
  public String enumValueDescription() {
    return switch (this) {
      case NONE -> """
      Do not propagate delays forwards. Reject real-time updates if not all arrival / departure times
      are specified until the end of the trip.
      Note that this will also reject all updates containing `NO_DATA`, or all updates containing
      `SKIPPED` stops without a time provided. Only use this value when you can guarantee that the
      real-time feed contains all departure and arrival times for all future stops, including
      `SKIPPED` stops.
      """;
      case DEFAULT -> """
      Default value. Propagate delays forwards for stops without arrival / departure times given.
      For `NO_DATA` stops, the scheduled time is used unless a previous delay fouls the scheduled time
      at the stop, in such case the minimum amount of delay is propagated to make the times
      non-decreasing.
      For `SKIPPED` stops without time given, interpolate the estimated time using the ratio between
      scheduled and real times from the previous to the next stop.
      """;
      case INTERPOLATE_CONTRADICTIONS -> """
      Like `DEFAULT`, with one addition: when a run of stops without any realtime information is
      followed by a provided time that contradicts plain forward propagation — the propagated
      times would be later than the next stop's provided time — the run is filled by
      interpolating between the surrounding provided times (the same treatment `DEFAULT` gives
      explicitly `SKIPPED` stops) instead of rejecting the whole update as a negative hop.
      Use this for feeds that silently omit skipped stops rather than marking them `SKIPPED`,
      such as the NYC Subway feeds, where express runs otherwise lose all realtime.
      """;
      case CLAMP_CONTRADICTIONS -> """
      Like `INTERPOLATE_CONTRADICTIONS`, with a final safety net: after interpolation, any
      remaining contradiction between two explicitly provided times — a negative hop or dwell
      that interpolation cannot reach because both ends were given by the feed — is repaired by
      clamping the offending time forward to the previous departure. The trip survives with a
      degenerate zero-length hop instead of losing every prediction it carries. Repairs are
      logged at debug level. Use for feeds that emit occasionally contradictory predictions
      (mixed prediction sources) where discarding the whole trip is worse than a flattened hop.
      """;
    };
  }
}
