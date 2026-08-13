package org.opentripplanner.graph_builder.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.onebusaway.csv_entities.CsvInputSource;
import org.opentripplanner.transit.model.site.StationEquipment;

class StationEquipmentMapperTest {

  private static final String SIDECAR = """
    equipment_id,equipment_type,ada,serving,station_name,gtfs_station_id
    EL359,elevator,1,"Bedford Av and N 7 St (NE corner) to mezzanine, both directions",Bedford Av,L08
    ES101,escalator,0,mezzanine to platform,Somewhere,
    EL999,elevator,,,Unknown St,
    """;

  private static CsvInputSource source(String content) {
    return new CsvInputSource() {
      @Override
      public boolean hasResource(String s) {
        return "equipment.txt".equals(s);
      }

      @Override
      public InputStream getResource(String s) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
      }

      @Override
      public void close() {}
    };
  }

  @Test
  void sidecarOnlyUnitsAreRegisteredAsUnroutable() {
    List<StationEquipment> out = StationEquipmentMapper.map("f", source(SIDECAR), List.of());
    assertEquals(3, out.size());

    var el359 = out
      .stream()
      .filter(e -> e.code().equals("EL359"))
      .findFirst()
      .orElseThrow();
    assertEquals(StationEquipment.EquipmentType.ELEVATOR, el359.type());
    assertEquals(Boolean.TRUE, el359.ada());
    assertTrue(el359.serving().contains("mezzanine, both directions"));
    assertEquals("f", el359.stationId().getFeedId());
    assertEquals("L08", el359.stationId().getId());
    assertFalse(el359.routable());

    var es101 = out
      .stream()
      .filter(e -> e.code().equals("ES101"))
      .findFirst()
      .orElseThrow();
    assertEquals(StationEquipment.EquipmentType.ESCALATOR, es101.type());
    assertEquals(Boolean.FALSE, es101.ada());
    assertNull(es101.stationId());

    var el999 = out
      .stream()
      .filter(e -> e.code().equals("EL999"))
      .findFirst()
      .orElseThrow();
    assertNull(el999.ada());
    assertNull(el999.serving());
  }

  @Test
  void missingSidecarYieldsNothingWithoutPathways() {
    var empty = new CsvInputSource() {
      @Override
      public boolean hasResource(String s) {
        return false;
      }

      @Override
      public InputStream getResource(String s) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void close() {}
    };
    assertTrue(StationEquipmentMapper.map("f", empty, List.of()).isEmpty());
  }
}
