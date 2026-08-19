package org.opentripplanner.updater.trip.gtfs.interpolation;

import java.util.Objects;
import org.opentripplanner.transit.model.timetable.RealTimeTripTimesBuilder;
import org.opentripplanner.transit.model.timetable.StopRealTimeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This interpolator fills in missing times according to the:
 * <a href="https://gtfs.org/documentation/realtime/feed-entities/trip-updates/#stoptimeupdate"> specification</a>
 * <p>
 * If one or more stops are missing along the trip the delay from the update (or, if only time is
 * provided in the update, a delay computed by comparing the time against the GTFS schedule time) is
 * propagated to all subsequent stops.
 * <p>
 * This means that updating a stop time for a certain stop will change all subsequent stops in the
 * absence of any other information. Note that updates with a schedule relationship of SKIPPED will
 * not stop delay propagation, but updates with schedule relationships of SCHEDULED (also the
 * default value if schedule relationship is not provided) or NO_DATA will.
 */
class DefaultForwardsDelayInterpolator implements ForwardsDelayInterpolator {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultForwardsDelayInterpolator.class);

  /**
   * When true, a run of stops with no realtime information whose flat-propagated times would
   * contradict the next provided time (a negative hop) is re-filled by ratio interpolation
   * between the surrounding provided times — the treatment the spec prescribes for SKIPPED
   * stops. Exists for feeds (NYC Subway) that omit skipped stops instead of marking them.
   */
  private final boolean interpolateContradictions;

  /**
   * When true, run a final monotonicity pass after interpolation: any remaining contradiction
   * between two explicitly provided times (which interpolation cannot repair — it only re-fills
   * runs of stops that had no realtime information) is clamped forward to the previous
   * departure, mirroring the exact check in RealTimeTripTimes#validateNonIncreasingTimes, so
   * the update survives with a degenerate hop instead of being rejected outright.
   */
  private final boolean clampContradictions;

  DefaultForwardsDelayInterpolator() {
    this(false, false);
  }

  DefaultForwardsDelayInterpolator(boolean interpolateContradictions) {
    this(interpolateContradictions, false);
  }

  DefaultForwardsDelayInterpolator(boolean interpolateContradictions, boolean clampContradictions) {
    this.interpolateContradictions = interpolateContradictions;
    this.clampContradictions = clampContradictions;
  }

  @Override
  public boolean interpolateDelay(RealTimeTripTimesBuilder builder) {
    Integer delay = null;
    Integer time = null;
    StopRealTimeState propagatedState = StopRealTimeState.DEFAULT;
    Integer firstCanceledStop = null;
    Integer firstPropagatedStop = null;
    boolean updated = false;
    boolean firstRealUpdateSeen = false;
    for (var i = 0; i < builder.numberOfStops(); ++i) {
      boolean noTimeGiven = builder.containsNoRealTimeTimes(i);
      if (!noTimeGiven) {
        firstRealUpdateSeen = true;
        if (interpolateContradictions && firstPropagatedStop != null && time != null) {
          var providedArrival = builder.getArrivalTime(i) != null
            ? builder.getArrivalTime(i)
            : builder.getDepartureTime(i);
          if (providedArrival != null && providedArrival < time) {
            // The flat-propagated run contradicts this stop's provided time: the vehicle covered
            // the run faster than schedule-plus-delay claims (it likely skipped those stops).
            // Re-fill the run by ratio interpolation between the surrounding provided times so
            // the update survives validation instead of being rejected as a negative hop.
            interpolateRun(builder, firstPropagatedStop, i, providedArrival);
            updated = true;
          }
        }
        firstPropagatedStop = null;
      }
      if (noTimeGiven) {
        if (builder.getStopRealTimeState(i) == StopRealTimeState.DEFAULT) {
          builder.withStopRealTimeState(i, propagatedState);
        }

        if (builder.getStopRealTimeState(i) == StopRealTimeState.CANCELLED) {
          if (firstCanceledStop == null) {
            firstCanceledStop = i;
          }
          firstPropagatedStop = null;
          continue;
        }
        if (builder.getStopRealTimeState(i) == StopRealTimeState.NO_DATA) {
          firstPropagatedStop = null;
        }
      }

      if (builder.getArrivalDelay(i) == null) {
        // only fill in times for NO_DATA stops after the first updated stop
        // otherwise, it is the job of the backward interpolator to fill in the times backward
        // see bug https://github.com/opentripplanner/OpenTripPlanner/issues/7097 for details
        if (builder.getStopRealTimeState(i) == StopRealTimeState.NO_DATA && firstRealUpdateSeen) {
          // for NO_DATA stops, try to use the scheduled time. However, if the schedule time is
          // earlier than the delayed departure of the previous stop, we cannot set an earlier time
          // than that.
          if (time != null && builder.getScheduledArrivalTime(i) < time) {
            builder.withArrivalTime(i, time);
          } else {
            builder.withArrivalDelay(i, 0);
          }
        } else if (delay != null) {
          // the arrival delay cannot exceed the given departure time
          var departureTime = builder.getDepartureTime(i);
          if (departureTime != null && builder.getScheduledArrivalTime(i) + delay > departureTime) {
            builder.withArrivalTime(i, departureTime);
          } else {
            builder.withArrivalDelay(i, delay);
          }
          if (
            noTimeGiven &&
            firstPropagatedStop == null &&
            builder.getStopRealTimeState(i) == StopRealTimeState.DEFAULT
          ) {
            firstPropagatedStop = i;
          }
        }
        updated = true;
      }
      time = builder.getArrivalTime(i);
      delay = builder.getArrivalDelay(i);

      if (builder.getDepartureDelay(i) == null) {
        if (builder.getStopRealTimeState(i) == StopRealTimeState.NO_DATA && firstRealUpdateSeen) {
          // for NO_DATA stops, try to use the scheduled time. However, if the schedule time is
          // earlier than the delayed arrival of this stop, we cannot set an earlier time
          // than that.
          if (time != null && builder.getScheduledDepartureTime(i) < time) {
            builder.withDepartureTime(i, time);
          } else {
            builder.withDepartureDelay(i, 0);
          }
        } else if (delay != null) {
          builder.withDepartureDelay(i, delay);
        }
        updated = true;
      }
      time = builder.getDepartureTime(i);
      delay = builder.getDepartureDelay(i);

      // interpolate time for canceled stops before this stop
      if (firstCanceledStop != null && firstCanceledStop > 0) {
        Integer prevDeparture = builder.getDepartureTime(firstCanceledStop - 1);
        if (prevDeparture != null) {
          int arrival = Objects.requireNonNull(builder.getArrivalTime(i));
          int prevScheduledDeparture = builder.getScheduledDepartureTime(firstCanceledStop - 1);
          int scheduledArrival = builder.getScheduledArrivalTime(i);
          // Math.max() because it is allowed for the previous departure and arrival to be
          // the same time
          int scheduledTravelTime = Math.max(scheduledArrival - prevScheduledDeparture, 1);
          int realTimeTravelTime = arrival - prevDeparture;
          double travelTimeRatio = (double) realTimeTravelTime / scheduledTravelTime;

          // Fill out interpolated time for cancelled stops, using the calculated ratio.
          for (int cancelledIndex = firstCanceledStop; cancelledIndex < i; cancelledIndex++) {
            final int scheduledArrivalCancelled = builder.getScheduledArrivalTime(cancelledIndex);
            final int scheduledDepartureCancelled = builder.getScheduledDepartureTime(
              cancelledIndex
            );

            // Interpolate
            int scheduledArrivalDiff = scheduledArrivalCancelled - prevScheduledDeparture;
            double interpolatedArrival = prevDeparture + travelTimeRatio * scheduledArrivalDiff;
            int scheduledDepartureDiff = scheduledDepartureCancelled - prevScheduledDeparture;
            double interpolatedDeparture = prevDeparture + travelTimeRatio * scheduledDepartureDiff;

            // Set Interpolated Times
            builder.withArrivalTime(cancelledIndex, (int) interpolatedArrival);
            builder.withDepartureTime(cancelledIndex, (int) interpolatedDeparture);
            updated = true;
          }
        }
      }
      firstCanceledStop = null;

      var state = builder.getStopRealTimeState(i);
      // NO_DATA should be propagated per the spec, SKIPPED should not
      // GTFS does not support INACCURATE_PREDICTIONS, but I think it should be propagated as well
      if (state == StopRealTimeState.NO_DATA || state == StopRealTimeState.INACCURATE_PREDICTIONS) {
        propagatedState = state;
      } else {
        propagatedState = StopRealTimeState.DEFAULT;
      }
    }

    if (delay != null && firstCanceledStop != null) {
      // there are skipped stops without estimated times at the end of the journey, propagate delay
      for (int i = firstCanceledStop; i < builder.numberOfStops(); ++i) {
        builder.withArrivalDelay(i, delay);
        builder.withDepartureDelay(i, delay);
        updated = true;
      }
    }

    // If we reach the end of the trip without a delay value, it means that no estimated times are
    // provided at all (the update may still contain NO_DATA or SKIPPED stops). In such case, copy
    // the scheduled timetable verbatim.
    //
    // If there is a value provided, the backward interpolator should take care of filling in the
    // stops before the first provided value.
    if (delay == null) {
      return builder.copyMissingTimesFromScheduledTimetable();
    }

    if (clampContradictions) {
      updated |= clampNonMonotonicTimes(builder);
    }

    return updated;
  }

  /**
   * Enforce non-decreasing times across the whole trip, walking the same shape as
   * RealTimeTripTimes#validateNonIncreasingTimes: any arrival earlier than the previous
   * departure, or departure earlier than its own arrival, is pushed forward to equality.
   * Only fires on contradictions between provided times — interpolation has already repaired
   * everything repairable by the time this runs.
   */
  private boolean clampNonMonotonicTimes(RealTimeTripTimesBuilder builder) {
    int repairs = 0;
    Integer prevDeparture = null;
    for (int i = 0; i < builder.numberOfStops(); i++) {
      Integer arrival = builder.getArrivalTime(i);
      if (arrival != null && prevDeparture != null && arrival < prevDeparture) {
        builder.withArrivalTime(i, prevDeparture);
        arrival = prevDeparture;
        repairs++;
      }
      Integer departure = builder.getDepartureTime(i);
      if (departure != null && arrival != null && departure < arrival) {
        builder.withDepartureTime(i, arrival);
        departure = arrival;
        repairs++;
      }
      if (departure != null) {
        prevDeparture = departure;
      } else if (arrival != null) {
        prevDeparture = arrival;
      }
    }
    if (repairs > 0) {
      LOG.debug("Clamped {} non-monotonic stop time(s) on trip {}", repairs, builder.getTrip());
    }
    if (repairs > 0) {
      // Sizes CLAMP_CONTRADICTIONS activity (issue #7). No feed context down here; the mode
      // is only enabled on the subway updaters, so the untagged total is effectively subway.
      io.micrometer.core.instrument.Metrics.counter("trip.updates.diag.clamp_repairs").increment(
        repairs
      );
    }
    return repairs > 0;
  }

  /**
   * Re-fill stops [runStart, nextProvided) by interpolating between the departure at
   * runStart - 1 and the provided arrival at nextProvided, proportionally to the scheduled
   * times — the same math used for explicitly SKIPPED runs. Times are clamped to the anchor
   * window so the result is always non-decreasing, whatever the data claims.
   */
  private void interpolateRun(
    RealTimeTripTimesBuilder builder,
    int runStart,
    int nextProvided,
    int providedArrival
  ) {
    Integer prevDeparture = builder.getDepartureTime(runStart - 1);
    if (prevDeparture == null) {
      return;
    }
    int prevScheduledDeparture = builder.getScheduledDepartureTime(runStart - 1);
    int scheduledArrival = builder.getScheduledArrivalTime(nextProvided);
    int scheduledTravelTime = Math.max(scheduledArrival - prevScheduledDeparture, 1);
    int realTimeTravelTime = Math.max(providedArrival - prevDeparture, 0);
    double travelTimeRatio = (double) realTimeTravelTime / scheduledTravelTime;

    for (int pos = runStart; pos < nextProvided; pos++) {
      int scheduledArrivalDiff = builder.getScheduledArrivalTime(pos) - prevScheduledDeparture;
      int scheduledDepartureDiff = builder.getScheduledDepartureTime(pos) - prevScheduledDeparture;
      int arrival = prevDeparture + (int) (travelTimeRatio * scheduledArrivalDiff);
      int departure = prevDeparture + (int) (travelTimeRatio * scheduledDepartureDiff);
      builder.withArrivalTime(pos, Math.min(Math.max(arrival, prevDeparture), providedArrival));
      builder.withDepartureTime(pos, Math.min(Math.max(departure, prevDeparture), providedArrival));
    }
  }
}
