package org.opentripplanner.street.model.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.street.model.StreetModelFactory.intersectionVertex;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.street.model.vertex.ElevatorHopVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.street.search.state.State;

class ElevatorBoardEdgeTest {

  private final Vertex from = intersectionVertex(0, 0);
  private final ElevatorHopVertex to = new ElevatorHopVertex(from, "test");

  private ElevatorBoardEdge edge(String name) {
    return ElevatorBoardEdge.createElevatorBoardEdge(
      from,
      to,
      name == null ? null : new NonLocalizedString(name)
    );
  }

  private State[] traverse(ElevatorBoardEdge edge, boolean wheelchair, Set<String> inoperative) {
    var req = StreetSearchRequest.of()
      .withWheelchairEnabled(wheelchair)
      .withWheelchair(b -> b.withInoperativeEquipment(inoperative).build())
      .build();
    return edge.traverse(new State(from, req));
  }

  @Test
  void parsesSignpostedEquipmentCode() {
    // Real signposts are prose starting with the code, not the bare code.
    assertEquals("EL131", edge("EL131 elevator to mezzanine").equipmentCode());
    assertEquals("EL359", edge("EL359").equipmentCode());
    assertEquals("ES115", edge("ES115 escalator to Manhattan-bound platform").equipmentCode());
    assertEquals("EL290X", edge("EL290X to PATH concourse").equipmentCode());
    assertNull(edge("Track 12 elevator").equipmentCode());
    assertNull(edge("ELEVATED walkway").equipmentCode());
    assertNull(edge(null).equipmentCode());
  }

  @Test
  void wheelchairSearchBlockedThroughInoperativeUnit() {
    var result = traverse(edge("EL359 elevator to street"), true, Set.of("EL359", "EL100"));
    assertTrue(State.isEmpty(result));
  }

  @Test
  void wheelchairSearchTraversesOperativeUnit() {
    var result = traverse(edge("EL359"), true, Set.of("EL100"));
    assertFalse(State.isEmpty(result));
  }

  @Test
  void nonWheelchairSearchUnaffectedByOutages() {
    var result = traverse(edge("EL359"), false, Set.of("EL359"));
    assertFalse(State.isEmpty(result));
  }

  @Test
  void unsignpostedElevatorNeverBlocked() {
    var result = traverse(edge(null), true, Set.of("EL359"));
    assertFalse(State.isEmpty(result));
  }
}
