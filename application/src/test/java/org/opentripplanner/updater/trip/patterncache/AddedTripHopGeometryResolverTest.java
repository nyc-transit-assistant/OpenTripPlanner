package org.opentripplanner.updater.trip.patterncache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.site.RegularStop;

/**
 * Unit tests for {@link AddedTripHopGeometryResolver}. The resolver is exercised entirely via
 * the narrow {@code PatternsForRoute} lambda, so no TransitService/snapshot setup is needed.
 */
class AddedTripHopGeometryResolverTest {

  private static final TimetableRepositoryForTest TEST_MODEL = TimetableRepositoryForTest.of();

  // Stops laid out roughly along Manhattan: A south, B middle, C north, D further north,
  // E off to the side. The exact coordinates don't matter for the resolver, but having
  // distinct ones makes it possible to identify which hop a returned LineString came from.
  private static final RegularStop STOP_A = TEST_MODEL.stop("A", 40.70, -74.01).build();
  private static final RegularStop STOP_B = TEST_MODEL.stop("B", 40.72, -74.00).build();
  private static final RegularStop STOP_C = TEST_MODEL.stop("C", 40.75, -73.99).build();
  private static final RegularStop STOP_D = TEST_MODEL.stop("D", 40.78, -73.97).build();
  private static final RegularStop STOP_E = TEST_MODEL.stop("E", 40.71, -73.92).build();

  private static final Route ROUTE = TimetableRepositoryForTest.route("R1").build();
  private static final Route OTHER_ROUTE = TimetableRepositoryForTest.route("R2").build();

  // Distinctive geometry for the A→B hop in the static pattern: a curve going through (40.71, -74.005).
  private static final LineString A_TO_B_GEOM = lineString(
    new Coordinate(-74.01, 40.70),
    new Coordinate(-74.005, 40.71),
    new Coordinate(-74.00, 40.72)
  );
  // Distinctive geometry for B→C with an intermediate point.
  private static final LineString B_TO_C_GEOM = lineString(
    new Coordinate(-74.00, 40.72),
    new Coordinate(-73.995, 40.74),
    new Coordinate(-73.99, 40.75)
  );

  @Test
  void emptyExistingPatternsReturnsEmpty() {
    var resolver = new AddedTripHopGeometryResolver(route -> List.of());
    var stopPattern = stopPattern(STOP_A, STOP_B);

    assertTrue(resolver.resolve(stopPattern, ROUTE).isEmpty());
  }

  @Test
  void singleStopReturnsEmpty() {
    var staticPattern = patternWithGeometries(List.of(STOP_A, STOP_B), List.of(A_TO_B_GEOM));
    var resolver = new AddedTripHopGeometryResolver(route -> List.of(staticPattern));

    assertTrue(resolver.resolve(stopPattern(STOP_A), ROUTE).isEmpty());
  }

  @Test
  void noMatchingHopsReturnsEmpty() {
    // Static pattern A→B, but added trip is C→D.
    var staticPattern = patternWithGeometries(List.of(STOP_A, STOP_B), List.of(A_TO_B_GEOM));
    var resolver = new AddedTripHopGeometryResolver(route -> List.of(staticPattern));

    assertTrue(resolver.resolve(stopPattern(STOP_C, STOP_D), ROUTE).isEmpty());
  }

  @Test
  void singleMatchingHopReusesStaticGeometry() {
    var staticPattern = patternWithGeometries(List.of(STOP_A, STOP_B), List.of(A_TO_B_GEOM));
    var resolver = new AddedTripHopGeometryResolver(route -> List.of(staticPattern));

    var resolved = resolver.resolve(stopPattern(STOP_A, STOP_B), ROUTE).orElseThrow();

    assertEquals(1, resolved.size());
    assertCurveEquals(A_TO_B_GEOM, resolved.get(0));
  }

  @Test
  void allHopsMatchedAcrossSinglePattern() {
    // Static pattern covers A→B→C, added trip walks the same hops.
    var staticPattern = patternWithGeometries(
      List.of(STOP_A, STOP_B, STOP_C),
      List.of(A_TO_B_GEOM, B_TO_C_GEOM)
    );
    var resolver = new AddedTripHopGeometryResolver(route -> List.of(staticPattern));

    var resolved = resolver.resolve(stopPattern(STOP_A, STOP_B, STOP_C), ROUTE).orElseThrow();

    assertEquals(2, resolved.size());
    assertCurveEquals(A_TO_B_GEOM, resolved.get(0));
    assertCurveEquals(B_TO_C_GEOM, resolved.get(1));
  }

  @Test
  void partialMatchUsesStraightLineForUnmatchedHop() {
    // Static covers A→B but not B→E; added trip is A→B→E.
    var staticPattern = patternWithGeometries(List.of(STOP_A, STOP_B), List.of(A_TO_B_GEOM));
    var resolver = new AddedTripHopGeometryResolver(route -> List.of(staticPattern));

    var resolved = resolver.resolve(stopPattern(STOP_A, STOP_B, STOP_E), ROUTE).orElseThrow();

    assertEquals(2, resolved.size());
    assertCurveEquals(A_TO_B_GEOM, resolved.get(0));
    // Unmatched hop B→E falls back to a 2-point straight line between the stops.
    var fallback = resolved.get(1);
    assertEquals(2, fallback.getNumPoints());
    assertEquals(STOP_B.getLon(), fallback.getCoordinateN(0).x);
    assertEquals(STOP_B.getLat(), fallback.getCoordinateN(0).y);
    assertEquals(STOP_E.getLon(), fallback.getCoordinateN(1).x);
    assertEquals(STOP_E.getLat(), fallback.getCoordinateN(1).y);
  }

  @Test
  void hopsAreCollectedFromMultiplePatterns() {
    // First static covers A→B only, second covers B→C only.
    var p1 = patternWithGeometries(List.of(STOP_A, STOP_B), List.of(A_TO_B_GEOM));
    var p2 = patternWithGeometries(List.of(STOP_B, STOP_C), List.of(B_TO_C_GEOM));
    var resolver = new AddedTripHopGeometryResolver(route -> List.of(p1, p2));

    var resolved = resolver.resolve(stopPattern(STOP_A, STOP_B, STOP_C), ROUTE).orElseThrow();

    assertCurveEquals(A_TO_B_GEOM, resolved.get(0));
    assertCurveEquals(B_TO_C_GEOM, resolved.get(1));
  }

  @Test
  void respectsHopDirection() {
    // Static has A→B; the added trip goes B→A. Reverse direction must NOT match — this
    // would otherwise produce a polyline that runs the wrong way along the track.
    var staticPattern = patternWithGeometries(List.of(STOP_A, STOP_B), List.of(A_TO_B_GEOM));
    var resolver = new AddedTripHopGeometryResolver(route -> List.of(staticPattern));

    assertTrue(resolver.resolve(stopPattern(STOP_B, STOP_A), ROUTE).isEmpty());
  }

  @Test
  void onlyConsidersPatternsForGivenRoute() {
    // Static pattern exists but on a different route; the lookup function should only see
    // patterns for the queried route. We simulate by returning the patterns lookup is
    // called with the right argument and returning a match; for a wrong route the lookup
    // returns empty.
    var staticPattern = patternWithGeometries(List.of(STOP_A, STOP_B), List.of(A_TO_B_GEOM));
    AddedTripHopGeometryResolver.PatternsForRoute lookup = route ->
      route.equals(ROUTE) ? List.of(staticPattern) : List.of();
    var resolver = new AddedTripHopGeometryResolver(lookup);

    assertEquals(1, resolver.resolve(stopPattern(STOP_A, STOP_B), ROUTE).orElseThrow().size());
    assertTrue(resolver.resolve(stopPattern(STOP_A, STOP_B), OTHER_ROUTE).isEmpty());
  }

  @Test
  void nonAdjacentStopsAreStitchedFromTheLongerPattern() {
    // Static pattern A→B→C covers A and C but not adjacently. An express skipping B follows the
    // same trackage, so the intervening hops are concatenated rather than left straight.
    var staticPattern = patternWithGeometries(
      List.of(STOP_A, STOP_B, STOP_C),
      List.of(A_TO_B_GEOM, B_TO_C_GEOM)
    );
    var resolver = new AddedTripHopGeometryResolver(route -> List.of(staticPattern));

    var resolved = resolver.resolve(stopPattern(STOP_A, STOP_C), ROUTE).orElseThrow();

    assertEquals(1, resolved.size());
    var stitched = resolved.get(0);
    assertEquals(A_TO_B_GEOM.getCoordinateN(0), stitched.getCoordinateN(0));
    assertEquals(
      B_TO_C_GEOM.getCoordinateN(B_TO_C_GEOM.getNumPoints() - 1),
      stitched.getCoordinateN(stitched.getNumPoints() - 1)
    );
    assertTrue(stitched.getNumPoints() > A_TO_B_GEOM.getNumPoints());
  }

  @Test
  void borrowsHopFromAnotherRouteWhenOwnRouteHasNone() {
    // Nothing on this route covers A→B, but another route through the same stops does.
    var otherRoutePattern = patternWithGeometries(
      List.of(STOP_A, STOP_B),
      List.of(A_TO_B_GEOM),
      OTHER_ROUTE
    );
    var resolver = new AddedTripHopGeometryResolver(
      route -> List.of(),
      stop -> stop.equals(STOP_A) ? List.of(otherRoutePattern) : List.of()
    );

    var resolved = resolver.resolve(stopPattern(STOP_A, STOP_B), ROUTE).orElseThrow();

    assertEquals(1, resolved.size());
    assertCurveEquals(A_TO_B_GEOM, resolved.get(0));
  }

  @Test
  void prefersDetailedGeometryOverShapelessOwnRouteMatch() {
    // The route's own pattern covers A→B but carries no shape, so its hop geometry is a bare
    // straight line. A shaped pattern on another route must win.
    var shapeless = patternWithoutGeometries(List.of(STOP_A, STOP_B), ROUTE);
    var shaped = patternWithGeometries(List.of(STOP_A, STOP_B), List.of(A_TO_B_GEOM), OTHER_ROUTE);
    var resolver = new AddedTripHopGeometryResolver(
      route -> List.of(shapeless),
      stop -> List.of(shaped)
    );

    var resolved = resolver.resolve(stopPattern(STOP_A, STOP_B), ROUTE).orElseThrow();

    assertCurveEquals(A_TO_B_GEOM, resolved.get(0));
  }

  @Test
  void shapelessPatternEarlierInTheListDoesNotMaskAShapedOne() {
    // Both patterns cover A→B and both are on this route, but the shapeless one is scanned
    // first. Returning its synthesized straight line would lose the real geometry entirely —
    // this is what stranded southbound SIR patterns.
    var shapeless = patternWithoutGeometries(List.of(STOP_A, STOP_B), ROUTE);
    var shaped = patternWithGeometries(List.of(STOP_A, STOP_B), List.of(A_TO_B_GEOM));
    var resolver = new AddedTripHopGeometryResolver(route -> List.of(shapeless, shaped));

    var resolved = resolver.resolve(stopPattern(STOP_A, STOP_B), ROUTE).orElseThrow();

    assertCurveEquals(A_TO_B_GEOM, resolved.get(0));
  }

  @Test
  void shapelessMatchesAloneReturnEmpty() {
    // Every candidate is shapeless, so we'd only be storing straight lines. Leave it unset and
    // let the API's own fallback do it.
    var shapeless = patternWithoutGeometries(List.of(STOP_A, STOP_B), ROUTE);
    var resolver = new AddedTripHopGeometryResolver(
      route -> List.of(shapeless),
      stop -> List.of(shapeless)
    );

    assertTrue(resolver.resolve(stopPattern(STOP_A, STOP_B), ROUTE).isEmpty());
  }

  @Test
  void crossRouteLookupStillRespectsHopDirection() {
    var otherRoutePattern = patternWithGeometries(
      List.of(STOP_A, STOP_B),
      List.of(A_TO_B_GEOM),
      OTHER_ROUTE
    );
    var resolver = new AddedTripHopGeometryResolver(
      route -> List.of(),
      stop -> List.of(otherRoutePattern)
    );

    assertTrue(resolver.resolve(stopPattern(STOP_B, STOP_A), ROUTE).isEmpty());
  }

  // ---------- helpers ----------

  private static StopPattern stopPattern(RegularStop... stops) {
    return TimetableRepositoryForTest.stopPattern(stops);
  }

  private static TripPattern patternWithGeometries(
    List<RegularStop> stops,
    List<LineString> hopGeometries
  ) {
    return patternWithGeometries(stops, hopGeometries, ROUTE);
  }

  private static TripPattern patternWithGeometries(
    List<RegularStop> stops,
    List<LineString> hopGeometries,
    Route route
  ) {
    return TripPattern.of(
      TimetableRepositoryForTest.id("static-" + route.getId() + "-" + stops.hashCode())
    )
      .withRoute(route)
      .withStopPattern(TimetableRepositoryForTest.stopPattern(stops.toArray(new RegularStop[0])))
      .withHopGeometries(hopGeometries)
      .build();
  }

  /** A pattern whose trips reference no shape — {@code getHopGeometry} yields straight lines. */
  private static TripPattern patternWithoutGeometries(List<RegularStop> stops, Route route) {
    return TripPattern.of(
      TimetableRepositoryForTest.id("noshape-" + route.getId() + "-" + stops.hashCode())
    )
      .withRoute(route)
      .withStopPattern(TimetableRepositoryForTest.stopPattern(stops.toArray(new RegularStop[0])))
      .build();
  }

  private static LineString lineString(Coordinate... coords) {
    return GeometryUtils.getGeometryFactory().createLineString(coords);
  }

  /**
   * Asserts two LineStrings have the same coordinates. Reference equality fails when the
   * static pattern internally re-serializes geometries.
   */
  private static void assertCurveEquals(LineString expected, LineString actual) {
    assertEquals(expected.getNumPoints(), actual.getNumPoints(), "point count");
    Collection<Coordinate> e = List.of(expected.getCoordinates());
    Collection<Coordinate> a = List.of(actual.getCoordinates());
    assertEquals(e, a);
  }
}
