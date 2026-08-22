package org.opentripplanner.graph_builder.module;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.onebusaway.csv_entities.CsvInputSource;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transit.model.site.Entrance;
import org.opentripplanner.transit.model.site.Pathway;
import org.opentripplanner.transit.model.site.PathwayMode;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.model.site.StationEquipment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@link StationEquipment} registry for one GTFS bundle.
 * <p>
 * Two sources merge, keyed by the operator's unit code ("EL359"):
 * <ul>
 *   <li>Elevator/escalator pathways whose signposted text starts with the code — these yield the
 *       owning station (from the pathway endpoints), any entrance endpoints, and mark the unit
 *       routable.</li>
 *   <li>An optional {@code equipment.txt} extension file in the bundle (columns: equipment_id,
 *       equipment_type, ada, serving, station_name, gtfs_station_id) — this carries the ADA flag
 *       and authoritative serving text, and documents units that are not modeled as pathways.</li>
 * </ul>
 */
public final class StationEquipmentMapper {

  private static final Logger LOG = LoggerFactory.getLogger(StationEquipmentMapper.class);
  private static final String EQUIPMENT_FILE = "equipment.txt";
  private static final Pattern CODE = Pattern.compile("^((?:EL|ES)\\d+X?)\\b");
  private static final String SIGNPOST_SEPARATOR = " — ";

  private StationEquipmentMapper() {}

  public static List<StationEquipment> map(
    String feedId,
    CsvInputSource csvSource,
    Collection<Pathway> pathways
  ) {
    Map<String, SidecarRow> sidecar = readSidecar(csvSource);

    // Fare-gate insertion rewires an entrance's pathways onto an interior gate
    // node; recover the doorway by looking through mode-6 pathways so street
    // units still reference their entrance.
    Map<FeedScopedId, Entrance> entranceBehindGate = new HashMap<>();
    for (Pathway pathway : pathways) {
      if (pathway.getPathwayMode() != PathwayMode.FARE_GATE) {
        continue;
      }
      var from = pathway.getFromStop();
      var to = pathway.getToStop();
      if (from instanceof Entrance entrance && !(to instanceof Entrance)) {
        entranceBehindGate.put(to.getId(), entrance);
      } else if (to instanceof Entrance entrance && !(from instanceof Entrance)) {
        entranceBehindGate.put(from.getId(), entrance);
      }
    }

    Map<String, PathwayFacts> byCode = new LinkedHashMap<>();
    for (Pathway pathway : pathways) {
      var mode = pathway.getPathwayMode();
      if (mode != PathwayMode.ELEVATOR && mode != PathwayMode.ESCALATOR) {
        continue;
      }
      String signposted = pathway.getSignpostedAs();
      if (signposted == null) {
        continue;
      }
      var matcher = CODE.matcher(signposted);
      if (!matcher.find()) {
        continue;
      }
      var facts = byCode.computeIfAbsent(matcher.group(1), c -> new PathwayFacts());
      facts.type = mode == PathwayMode.ELEVATOR
        ? StationEquipment.EquipmentType.ELEVATOR
        : StationEquipment.EquipmentType.ESCALATOR;
      int sep = signposted.indexOf(SIGNPOST_SEPARATOR);
      if (sep > 0 && facts.serving == null) {
        facts.serving = signposted.substring(sep + SIGNPOST_SEPARATOR.length());
      }
      for (var element : List.of(pathway.getFromStop(), pathway.getToStop())) {
        Station station = element.getParentStation();
        if (station != null) {
          facts.stationIds.add(station.getId());
        }
        if (element instanceof Entrance entrance) {
          facts.entranceIds.add(entrance.getId());
        } else {
          Entrance behindGate = entranceBehindGate.get(element.getId());
          if (behindGate != null) {
            facts.entranceIds.add(behindGate.getId());
          }
        }
      }
    }

    List<StationEquipment> out = new ArrayList<>();
    for (var entry : byCode.entrySet()) {
      String code = entry.getKey();
      PathwayFacts facts = entry.getValue();
      SidecarRow row = sidecar.remove(code);
      // Cross-station complex units (a host mezzanine elevator landing on a
      // member's platform) keep insertion order: the first endpoint's station
      // is the mezzanine/entrance side — the unit's natural owner.
      FeedScopedId stationId = !facts.stationIds.isEmpty()
        ? facts.stationIds.iterator().next()
        : row != null
          ? row.stationId(feedId)
          : null;
      out.add(
        new StationEquipment(
          new FeedScopedId(feedId, code),
          facts.type,
          row != null && row.serving != null ? row.serving : facts.serving,
          row != null ? row.ada : null,
          stationId,
          List.copyOf(facts.entranceIds),
          true
        )
      );
    }
    int routable = out.size();
    for (var entry : sidecar.entrySet()) {
      SidecarRow row = entry.getValue();
      if (row.type == null) {
        continue;
      }
      out.add(
        new StationEquipment(
          new FeedScopedId(feedId, entry.getKey()),
          row.type,
          row.serving,
          row.ada,
          row.stationId(feedId),
          List.of(),
          false
        )
      );
    }
    if (!out.isEmpty()) {
      LOG.info(
        "Registered {} station equipment units for feed {} ({} routable via pathways)",
        out.size(),
        feedId,
        routable
      );
    }
    return out;
  }

  private static Map<String, SidecarRow> readSidecar(CsvInputSource source) {
    Map<String, SidecarRow> rows = new HashMap<>();
    try {
      if (!source.hasResource(EQUIPMENT_FILE)) {
        return rows;
      }
      try (
        var reader = new BufferedReader(
          new InputStreamReader(source.getResource(EQUIPMENT_FILE), StandardCharsets.UTF_8)
        )
      ) {
        List<List<String>> records = parseCsv(reader);
        if (records.isEmpty()) {
          return rows;
        }
        List<String> header = records.get(0);
        for (var record : records.subList(1, records.size())) {
          String code = col(record, header, "equipment_id");
          if (code == null || code.isBlank()) {
            continue;
          }
          var row = new SidecarRow();
          String type = col(record, header, "equipment_type");
          row.type = "elevator".equals(type)
            ? StationEquipment.EquipmentType.ELEVATOR
            : "escalator".equals(type)
              ? StationEquipment.EquipmentType.ESCALATOR
              : null;
          String ada = col(record, header, "ada");
          row.ada = "1".equals(ada) ? Boolean.TRUE : "0".equals(ada) ? Boolean.FALSE : null;
          row.serving = blankToNull(col(record, header, "serving"));
          row.gtfsStationId = blankToNull(col(record, header, "gtfs_station_id"));
          rows.put(code, row);
        }
      }
    } catch (IOException e) {
      LOG.warn("Could not read {}: {}", EQUIPMENT_FILE, e.toString());
    }
    return rows;
  }

  @Nullable
  private static String col(List<String> record, List<String> header, String name) {
    int i = header.indexOf(name);
    return i >= 0 && i < record.size() ? record.get(i) : null;
  }

  @Nullable
  private static String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /** Minimal RFC 4180 parser — quoted fields with embedded commas/quotes/newlines. */
  static List<List<String>> parseCsv(BufferedReader reader) throws IOException {
    List<List<String>> records = new ArrayList<>();
    List<String> record = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    int c;
    while ((c = reader.read()) != -1) {
      char ch = (char) c;
      if (inQuotes) {
        if (ch == '"') {
          int next = reader.read();
          if (next == '"') {
            field.append('"');
          } else {
            inQuotes = false;
            if (next == -1) {
              break;
            }
            ch = (char) next;
            if (ch == ',') {
              record.add(field.toString());
              field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
              endRecord(records, record, field);
            }
          }
        } else {
          field.append(ch);
        }
      } else if (ch == '"') {
        inQuotes = true;
      } else if (ch == ',') {
        record.add(field.toString());
        field.setLength(0);
      } else if (ch == '\n') {
        endRecord(records, record, field);
      } else if (ch != '\r') {
        field.append(ch);
      }
    }
    if (field.length() > 0 || !record.isEmpty()) {
      endRecord(records, record, field);
    }
    return records;
  }

  private static void endRecord(
    List<List<String>> records,
    List<String> record,
    StringBuilder field
  ) {
    if (field.length() == 0 && record.isEmpty()) {
      return;
    }
    record.add(field.toString());
    records.add(new ArrayList<>(record));
    record.clear();
    field.setLength(0);
  }

  private static final class PathwayFacts {

    StationEquipment.EquipmentType type;

    @Nullable
    String serving;

    Set<FeedScopedId> stationIds = new LinkedHashSet<>();
    Set<FeedScopedId> entranceIds = new LinkedHashSet<>();
  }

  private static final class SidecarRow {

    @Nullable
    StationEquipment.EquipmentType type;

    @Nullable
    Boolean ada;

    @Nullable
    String serving;

    @Nullable
    String gtfsStationId;

    @Nullable
    FeedScopedId stationId(String feedId) {
      return gtfsStationId == null ? null : new FeedScopedId(feedId, gtfsStationId);
    }
  }
}
