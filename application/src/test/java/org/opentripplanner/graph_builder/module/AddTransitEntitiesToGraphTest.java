package org.opentripplanner.graph_builder.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedIdForTestFactory;
import org.opentripplanner.street.geometry.WgsCoordinate;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.site.Pathway;
import org.opentripplanner.transit.model.site.PathwayMode;
import org.opentripplanner.transit.model.site.PathwayNode;
import org.opentripplanner.transit.model.site.RegularStop;

class AddTransitEntitiesToGraphTest {

  private static final PathwayNode FROM = PathwayNode.of(FeedScopedIdForTestFactory.id("1:node"))
    .withCoordinate(new WgsCoordinate(20, 30))
    .build();
  private static final RegularStop TO = TimetableRepositoryForTest.of().stop("1:stop").build();

  private static org.opentripplanner.transit.model.site.PathwayBuilder builder(PathwayMode mode) {
    return Pathway.of(FeedScopedIdForTestFactory.id("1:pw"))
      .withPathwayMode(mode)
      .withFromStop(FROM)
      .withToStop(TO);
  }

  @Test
  void stairsModePathwayWithoutStairCountGetsSyntheticSteps() {
    assertEquals(
      16,
      AddTransitEntitiesToGraph.effectiveStairCount(builder(PathwayMode.STAIRS).build())
    );
  }

  @Test
  void explicitStairCountIsKept() {
    assertEquals(
      7,
      AddTransitEntitiesToGraph.effectiveStairCount(
        builder(PathwayMode.STAIRS).withStairCount(7).build()
      )
    );
  }

  @Test
  void nonStairsModesAreUntouched() {
    assertEquals(
      0,
      AddTransitEntitiesToGraph.effectiveStairCount(builder(PathwayMode.WALKWAY).build())
    );
  }
}
