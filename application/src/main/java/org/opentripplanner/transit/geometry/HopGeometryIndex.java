package org.opentripplanner.transit.geometry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.street.geometry.GeometryUtils;
import org.opentripplanner.transit.model.site.StopLocation;

/**
 * An index of hop geometries harvested from stop sequences that have one, used to synthesize
 * geometry for sequences that don't.
 * <p>
 * A GTFS trip without a {@code shape_id} leaves its pattern with no geometry at all, and the API
 * then falls back to straight stop-to-stop lines. Where the same trackage is described by some
 * other pattern — a different service variant, the express version of a local line, or the
 * opposite direction — the real alignment is already present in the feed and can be reused.
 * <p>
 * Hops are resolved in descending order of confidence:
 * <ol>
 *   <li><b>adjacent</b> — some indexed sequence runs directly from A to B. Exact.</li>
 *   <li><b>stitched</b> — some sequence passes through A and later B; the intervening hops are
 *       concatenated. Exact if the skipping service follows the same alignment, which holds for
 *       express/local pairs sharing a right of way but is not guaranteed in general.</li>
 *   <li><b>reversed</b> — the opposite direction between the same two stations is known and is
 *       reversed. An approximation: opposing tracks are usually parallel but not identical, and
 *       this is wrong where the directions genuinely diverge.</li>
 * </ol>
 * Anything still unresolved falls back to a straight line, matching what the API would have
 * produced anyway. Callers get per-tier counts back so synthesized geometry can be reported
 * rather than silently passed off as surveyed data.
 * <p>
 * Only hops with intermediate points are indexed: a two-point hop carries no more information
 * than the straight-line fallback, and indexing it would mask a better source elsewhere.
 */
public class HopGeometryIndex {

  private record Key(FeedScopedId from, FeedScopedId to) {}

  /** A stop sequence with one geometry per hop, retained for stitching. */
  private record Source(List<StopLocation> stops, List<LineString> hops) {}

  private final List<Source> sources = new ArrayList<>();
  private final Map<Key, LineString> byStop = new HashMap<>();
  private final Map<Key, LineString> byStation = new HashMap<>();

  /**
   * Harvest a stop sequence. {@code hopGeometries} must hold one entry per hop, i.e. one fewer
   * than {@code stops}; anything else is ignored, as is a null geometry list (a sequence with no
   * shape of its own has nothing to contribute).
   */
  public void add(List<StopLocation> stops, @Nullable List<LineString> hopGeometries) {
    if (stops == null || hopGeometries == null || stops.size() - 1 != hopGeometries.size()) {
      return;
    }
    sources.add(new Source(stops, hopGeometries));
    for (int i = 0; i < hopGeometries.size(); i++) {
      var geometry = hopGeometries.get(i);
      if (!isDetailed(geometry)) {
        continue;
      }
      var from = stops.get(i);
      var to = stops.get(i + 1);
      byStop.putIfAbsent(new Key(from.getId(), to.getId()), geometry);
      var stationKey = stationKey(from, to);
      if (stationKey != null) {
        byStation.putIfAbsent(stationKey, geometry);
      }
    }
  }

  public boolean isEmpty() {
    return sources.isEmpty();
  }

  /**
   * Build one geometry per hop of {@code stops}, in descending order of confidence. The returned
   * list is always aligned to the stop sequence; unresolved hops hold a straight line.
   */
  public Resolution resolve(List<StopLocation> stops) {
    int hopCount = stops.size() - 1;
    var geometries = new ArrayList<LineString>(Math.max(hopCount, 0));
    int adjacent = 0;
    int stitched = 0;
    int reversed = 0;
    int straight = 0;
    for (int i = 0; i < hopCount; i++) {
      var from = stops.get(i);
      var to = stops.get(i + 1);
      LineString geometry = byStop.get(new Key(from.getId(), to.getId()));
      if (geometry != null) {
        adjacent++;
      } else if ((geometry = stitch(from, to)) != null) {
        stitched++;
      } else if ((geometry = reverseOfOppositeDirection(from, to)) != null) {
        reversed++;
      } else {
        geometry = straightLine(from, to);
        straight++;
      }
      geometries.add(geometry);
    }
    return new Resolution(geometries, adjacent, stitched, reversed, straight);
  }

  /**
   * Concatenate the hops of an indexed sequence that passes through {@code from} and later
   * {@code to}. Handles skip-stop and express services, whose stop pairs never appear adjacent
   * anywhere.
   */
  @Nullable
  private LineString stitch(StopLocation from, StopLocation to) {
    for (var source : sources) {
      int start = indexOf(source.stops(), from, 0);
      if (start < 0) {
        continue;
      }
      int end = indexOf(source.stops(), to, start + 1);
      if (end < 0) {
        continue;
      }
      var parts = new ArrayList<LineString>(end - start);
      for (int i = start; i < end; i++) {
        var hop = source.hops().get(i);
        if (hop == null) {
          parts.clear();
          break;
        }
        parts.add(hop);
      }
      if (parts.isEmpty()) {
        continue;
      }
      var joined = GeometryUtils.concatenateLineStrings(parts);
      if (isDetailed(joined)) {
        return joined;
      }
    }
    return null;
  }

  /**
   * Reuse the geometry of the same trackage travelled the other way, reversed. Matching is by
   * station rather than stop so that the opposite platform — a distinct stop in feeds like NYCT,
   * where each direction has its own stop id — is recognised as the same place.
   */
  @Nullable
  private LineString reverseOfOppositeDirection(StopLocation from, StopLocation to) {
    var key = stationKey(to, from);
    if (key == null) {
      return null;
    }
    var geometry = byStation.get(key);
    return geometry == null ? null : geometry.reverse();
  }

  private static int indexOf(List<StopLocation> stops, StopLocation stop, int fromIndex) {
    for (int i = fromIndex; i < stops.size(); i++) {
      if (stops.get(i).getId().equals(stop.getId())) {
        return i;
      }
    }
    return -1;
  }

  @Nullable
  private static Key stationKey(StopLocation from, StopLocation to) {
    var fromStation = from.getParentStation();
    var toStation = to.getParentStation();
    if (fromStation == null || toStation == null) {
      return null;
    }
    return new Key(fromStation.getId(), toStation.getId());
  }

  private static boolean isDetailed(@Nullable LineString geometry) {
    return geometry != null && geometry.getNumPoints() > 2;
  }

  private static LineString straightLine(StopLocation from, StopLocation to) {
    return GeometryUtils.getGeometryFactory().createLineString(
      new Coordinate[] {
        new Coordinate(from.getLon(), from.getLat()),
        new Coordinate(to.getLon(), to.getLat()),
      }
    );
  }

  /**
   * The geometries for a stop sequence together with how each hop was obtained.
   *
   * @param geometries one entry per hop, aligned to the stop sequence
   * @param adjacent hops taken verbatim from a sequence running directly between the two stops
   * @param stitched hops concatenated from a sequence that skips no stops between them
   * @param reversed hops taken from the opposite direction and reversed
   * @param straight hops with no source at all, left as a straight line
   */
  public record Resolution(
    List<LineString> geometries,
    int adjacent,
    int stitched,
    int reversed,
    int straight
  ) {
    /** Hops that came from real geometry rather than a straight-line fallback. */
    public int borrowed() {
      return adjacent + stitched + reversed;
    }

    /** Hops whose geometry is an approximation rather than a copy of the same trackage. */
    public int approximated() {
      return stitched + reversed;
    }

    public boolean hasAny() {
      return borrowed() > 0;
    }
  }
}
