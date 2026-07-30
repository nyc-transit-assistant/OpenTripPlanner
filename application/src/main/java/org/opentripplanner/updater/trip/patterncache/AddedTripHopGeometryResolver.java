package org.opentripplanner.updater.trip.patterncache;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transit.geometry.HopGeometryIndex;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.site.StopLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reuses hop geometries from existing {@link TripPattern}s when constructing a pattern for a
 * realtime trip that has none of its own.
 * <p>
 * Stock OTP creates such patterns with no stored hop geometries, which makes the API fall back to
 * straight stop-to-stop lines. For agencies whose static feed already describes most of the
 * network (e.g. NYC subway) the real alignment is usually available on some other pattern, so it
 * is harvested into a {@link HopGeometryIndex} and reused. See that class for how a hop is
 * resolved and how good each answer is.
 * <p>
 * The class is intentionally narrow: it depends only on the two lookup functions, not the full
 * {@code TransitService}, so unit tests can supply hand-built patterns.
 */
public class AddedTripHopGeometryResolver {

  private static final Logger LOG = LoggerFactory.getLogger(AddedTripHopGeometryResolver.class);

  /** Lookup the patterns belonging to a route. Narrowed for testability. */
  @FunctionalInterface
  public interface PatternsForRoute {
    Collection<TripPattern> apply(Route route);
  }

  /**
   * Lookup the patterns serving a stop, regardless of route. Optional; when supplied it lets a hop
   * borrow geometry from a different route sharing the same trackage.
   */
  @FunctionalInterface
  public interface PatternsForStop {
    Collection<TripPattern> apply(StopLocation stop);
  }

  private final PatternsForRoute patternsForRoute;

  @Nullable
  private final PatternsForStop patternsForStop;

  public AddedTripHopGeometryResolver(PatternsForRoute patternsForRoute) {
    this(patternsForRoute, null);
  }

  public AddedTripHopGeometryResolver(
    PatternsForRoute patternsForRoute,
    @Nullable PatternsForStop patternsForStop
  ) {
    this.patternsForRoute = patternsForRoute;
    this.patternsForStop = patternsForStop;
  }

  /**
   * Build a hop-geometry list aligned to {@code stopPattern}, one entry per hop, from the geometry
   * already known for {@code route} and for the routes sharing its stops.
   * <p>
   * Returns {@link Optional#empty()} when no hop could be resolved, so the caller can leave the
   * geometries unset and rely on OTP's own straight-line fallback rather than pay to store
   * geometries that add nothing.
   */
  public Optional<List<LineString>> resolve(StopPattern stopPattern, Route route) {
    int hopCount = stopPattern.getSize() - 1;
    if (hopCount < 1) {
      return Optional.empty();
    }

    var stops = stopsOf(stopPattern);
    var index = new HopGeometryIndex();
    var harvested = new HashSet<FeedScopedId>();
    harvest(index, patternsForRoute.apply(route), harvested);
    if (patternsForStop != null) {
      for (var stop : stops) {
        harvest(index, patternsForStop.apply(stop), harvested);
      }
    }

    var resolution = index.resolve(stops);
    if (!resolution.hasAny()) {
      // Nothing to borrow anywhere — the polyline will be straight lines. Rare enough to be worth
      // a log line; a burst of these means the static feed has lost its shapes.
      LOG.info(
        "hop-geometry miss: route={} hops={} sourcePatterns={} first={} last={}",
        route.getId(),
        hopCount,
        harvested.size(),
        stops.get(0).getId(),
        stops.get(hopCount).getId()
      );
      return Optional.empty();
    }
    if (resolution.straight() > 0 && LOG.isDebugEnabled()) {
      LOG.debug(
        "hop-geometry partial: route={} hops={} adjacent={} stitched={} reversed={} straight={}",
        route.getId(),
        hopCount,
        resolution.adjacent(),
        resolution.stitched(),
        resolution.reversed(),
        resolution.straight()
      );
    }
    return Optional.of(resolution.geometries());
  }

  /**
   * Feed patterns into the index, skipping ones already harvested and ones with no geometry of
   * their own — the latter would only contribute the straight lines that
   * {@link TripPattern#getHopGeometry(int)} synthesizes on demand.
   */
  private static void harvest(
    HopGeometryIndex index,
    Collection<TripPattern> patterns,
    Set<FeedScopedId> harvested
  ) {
    for (var pattern : patterns) {
      if (!harvested.add(pattern.getId()) || pattern.getGeometry() == null) {
        continue;
      }
      int hops = pattern.numberOfStops() - 1;
      if (hops < 1) {
        continue;
      }
      var geometries = new java.util.ArrayList<LineString>(hops);
      for (int i = 0; i < hops; i++) {
        geometries.add(pattern.getHopGeometry(i));
      }
      index.add(List.copyOf(pattern.getStops()), geometries);
    }
  }

  private static List<StopLocation> stopsOf(StopPattern stopPattern) {
    var stops = new java.util.ArrayList<StopLocation>(stopPattern.getSize());
    for (int i = 0; i < stopPattern.getSize(); i++) {
      stops.add(stopPattern.getStop(i));
    }
    return stops;
  }
}
