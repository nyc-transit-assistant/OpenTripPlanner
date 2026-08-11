package org.opentripplanner.street.model.edge;

import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.TraverseMode;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.street.search.state.StateEditor;

/**
 * A walking pathway as described in GTFS
 */
public class PathwayEdge extends Edge implements BikeWalkableEdge, WheelchairTraversalInformation {

  public static final I18NString DEFAULT_NAME = new NonLocalizedString("pathway");

  /**
   * Weight added per fare gate in direct (street-only) searches. Crossing a
   * station entrance-to-entrance passes two gates, so a cut-through pays
   * twice this — enough to kill save-a-corner shortcuts through fare control
   * while a genuinely superior passage (multiple minutes saved) remains
   * routable.
   */
  public static final double FARE_GATE_DIRECT_WEIGHT = 300;

  @Nullable
  private final I18NString signpostedAs;

  private final int traversalTime;
  private final double distance;
  private final int steps;
  private final double slope;

  private final boolean wheelchairAccessible;
  private final boolean fareGate;

  private PathwayEdge(
    Vertex fromv,
    Vertex tov,
    @Nullable I18NString signpostedAs,
    int traversalTime,
    double distance,
    int steps,
    double slope,
    boolean wheelchairAccessible,
    boolean fareGate
  ) {
    super(fromv, tov);
    this.signpostedAs = signpostedAs;
    this.traversalTime = traversalTime;
    this.steps = steps;
    this.slope = slope;
    this.wheelchairAccessible = wheelchairAccessible;
    this.distance = distance;
    this.fareGate = fareGate;
  }

  /**
   * Create a PathwayEdge that doesn't have a traversal time, distance or steps.
   * <p>
   * These are for edges which have an implied cost of almost zero just like a FreeEdge has.
   */
  public static PathwayEdge createLowCostPathwayEdge(
    Vertex fromV,
    Vertex toV,
    boolean wheelchairAccessible
  ) {
    return createPathwayEdge(fromV, toV, null, 0, 0, 0, 0, wheelchairAccessible);
  }

  public static PathwayEdge createPathwayEdge(
    Vertex fromv,
    Vertex tov,
    I18NString signpostedAs,
    int traversalTime,
    double distance,
    int steps,
    double slope,
    boolean wheelchairAccessible
  ) {
    return createPathwayEdge(
      fromv,
      tov,
      signpostedAs,
      traversalTime,
      distance,
      steps,
      slope,
      wheelchairAccessible,
      false
    );
  }

  public static PathwayEdge createPathwayEdge(
    Vertex fromv,
    Vertex tov,
    I18NString signpostedAs,
    int traversalTime,
    double distance,
    int steps,
    double slope,
    boolean wheelchairAccessible,
    boolean fareGate
  ) {
    return connectToGraph(
      new PathwayEdge(
        fromv,
        tov,
        signpostedAs,
        traversalTime,
        distance,
        steps,
        slope,
        wheelchairAccessible,
        fareGate
      )
    );
  }

  @Override
  public State[] traverse(State s0) {
    StateEditor s1 = createEditorForWalking(s0, this);
    if (s1 == null) {
      return State.empty();
    }

    var request = s0.getRequest();

    long time_ms = 1000L * traversalTime;

    if (time_ms == 0) {
      if (distance > 0) {
        time_ms = (long) ((1000.0 * distance) / request.walk().speed());
      } else if (isStairs()) {
        // 1 step corresponds to 20cm, doubling that to compensate for elevation;
        time_ms = (long) ((1000.0 * 0.4 * Math.abs(steps)) / request.walk().speed());
      }
    }

    if (s0.getRequest().wheelchairEnabled() && isStairs()) {
      // Station stairs are impassable to a wheelchair, not merely slow. A
      // reluctance keeps stair pathways on the pareto frontier whenever they
      // are time-fastest (Union Sq: alight the L, walk the street, take the
      // stairs down to the elevator-less 4/5/6), so soft costs cannot express
      // "there is no elevator to this platform". Hard-refuse; the feed
      // guarantees census-accessible platforms a step-free chain.
      return State.empty();
    }

    if (fareGate && s0.getRequest().penalizeFareGates()) {
      s1.incrementWeight(FARE_GATE_DIRECT_WEIGHT);
    }

    if (time_ms > 0) {
      double weight = time_ms / 1000.0;
      if (s0.getRequest().wheelchairEnabled()) {
        weight *= StreetEdgeReluctanceCalculator.computeWheelchairReluctance(
          request,
          slope,
          wheelchairAccessible,
          isStairs()
        );
      } else {
        weight *= StreetEdgeReluctanceCalculator.computeReluctance(
          request,
          TraverseMode.WALK,
          s0.currentMode() == TraverseMode.BICYCLE,
          isStairs()
        );
      }
      s1.incrementTimeInMilliseconds(time_ms);
      s1.incrementWeight(weight);
    } else {
      // elevators often don't have a traversal time, distance or steps, so we need to add
      // _some_ cost. the real cost is added in ElevatorHopEdge.
      // adding a cost of 1 is analogous to FreeEdge
      s1.incrementWeight(1);
    }

    return s1.makeStateArray();
  }

  /**
   * Return the sign to follow when traversing the pathway. An empty optional means that this
   * pathway does not have "signposted at" information.
   */
  public Optional<I18NString> signpostedAs() {
    return Optional.ofNullable(signpostedAs);
  }

  @Override
  public I18NString getName() {
    return Objects.requireNonNullElse(signpostedAs, DEFAULT_NAME);
  }

  @Override
  public boolean nameIsDerived() {
    return signpostedAs == null;
  }

  public LineString getGeometry() {
    Coordinate[] coordinates = new Coordinate[] {
      getFromVertex().getCoordinate(),
      getToVertex().getCoordinate(),
    };
    return GeometryUtils.getGeometryFactory().createLineString(coordinates);
  }

  public double getDistanceMeters() {
    return this.distance;
  }

  @Override
  public double getEffectiveWalkDistance() {
    if (traversalTime > 0) {
      return 0;
    } else {
      return distance;
    }
  }

  public int getSteps() {
    return steps;
  }

  @Override
  public boolean isWheelchairAccessible() {
    return wheelchairAccessible;
  }

  private boolean isStairs() {
    return steps > 0;
  }
}
