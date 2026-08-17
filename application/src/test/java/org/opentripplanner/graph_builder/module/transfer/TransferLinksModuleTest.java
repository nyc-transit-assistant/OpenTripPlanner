package org.opentripplanner.graph_builder.module.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.HashMultimap;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.graph_builder.issue.service.DefaultDataImportIssueStore;
import org.opentripplanner.street.model.StreetMode;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.transfer.regular.internal.DefaultTransferRepository;
import org.opentripplanner.transfer.regular.internal.TransferIndex;
import org.opentripplanner.transfer.regular.model.CuratedPathTransfer;
import org.opentripplanner.transfer.regular.model.PathTransfer;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.TimetableRepository;

class TransferLinksModuleTest {

  private static final TimetableRepositoryForTest TEST_MODEL = TimetableRepositoryForTest.of();
  private static final RegularStop STOP_A = TEST_MODEL.stop("A", 40.75, -73.99).build();
  private static final RegularStop STOP_B = TEST_MODEL.stop("B", 40.751, -73.991).build();

  private static String csv(String rows) {
    return (
      "from_stop_id,to_stop_id,transfer_type,min_transfer_time,wheelchair_min_transfer_time\n" +
      rows
    );
  }

  private TimetableRepository timetableRepository() {
    return new TimetableRepository(
      TEST_MODEL.siteRepositoryBuilder().withRegularStop(STOP_A).withRegularStop(STOP_B).build()
    );
  }

  @Test
  void curatedLinkReplacesStreetWalkTransfer() {
    var transferRepository = new DefaultTransferRepository(new TransferIndex());
    var streetTransfer = new PathTransfer(
      STOP_A,
      STOP_B,
      900,
      null,
      EnumSet.of(StreetMode.WALK, StreetMode.BIKE)
    );
    var byStop = HashMultimap.<StopLocation, PathTransfer>create();
    byStop.put(STOP_A, streetTransfer);
    transferRepository.addAllTransfersByStops(byStop);

    var module = new TransferLinksModule(
      timetableRepository(),
      transferRepository,
      new DefaultDataImportIssueStore(),
      TransferLinksParser.parse(
        new ByteArrayInputStream(csv("F:A,F:B,2,240,360\n").getBytes(StandardCharsets.UTF_8))
      )
    );
    module.buildGraph();

    var transfers = transferRepository.findTransfersByStop(STOP_A);
    assertEquals(2, transfers.size());

    var curated = transfers
      .stream()
      .filter(CuratedPathTransfer.class::isInstance)
      .map(CuratedPathTransfer.class::cast)
      .findFirst()
      .orElseThrow();
    assertEquals(240, curated.durationSeconds());
    assertTrue(curated.wheelchairAccessible());

    // The street transfer lost WALK but kept BIKE.
    var street = transfers
      .stream()
      .filter(t -> !(t instanceof CuratedPathTransfer))
      .findFirst()
      .orElseThrow();
    assertFalse(street.allowsMode(StreetMode.WALK));
    assertTrue(street.allowsMode(StreetMode.BIKE));
  }

  @Test
  void raptorTransferUsesFixedDurations() {
    var curated = new CuratedPathTransfer(STOP_A, STOP_B, 150, 240, 360);

    var walking = curated.asRaptorTransfer(StreetSearchRequest.of().build()).orElseThrow();
    assertEquals(240, walking.durationInSeconds());

    var wheelchair = curated
      .asRaptorTransfer(StreetSearchRequest.of().withWheelchairEnabled(true).build())
      .orElseThrow();
    assertEquals(360, wheelchair.durationInSeconds());
  }

  @Test
  void inaccessibleLinkIsOmittedFromWheelchairSearches() {
    var curated = new CuratedPathTransfer(
      STOP_A,
      STOP_B,
      150,
      240,
      CuratedPathTransfer.NOT_WHEELCHAIR_ACCESSIBLE
    );
    assertFalse(curated.wheelchairAccessible());
    assertTrue(curated.asRaptorTransfer(StreetSearchRequest.of().build()).isPresent());
    assertTrue(
      curated
        .asRaptorTransfer(StreetSearchRequest.of().withWheelchairEnabled(true).build())
        .isEmpty()
    );
  }

  @Test
  void unknownStopIsSkippedWithIssue() {
    var transferRepository = new DefaultTransferRepository(new TransferIndex());
    var issueStore = new DefaultDataImportIssueStore();
    var module = new TransferLinksModule(
      timetableRepository(),
      transferRepository,
      issueStore,
      List.of(
        new TransferLinksParser.TransferLinkRow(
          new FeedScopedId("F", "A"),
          new FeedScopedId("nope", "missing"),
          240,
          CuratedPathTransfer.NOT_WHEELCHAIR_ACCESSIBLE
        )
      )
    );
    module.buildGraph();

    assertTrue(transferRepository.findTransfersByStop(STOP_A).isEmpty());
    assertEquals(1, issueStore.listIssues().size());
  }

  @Test
  void stationEndpointExpandsToChildStops() {
    var station = TEST_MODEL.station("STN-PABT").build();
    var gate1 = TEST_MODEL.stop("gate-1", 40.757, -73.99).withParentStation(station).build();
    var gate2 = TEST_MODEL.stop("gate-2", 40.7571, -73.9901).withParentStation(station).build();
    var timetableRepository = new TimetableRepository(
      TEST_MODEL.siteRepositoryBuilder()
        .withStation(station)
        .withRegularStop(gate1)
        .withRegularStop(gate2)
        .withRegularStop(STOP_A)
        .build()
    );
    var transferRepository = new DefaultTransferRepository(new TransferIndex());
    var module = new TransferLinksModule(
      timetableRepository,
      transferRepository,
      new DefaultDataImportIssueStore(),
      TransferLinksParser.parse(
        new ByteArrayInputStream(csv("F:STN-PABT,F:A,2,240,\n").getBytes(StandardCharsets.UTF_8))
      )
    );
    module.buildGraph();

    // one curated transfer per child gate stop
    assertEquals(1, transferRepository.findTransfersByStop(gate1).size());
    assertEquals(1, transferRepository.findTransfersByStop(gate2).size());
    assertTrue(
      transferRepository
        .findTransfersByStop(gate1)
        .stream()
        .allMatch(t -> t instanceof CuratedPathTransfer && t.to.equals(STOP_A))
    );
  }

  @Test
  void elevatorOutageGatesWheelchairTransfer() {
    var curated = new CuratedPathTransfer(STOP_A, STOP_B, 150, 240, 360, List.of("EL290", "EL291"));

    // Walking search unaffected by outages
    var walking = curated.asRaptorTransfer(
      StreetSearchRequest.of()
        .withWheelchair(w -> w.withInoperativeEquipment(java.util.Set.of("EL290")))
        .build()
    );
    assertEquals(240, walking.orElseThrow().durationInSeconds());

    // Wheelchair search with all elevators in service: uses wheelchair time
    var allWorking = curated.asRaptorTransfer(
      StreetSearchRequest.of().withWheelchairEnabled(true).build()
    );
    assertEquals(360, allWorking.orElseThrow().durationInSeconds());

    // Any required unit out: transfer omitted from wheelchair searches
    var gated = curated.asRaptorTransfer(
      StreetSearchRequest.of()
        .withWheelchairEnabled(true)
        .withWheelchair(w -> w.withInoperativeEquipment(java.util.Set.of("EL291")))
        .build()
    );
    assertTrue(gated.isEmpty());

    // Unrelated outage does not gate
    var unrelated = curated.asRaptorTransfer(
      StreetSearchRequest.of()
        .withWheelchairEnabled(true)
        .withWheelchair(w -> w.withInoperativeEquipment(java.util.Set.of("EL999")))
        .build()
    );
    assertEquals(360, unrelated.orElseThrow().durationInSeconds());
  }

  @Test
  void parserReadsWheelchairElevators() {
    var rows = TransferLinksParser.parse(
      new ByteArrayInputStream(
        ("from_stop_id,to_stop_id,transfer_type,min_transfer_time,wheelchair_min_transfer_time,wheelchair_elevators\n" +
          "F:A,F:B,2,240,360,EL290|EL291\n" +
          "F:B,F:A,2,240,360,\n").getBytes(StandardCharsets.UTF_8)
      )
    );
    assertEquals(List.of("EL290", "EL291"), rows.get(0).wheelchairElevators());
    assertTrue(rows.get(1).wheelchairElevators().isEmpty());
  }

  @Test
  void nameGuardRejectsReassignedId() {
    var transferRepository = new DefaultTransferRepository(new TransferIndex());
    var issueStore = new DefaultDataImportIssueStore();
    var module = new TransferLinksModule(
      timetableRepository(),
      transferRepository,
      issueStore,
      TransferLinksParser.parse(
        new ByteArrayInputStream(
          ("from_stop_id,to_stop_id,transfer_type,min_transfer_time,wheelchair_min_transfer_time,from_stop_name,to_stop_name\n" +
            // guard matches (stop A is named "A") -> applied
            "F:A,F:B,2,240,,A,B\n" +
            // guard mismatch: id F:A exists but is NOT named "Penn Station" -> skipped
            "F:A,F:B,2,240,,Penn Station,\n").getBytes(StandardCharsets.UTF_8)
        )
      )
    );
    module.buildGraph();

    assertEquals(1, transferRepository.findTransfersByStop(STOP_A).size());
    assertEquals(1, issueStore.listIssues().size());
    assertEquals("TransferLinkStopNameMismatch", issueStore.listIssues().get(0).getType());
  }

  @Test
  void parserRejectsUnsupportedTransferType() {
    assertThrows(IllegalArgumentException.class, () ->
      TransferLinksParser.parse(
        new ByteArrayInputStream(csv("F:A,F:B,0,240,\n").getBytes(StandardCharsets.UTF_8))
      )
    );
  }
}
