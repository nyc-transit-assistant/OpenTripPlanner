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
  void parserRejectsUnsupportedTransferType() {
    assertThrows(IllegalArgumentException.class, () ->
      TransferLinksParser.parse(
        new ByteArrayInputStream(csv("F:A,F:B,0,240,\n").getBytes(StandardCharsets.UTF_8))
      )
    );
  }
}
