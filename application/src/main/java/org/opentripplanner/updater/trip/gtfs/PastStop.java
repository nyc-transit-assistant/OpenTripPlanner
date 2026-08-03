package org.opentripplanner.updater.trip.gtfs;

import org.opentripplanner.transit.model.site.StopLocation;

/**
 * A stop of a realtime-added (NEW/ADDED) trip together with the last realtime times observed for
 * it, in epoch seconds. Harvested from the timetable snapshot buffer before a FULL_DATASET clear
 * so that stops the producer has dropped from the feed (NYCT removes a stop's update once the
 * train departs it) can be carried forward into the trip's next rebuild instead of eroding off
 * the front of the pattern.
 */
record PastStop(StopLocation stop, long arrivalEpoch, long departureEpoch) {}
