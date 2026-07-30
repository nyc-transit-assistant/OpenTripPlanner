package org.opentripplanner.gtfs;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.core.framework.deduplicator.DeduplicatorService;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.ext.flex.trip.FlexTrip;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.issues.SynthesizedHopGeometry;
import org.opentripplanner.graph_builder.issues.TripDegenerate;
import org.opentripplanner.graph_builder.issues.TripUndefinedService;
import org.opentripplanner.graph_builder.module.geometry.GeometryProcessor;
import org.opentripplanner.model.Frequency;
import org.opentripplanner.model.StopTime;
import org.opentripplanner.model.impl.TransitDataImportBuilder;
import org.opentripplanner.transit.geometry.HopGeometryIndex;
import org.opentripplanner.transit.model.framework.DataValidationException;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.network.TripPatternBuilder;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.Direction;
import org.opentripplanner.transit.model.timetable.FrequencyEntry;
import org.opentripplanner.transit.model.timetable.ScheduledTripTimes;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripTimesFactory;
import org.opentripplanner.utils.logging.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for generating trip patterns when loading GTFS data.
 */
public class GenerateTripPatternsOperation {

  private static final Logger LOG = LoggerFactory.getLogger(GenerateTripPatternsOperation.class);

  private final Map<String, Integer> tripPatternIdCounters = new HashMap<>();

  private final TransitDataImportBuilder transitServiceBuilder;
  private final DataImportIssueStore issueStore;
  private final DeduplicatorService deduplicator;
  private final Set<FeedScopedId> calendarServiceIds;
  private final GeometryProcessor geometryProcessor;

  // TODO the linked hashset configuration ensures that TripPatterns are created in the same order
  //  as Trips are imported, as a workaround for issue #6067
  private final Multimap<StopPattern, TripPatternBuilder> tripPatternBuilders =
    MultimapBuilder.linkedHashKeys().linkedHashSetValues().build();
  private final ListMultimap<Trip, Frequency> frequenciesForTrip = ArrayListMultimap.create();

  /**
   * Hop geometries of every pattern that has a shape, kept so that patterns without one can
   * borrow from them once all patterns are known. Absence of a key means "no shape".
   */
  private final Map<TripPatternBuilder, List<LineString>> hopGeometriesByPattern =
    new IdentityHashMap<>();

  private int freqCount = 0;
  private int scheduledCount = 0;

  public GenerateTripPatternsOperation(
    TransitDataImportBuilder builder,
    DataImportIssueStore issueStore,
    DeduplicatorService deduplicator,
    Set<FeedScopedId> calendarServiceIds,
    GeometryProcessor geometryProcessor
  ) {
    this.transitServiceBuilder = builder;
    this.issueStore = issueStore;
    this.deduplicator = deduplicator;
    this.calendarServiceIds = calendarServiceIds;
    this.geometryProcessor = geometryProcessor;
  }

  public void run() {
    collectFrequencyByTrip();

    final Collection<Trip> trips = transitServiceBuilder.getTripsById().values();
    var progressLogger = ProgressTracker.track("build trip patterns", 50_000, trips.size());
    LOG.info(progressLogger.startMessage());

    /* Loop over all trips, handling each one as a frequency-based or scheduled trip. */
    for (Trip trip : trips) {
      try {
        buildTripPatternForTrip(trip);
        //noinspection Convert2MethodRef
        progressLogger.step(m -> LOG.info(m));
      } catch (DataValidationException e) {
        issueStore.add(e.error());
      }
    }

    fillInMissingHopGeometries();

    tripPatternBuilders
      .values()
      .stream()
      .map(TripPatternBuilder::build)
      .forEach(tripPattern ->
        transitServiceBuilder.getTripPatterns().put(tripPattern.getStopPattern(), tripPattern)
      );

    LOG.info(progressLogger.completeMessage());
    LOG.info(
      "Added {} frequency-based and {} single-trip timetable entries.",
      freqCount,
      scheduledCount
    );
  }

  public boolean hasFrequencyBasedTrips() {
    return freqCount > 0;
  }

  public boolean hasScheduledTrips() {
    return scheduledCount > 0;
  }

  /**
   * First, record which trips are used by one or more frequency entries. These trips will be
   * ignored for the purposes of non-frequency routing, and all the frequency entries referencing
   * the same trip can be added at once to the same Timetable/TripPattern.
   */
  private void collectFrequencyByTrip() {
    for (Frequency freq : transitServiceBuilder.getFrequencies()) {
      frequenciesForTrip.put(freq.trip(), freq);
    }
  }

  private void buildTripPatternForTrip(Trip trip) {
    // TODO: move to a validator module
    if (!calendarServiceIds.contains(trip.getServiceId())) {
      issueStore.add(new TripUndefinedService(trip));
      // Invalid trip, skip it, it will break later
      return;
    }

    List<StopTime> stopTimes = transitServiceBuilder.getStopTimesSortedByTrip().get(trip);

    // If after filtering this trip does not contain at least 2 stoptimes, it does not serve any purpose.
    var staticTripWithFewerThan2Stops =
      !FlexTrip.containsFlexStops(stopTimes) && stopTimes.size() < 2;
    // flex trips are allowed to have a single stop because that can be an area or a group of stops
    var flexTripWithZeroStops = FlexTrip.containsFlexStops(stopTimes) && stopTimes.size() < 1;
    if (staticTripWithFewerThan2Stops || flexTripWithZeroStops) {
      issueStore.add(new TripDegenerate(trip));
      return;
    }

    // Get the existing TripPattern for this filtered StopPattern, or create one.
    StopPattern stopPattern = new StopPattern(stopTimes);

    TripPatternBuilder tripPatternBuilder = findOrCreateTripPattern(stopPattern, trip);

    // Create a TripTimes object for this list of stoptimes, which form one trip.
    ScheduledTripTimes tripTimes = TripTimesFactory.tripTimes(trip, stopTimes, deduplicator);

    // If this trip is referenced by one or more lines in frequencies.txt, wrap it in a FrequencyEntry.
    List<Frequency> frequencies = frequenciesForTrip.get(trip);
    if (!frequencies.isEmpty()) {
      for (Frequency freq : frequencies) {
        tripPatternBuilder.withScheduledTimeTableBuilder(builder ->
          builder.addFrequencyEntry(new FrequencyEntry(freq, tripTimes))
        );
        freqCount++;
      }
    }
    // This trip was not frequency-based. Add the TripTimes directly to the TripPattern's scheduled timetable.
    else {
      tripPatternBuilder.withScheduledTimeTableBuilder(builder -> builder.addTripTimes(tripTimes));
      scheduledCount++;
    }
  }

  private TripPatternBuilder findOrCreateTripPattern(StopPattern stopPattern, Trip trip) {
    Route route = trip.getRoute();
    Direction direction = trip.getDirection();
    for (TripPatternBuilder tripPatternBuilder : tripPatternBuilders.get(stopPattern)) {
      if (
        tripPatternBuilder.getRoute().equals(route) &&
        tripPatternBuilder.getDirection().equals(direction) &&
        tripPatternBuilder.getMode().equals(trip.getMode()) &&
        tripPatternBuilder.getNetexSubmode().equals(trip.getNetexSubMode())
      ) {
        // A pattern takes its geometry from whichever of its trips is seen first, so it can end
        // up with none even though a later trip on the very same stop pattern carries a shape.
        // Take the first shape offered rather than the first trip's.
        if (!hopGeometriesByPattern.containsKey(tripPatternBuilder)) {
          recordHopGeometries(tripPatternBuilder, geometryProcessor.createHopGeometries(trip));
        }
        return tripPatternBuilder;
      }
    }
    FeedScopedId patternId = generateUniqueIdForTripPattern(route, direction);
    TripPatternBuilder tripPatternBuilder = TripPattern.of(patternId)
      .withRoute(route)
      .withStopPattern(stopPattern)
      .withMode(trip.getMode())
      .withNetexSubmode(trip.getNetexSubMode());
    recordHopGeometries(tripPatternBuilder, geometryProcessor.createHopGeometries(trip));
    tripPatternBuilders.put(stopPattern, tripPatternBuilder);
    return tripPatternBuilder;
  }

  /** Set the geometries on the builder and remember them as a source for patterns that lack any. */
  private void recordHopGeometries(
    TripPatternBuilder tripPatternBuilder,
    @Nullable List<LineString> hopGeometries
  ) {
    tripPatternBuilder.withHopGeometries(hopGeometries);
    if (hopGeometries != null) {
      hopGeometriesByPattern.put(tripPatternBuilder, hopGeometries);
    }
  }

  /**
   * Give patterns whose trips carry no {@code shape_id} the geometry of other patterns covering
   * the same trackage. Runs once every pattern is known, since a pattern can only borrow from
   * sequences that have already been read.
   *
   * @see HopGeometryIndex
   */
  private void fillInMissingHopGeometries() {
    var shapeless = tripPatternBuilders
      .values()
      .stream()
      .filter(b -> !hopGeometriesByPattern.containsKey(b))
      .filter(b -> b.getStopPattern().getSize() > 1)
      .toList();
    if (shapeless.isEmpty()) {
      return;
    }

    var index = new HopGeometryIndex();
    hopGeometriesByPattern.forEach((builder, geometries) ->
      index.add(stopsOf(builder.getStopPattern()), geometries)
    );
    if (index.isEmpty()) {
      LOG.info(
        "{} trip patterns have no shape and there is no geometry to borrow.",
        shapeless.size()
      );
      return;
    }

    int filled = 0;
    int adjacent = 0;
    int stitched = 0;
    int reversed = 0;
    int straight = 0;
    int unresolved = 0;
    for (var builder : shapeless) {
      var stopPattern = builder.getStopPattern();
      var resolution = index.resolve(stopsOf(stopPattern));
      if (!resolution.hasAny()) {
        unresolved++;
        continue;
      }
      builder.withHopGeometries(resolution.geometries());
      filled++;
      adjacent += resolution.adjacent();
      stitched += resolution.stitched();
      reversed += resolution.reversed();
      straight += resolution.straight();
      if (resolution.approximated() > 0 || resolution.straight() > 0) {
        issueStore.add(
          new SynthesizedHopGeometry(
            builder.getRoute().getId(),
            stopPattern.getStop(0).getId(),
            stopPattern.getStop(stopPattern.getSize() - 1).getId(),
            resolution.stitched(),
            resolution.reversed(),
            resolution.straight()
          )
        );
      }
    }
    LOG.info(
      "Synthesized geometry for {} of {} trip patterns without a shape ({} unresolved). " +
        "Hops: {} adjacent, {} stitched, {} reversed, {} left straight.",
      filled,
      shapeless.size(),
      unresolved,
      adjacent,
      stitched,
      reversed,
      straight
    );
  }

  private static List<StopLocation> stopsOf(StopPattern stopPattern) {
    var stops = new ArrayList<StopLocation>(stopPattern.getSize());
    for (int i = 0; i < stopPattern.getSize(); i++) {
      stops.add(stopPattern.getStop(i));
    }
    return stops;
  }

  /**
   * Patterns do not have unique IDs in GTFS, so we make some by concatenating agency id, route id,
   * the direction and an integer. This only works if the Collection of TripPattern includes every
   * TripPattern for the agency.
   */
  private FeedScopedId generateUniqueIdForTripPattern(Route route, Direction direction) {
    FeedScopedId routeId = route.getId();
    String directionId = direction == Direction.UNKNOWN ? "" : Integer.toString(direction.gtfsCode);
    String key = routeId.getId() + ":" + direction;

    // Add 1 to counter and update it
    int counter = tripPatternIdCounters.getOrDefault(key, 0) + 1;
    tripPatternIdCounters.put(key, counter);

    String id = String.format("%s:%s:%02d", routeId.getId(), directionId, counter);

    return new FeedScopedId(routeId.getFeedId(), id);
  }
}
