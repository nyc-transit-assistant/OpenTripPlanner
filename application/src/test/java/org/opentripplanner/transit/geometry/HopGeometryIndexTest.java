package org.opentripplanner.transit.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.Station;

class HopGeometryIndexTest {

  private static final TimetableRepositoryForTest TEST_MODEL = TimetableRepositoryForTest.of();

  // A, B, C run roughly south to north; D is off to one side.
  private static final RegularStop STOP_A = TEST_MODEL.stop("A", 40.70, -74.01).build();
  private static final RegularStop STOP_B = TEST_MODEL.stop("B", 40.72, -74.00).build();
  private static final RegularStop STOP_C = TEST_MODEL.stop("C", 40.75, -73.99).build();
  private static final RegularStop STOP_D = TEST_MODEL.stop("D", 40.71, -73.92).build();

  private static final LineString A_TO_B = curve(-74.01, 40.70, -74.005, 40.71, -74.0, 40.72);
  private static final LineString B_TO_C = curve(-74.0, 40.72, -73.995, 40.74, -73.99, 40.75);

  @Test
  void emptyIndexResolvesEverythingToStraightLines() {
    var index = new HopGeometryIndex();
    assertTrue(index.isEmpty());

    var resolution = index.resolve(List.of(STOP_A, STOP_B));

    assertFalse(resolution.hasAny());
    assertEquals(1, resolution.straight());
    assertEquals(2, resolution.geometries().get(0).getNumPoints());
  }

  @Test
  void adjacentHopIsReusedVerbatim() {
    var index = new HopGeometryIndex();
    index.add(List.of(STOP_A, STOP_B), List.of(A_TO_B));

    var resolution = index.resolve(List.of(STOP_A, STOP_B));

    assertEquals(1, resolution.adjacent());
    assertEquals(0, resolution.approximated());
    assertCoordinatesEqual(A_TO_B, resolution.geometries().get(0));
  }

  @Test
  void mismatchedGeometryCountIsIgnored() {
    var index = new HopGeometryIndex();
    // Two hops claimed, one geometry supplied.
    index.add(List.of(STOP_A, STOP_B, STOP_C), List.of(A_TO_B));

    assertTrue(index.isEmpty());
  }

  @Test
  void twoPointHopIsNotIndexed() {
    // A straight hop tells us nothing the fallback doesn't, and indexing it would mask a real
    // source for the same pair.
    var straight = curve(-74.01, 40.70, -74.0, 40.72);
    var index = new HopGeometryIndex();
    index.add(List.of(STOP_A, STOP_B), List.of(straight));
    var better = new HopGeometryIndex();
    better.add(List.of(STOP_A, STOP_B), List.of(straight));
    better.add(List.of(STOP_A, STOP_B), List.of(A_TO_B));

    assertEquals(0, index.resolve(List.of(STOP_A, STOP_B)).adjacent());
    assertEquals(1, better.resolve(List.of(STOP_A, STOP_B)).adjacent());
  }

  @Test
  void skippedStopIsStitchedFromTheLocalSequence() {
    // An express runs A -> C; only the local A -> B -> C is known.
    var index = new HopGeometryIndex();
    index.add(List.of(STOP_A, STOP_B, STOP_C), List.of(A_TO_B, B_TO_C));

    var resolution = index.resolve(List.of(STOP_A, STOP_C));

    assertEquals(0, resolution.adjacent());
    assertEquals(1, resolution.stitched());
    // The stitched line runs the whole way and keeps the intermediate detail.
    var stitchedGeometry = resolution.geometries().get(0);
    assertEquals(-74.01, stitchedGeometry.getCoordinateN(0).x, 1e-9);
    assertEquals(
      -73.99,
      stitchedGeometry.getCoordinateN(stitchedGeometry.getNumPoints() - 1).x,
      1e-9
    );
    assertTrue(stitchedGeometry.getNumPoints() > A_TO_B.getNumPoints());
  }

  @Test
  void stitchingRespectsDirection() {
    var index = new HopGeometryIndex();
    index.add(List.of(STOP_A, STOP_B, STOP_C), List.of(A_TO_B, B_TO_C));

    // C -> A is the reverse of the known sequence; without stations to match on there is nothing
    // to reverse, so it must not be stitched the wrong way round.
    var resolution = index.resolve(List.of(STOP_C, STOP_A));

    assertEquals(0, resolution.borrowed());
    assertEquals(1, resolution.straight());
  }

  @Test
  void oppositeDirectionIsReversedWhenStopsShareStations() {
    var stationA = station("STA");
    var stationB = station("STB");
    var northA = TEST_MODEL.stop("A-N", 40.70, -74.01).withParentStation(stationA).build();
    var northB = TEST_MODEL.stop("B-N", 40.72, -74.0).withParentStation(stationB).build();
    var southA = TEST_MODEL.stop("A-S", 40.70, -74.01).withParentStation(stationA).build();
    var southB = TEST_MODEL.stop("B-S", 40.72, -74.0).withParentStation(stationB).build();

    var index = new HopGeometryIndex();
    index.add(List.of(southA, southB), List.of(A_TO_B));

    // The other direction, on its own platforms, has no geometry of its own.
    var resolution = index.resolve(List.of(northB, northA));

    assertEquals(1, resolution.reversed());
    var reversed = resolution.geometries().get(0);
    assertEquals(A_TO_B.getNumPoints(), reversed.getNumPoints());
    assertEquals(
      A_TO_B.getCoordinateN(A_TO_B.getNumPoints() - 1).x,
      reversed.getCoordinateN(0).x,
      1e-9
    );
    assertEquals(
      A_TO_B.getCoordinateN(0).x,
      reversed.getCoordinateN(reversed.getNumPoints() - 1).x,
      1e-9
    );
  }

  @Test
  void adjacentBeatsStitchedAndReversed() {
    var index = new HopGeometryIndex();
    index.add(List.of(STOP_A, STOP_B, STOP_C), List.of(A_TO_B, B_TO_C));
    var direct = curve(-74.01, 40.70, -74.002, 40.735, -73.99, 40.75);
    index.add(List.of(STOP_A, STOP_C), List.of(direct));

    var resolution = index.resolve(List.of(STOP_A, STOP_C));

    assertEquals(1, resolution.adjacent());
    assertEquals(0, resolution.stitched());
    assertCoordinatesEqual(direct, resolution.geometries().get(0));
  }

  @Test
  void unresolvableHopFallsBackToAStraightLine() {
    var index = new HopGeometryIndex();
    index.add(List.of(STOP_A, STOP_B), List.of(A_TO_B));

    var resolution = index.resolve(List.of(STOP_A, STOP_B, STOP_D));

    assertEquals(1, resolution.adjacent());
    assertEquals(1, resolution.straight());
    assertEquals(2, resolution.geometries().size());
    var fallback = resolution.geometries().get(1);
    assertEquals(2, fallback.getNumPoints());
    assertEquals(STOP_D.getLon(), fallback.getCoordinateN(1).x, 1e-9);
  }

  private static Station station(String id) {
    return TEST_MODEL.station(id).build();
  }

  private static LineString curve(double... lonLat) {
    var coords = new Coordinate[lonLat.length / 2];
    for (int i = 0; i < coords.length; i++) {
      coords[i] = new Coordinate(lonLat[2 * i], lonLat[2 * i + 1]);
    }
    return GeometryUtils.getGeometryFactory().createLineString(coords);
  }

  private static void assertCoordinatesEqual(LineString expected, LineString actual) {
    assertEquals(expected.getNumPoints(), actual.getNumPoints(), "point count");
    assertEquals(List.of(expected.getCoordinates()), List.of(actual.getCoordinates()));
  }
}
