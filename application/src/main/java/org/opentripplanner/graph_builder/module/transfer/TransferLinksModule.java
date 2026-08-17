package org.opentripplanner.graph_builder.module.transfer;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.opentripplanner.datastore.api.DataSource;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.issue.api.Issue;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.graph_builder.module.transfer.TransferLinksParser.TransferLinkRow;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.model.CuratedPathTransfer;
import org.opentripplanner.transit.service.TimetableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Injects curated transfer links — typically cross-feed station-complex transfers no single GTFS
 * dataset can express — into the regular transfer model. Runs after {@link DirectTransferGenerator}
 * so a curated link REPLACES whatever walk transfer the street network produced for that stop pair:
 * street-generated paths through complexes are wrong in both directions (surface detours around
 * paid areas overestimate, 2D shortcuts through walls underestimate), and the curator's signposted
 * time is authoritative. Other modes' transfers (bike, car) for the pair are preserved.
 */
public class TransferLinksModule implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(TransferLinksModule.class);

  private final TimetableRepository timetableRepository;
  private final TransferRepository transferRepository;
  private final DataImportIssueStore issueStore;
  private final List<TransferLinkRow> rows;

  public TransferLinksModule(
    TimetableRepository timetableRepository,
    TransferRepository transferRepository,
    DataImportIssueStore issueStore,
    List<TransferLinkRow> rows
  ) {
    this.timetableRepository = Objects.requireNonNull(timetableRepository);
    this.transferRepository = Objects.requireNonNull(transferRepository);
    this.issueStore = Objects.requireNonNull(issueStore);
    this.rows = Objects.requireNonNull(rows);
  }

  @Override
  public void buildGraph() {
    var siteRepository = timetableRepository.getSiteRepository();
    int applied = 0;
    for (TransferLinkRow row : rows) {
      var from = siteRepository.getRegularStop(row.from());
      var to = siteRepository.getRegularStop(row.to());
      if (from == null || to == null) {
        issueStore.add(
          Issue.issue(
            "TransferLinkStopNotFound",
            "Transfer link %s -> %s skipped: %s not found in any loaded feed",
            row.from(),
            row.to(),
            from == null ? row.from() : row.to()
          )
        );
        continue;
      }
      double distance = from.getCoordinate().distanceTo(to.getCoordinate());
      var transfer = new CuratedPathTransfer(
        from,
        to,
        distance,
        row.minTransferTimeSeconds(),
        row.wheelchairMinTransferTimeSeconds()
      );
      transferRepository.replaceWalkTransfer(from, to, transfer);
      applied++;
    }
    transferRepository.index();
    LOG.info("Applied {} curated transfer links ({} rows in file)", applied, rows.size());
  }

  public static TransferLinksModule of(
    TimetableRepository timetableRepository,
    TransferRepository transferRepository,
    DataImportIssueStore issueStore,
    DataSource ds
  ) {
    LOG.info("Reading transfer links from '{}'", ds);
    try (var inputStream = ds.asInputStream()) {
      var rows = TransferLinksParser.parse(inputStream);
      return new TransferLinksModule(timetableRepository, transferRepository, issueStore, rows);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
