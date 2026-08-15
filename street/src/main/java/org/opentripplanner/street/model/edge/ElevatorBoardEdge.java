package org.opentripplanner.street.model.edge;

import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.core.model.i18n.I18NString;
import org.opentripplanner.core.model.i18n.NonLocalizedString;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.street.model.vertex.ElevatorHopVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.street.search.state.StateEditor;

/**
 * A relatively high cost edge for boarding an elevator.
 *
 * @author mattwigway
 */
public class ElevatorBoardEdge extends Edge implements BikeWalkableEdge, ElevatorEdge {

  /**
   * The polyline geometry of this edge. It's generally a polyline with two coincident points, but
   * some elevators have horizontal dimension, e.g. the ones on the Eiffel Tower.
   */
  private final LineString geometry;

  private final I18NString customName;

  /**
   * Matches the operator unit code at the START of a signposted station elevator pathway name
   * ("EL131 elevator to mezzanine") — prefix, not full match, mirroring the itinerary mapper's
   * ElevatorUse code extraction so edge blocking and API reporting agree on the same unit.
   */
  private static final Pattern EQUIPMENT_CODE = Pattern.compile("^((?:EL|ES)\\d+X?)\\b");

  /**
   * The operator's unit code when this edge was built from a signposted station elevator
   * pathway; null for OSM elevators and unsignposted pathways. The join key into the realtime
   * equipment status feed.
   */
  @Nullable
  private final String equipmentCode;

  private ElevatorBoardEdge(Vertex from, ElevatorHopVertex to, I18NString customName) {
    super(from, to);
    this.customName = customName;
    this.equipmentCode = parseEquipmentCode(customName);
    geometry = GeometryUtils.makeLineString(from.getX(), from.getY(), to.getX(), to.getY());
  }

  @Nullable
  private static String parseEquipmentCode(@Nullable I18NString name) {
    if (name == null) {
      return null;
    }
    var matcher = EQUIPMENT_CODE.matcher(name.toString().trim());
    return matcher.find() ? matcher.group(1) : null;
  }

  @Nullable
  public String equipmentCode() {
    return equipmentCode;
  }

  public static ElevatorBoardEdge createElevatorBoardEdge(Vertex from, ElevatorHopVertex to) {
    return connectToGraph(new ElevatorBoardEdge(from, to, null));
  }

  public static ElevatorBoardEdge createElevatorBoardEdge(
    Vertex from,
    ElevatorHopVertex to,
    I18NString customName
  ) {
    return connectToGraph(new ElevatorBoardEdge(from, to, customName));
  }

  @Override
  public State[] traverse(State s0) {
    StateEditor s1 = createEditorForDrivingOrWalking(s0, this);
    if (s1 == null) {
      return State.empty();
    }

    var req = s0.getRequest();

    // A unit the realtime feed reports out of service is untraversable for wheelchair
    // searches — a rider who cannot use stairs must not be routed into a dead elevator. The
    // set is empty for non-wheelchair searches and when status is unknown (stale feed), so
    // this never blocks on missing data.
    if (
      equipmentCode != null &&
      req.wheelchairEnabled() &&
      req.wheelchair().inoperativeEquipment().contains(equipmentCode)
    ) {
      return State.empty();
    }

    long time = req.elevator().boardSlack().toSeconds();
    s1.incrementWeight(req.elevator().boardCost() + req.elevator().reluctance() * time);
    s1.incrementTimeInSeconds(time);

    return s1.makeStateArray();
  }

  @Override
  public I18NString getName() {
    // TODO: i18n
    return customName != null ? customName : new NonLocalizedString("ElevatorBoardEdge");
  }

  /**
   * Since board edges are always called ElevatorBoardEdge, the name is complete bogus but is
   * never included in plans.
   */
  @Override
  public boolean nameIsDerived() {
    return true;
  }

  @Override
  public LineString getGeometry() {
    return geometry;
  }
}
