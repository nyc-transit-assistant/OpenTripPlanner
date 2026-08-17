package org.opentripplanner.street.linking;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.street.model.StreetModelFactory.intersectionVertex;
import static org.opentripplanner.street.model.StreetModelFactory.streetEdgeBuilder;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.service.vehiclerental.GeofencingZoneService;
import org.opentripplanner.street.graph.Graph;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.FreeEdge;
import org.opentripplanner.street.model.vertex.IntersectionVertex;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.TraverseModeSet;

/**
 * A 2D-linked request coordinate must not snap onto infrastructure mapped below street level
 * (station concourses, tunnels) that merely lies beneath it: those networks are only reachable
 * through entrances elsewhere, so linking into them strands the request. Surface edges win
 * whenever any are in range; underground edges remain the fallback, and build-time (permanent)
 * linking is unaffected.
 */
class BelowStreetLevelLinkingTest {

  private static final double SURFACE_LAT = 60.0;
  private static final double UNDERGROUND_LAT = 60.0001;

  /**
   * Two parallel east-west edge pairs; the underground one is closer to the probe point.
   */
  private Graph buildGraph() {
    var graph = new Graph();

    IntersectionVertex s1 = intersectionVertex("surface-w", SURFACE_LAT, 10.0);
    IntersectionVertex s2 = intersectionVertex("surface-e", SURFACE_LAT, 10.002);
    IntersectionVertex u1 = intersectionVertex("underground-w", UNDERGROUND_LAT, 10.0);
    IntersectionVertex u2 = intersectionVertex("underground-e", UNDERGROUND_LAT, 10.002);
    for (var v : List.of(s1, s2, u1, u2)) {
      graph.addVertex(v);
    }

    streetEdgeBuilder(s1, s2, 110, StreetTraversalPermission.PEDESTRIAN).buildAndConnect();
    streetEdgeBuilder(s2, s1, 110, StreetTraversalPermission.PEDESTRIAN).buildAndConnect();
    streetEdgeBuilder(u1, u2, 110, StreetTraversalPermission.PEDESTRIAN)
      .withBelowStreetLevel(true)
      .buildAndConnect();
    streetEdgeBuilder(u2, u1, 110, StreetTraversalPermission.PEDESTRIAN)
      .withBelowStreetLevel(true)
      .buildAndConnect();

    graph.index();
    return graph;
  }

  private VertexLinker linker(Graph graph) {
    return new VertexLinker(
      graph,
      GeofencingZoneService.EMPTY,
      VisibilityMode.TRAVERSE_AREA_EDGES,
      50,
      false
    );
  }

  /** Probe sits 4.5m from the underground edge and ~16m from the surface edge. */
  private IntersectionVertex probeVertex(Graph graph) {
    var probe = intersectionVertex("probe", 60.00014, 10.001);
    graph.addVertex(probe);
    return probe;
  }

  @Test
  void requestLinkingPrefersSurfaceEdges() {
    var graph = buildGraph();
    var probe = probeVertex(graph);

    linker(graph).linkVertexForRequest(
      probe,
      new TraverseModeSet(TraverseMode.WALK),
      LinkingDirection.BIDIRECTIONAL,
      (vertex, streetVertex) ->
        List.of(
          FreeEdge.createFreeEdge(vertex, streetVertex),
          FreeEdge.createFreeEdge(streetVertex, vertex)
        )
    );

    var linkedLats = probe
      .getOutgoing()
      .stream()
      .map(e -> e.getToVertex().getLat())
      .toList();
    assertFalse(linkedLats.isEmpty(), "probe should have linked");
    assertTrue(
      linkedLats.stream().allMatch(lat -> Math.abs(lat - SURFACE_LAT) < 1e-9),
      "request coordinate must link to the surface edge, not the closer underground one: " +
        linkedLats
    );
  }

  @Test
  void requestLinkingFallsBackToUndergroundWhenNothingElseInRange() {
    var graph = new Graph();
    IntersectionVertex u1 = intersectionVertex("underground-w", UNDERGROUND_LAT, 10.0);
    IntersectionVertex u2 = intersectionVertex("underground-e", UNDERGROUND_LAT, 10.002);
    graph.addVertex(u1);
    graph.addVertex(u2);
    streetEdgeBuilder(u1, u2, 110, StreetTraversalPermission.PEDESTRIAN)
      .withBelowStreetLevel(true)
      .buildAndConnect();
    streetEdgeBuilder(u2, u1, 110, StreetTraversalPermission.PEDESTRIAN)
      .withBelowStreetLevel(true)
      .buildAndConnect();
    graph.index();
    var probe = probeVertex(graph);

    linker(graph).linkVertexForRequest(
      probe,
      new TraverseModeSet(TraverseMode.WALK),
      LinkingDirection.BIDIRECTIONAL,
      (vertex, streetVertex) ->
        List.of(
          FreeEdge.createFreeEdge(vertex, streetVertex),
          FreeEdge.createFreeEdge(streetVertex, vertex)
        )
    );

    assertFalse(
      probe.getOutgoing().isEmpty(),
      "with only underground edges in range, linking must still succeed"
    );
  }

  @Test
  void permanentLinkingStillUsesNearestEdge() {
    var graph = buildGraph();
    var probe = probeVertex(graph);

    linker(graph).linkVertexPermanently(
      probe,
      new TraverseModeSet(TraverseMode.WALK),
      LinkingDirection.BIDIRECTIONAL,
      (vertex, streetVertex) ->
        List.of(
          FreeEdge.createFreeEdge(vertex, streetVertex),
          FreeEdge.createFreeEdge(streetVertex, vertex)
        )
    );

    var linkedLats = probe
      .getOutgoing()
      .stream()
      .map(e -> e.getToVertex().getLat())
      .toList();
    assertFalse(linkedLats.isEmpty(), "probe should have linked");
    assertTrue(
      linkedLats.stream().allMatch(lat -> Math.abs(lat - UNDERGROUND_LAT) < 1e-9),
      "permanent linking keeps nearest-edge behavior (stops/entrances live underground): " +
        linkedLats
    );
  }
}
