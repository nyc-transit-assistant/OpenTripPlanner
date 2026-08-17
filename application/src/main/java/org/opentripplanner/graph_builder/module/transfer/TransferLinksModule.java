package org.opentripplanner.graph_builder.module.transfer;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.datastore.api.DataSource;
import org.opentripplanner.graph_builder.issue.api.DataImportIssueStore;
import org.opentripplanner.graph_builder.issue.api.Issue;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.graph_builder.module.transfer.TransferLinksParser.TransferLinkRow;
import org.opentripplanner.transfer.regular.TransferRepository;
import org.opentripplanner.transfer.regular.model.CuratedPathTransfer;
import org.opentripplanner.transit.model.site.RegularStop;
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
    int applied = 0;
    int skipped = 0;
    for (TransferLinkRow row : rows) {
      var fromStops = resolve(row.from());
      var toStops = resolve(row.to());
      if (fromStops.isEmpty() || toStops.isEmpty()) {
        issueStore.add(
          Issue.issue(
            "TransferLinkStopNotFound",
            "Transfer link %s -> %s skipped: %s not found in any loaded feed",
            row.from(),
            row.to(),
            fromStops.isEmpty() ? row.from() : row.to()
          )
        );
        skipped++;
        continue;
      }
      // Name guards catch id REASSIGNMENT: publishers like NJ Transit renumber stops between
      // picks, silently re-pointing a curated link at an unrelated station. Loud skip beats
      // silently wrong transfers.
      String guardViolation = nameGuardViolation(row.from(), row.fromNameGuard());
      if (guardViolation == null) {
        guardViolation = nameGuardViolation(row.to(), row.toNameGuard());
      }
      if (guardViolation != null) {
        issueStore.add(
          Issue.issue(
            "TransferLinkStopNameMismatch",
            "Transfer link %s -> %s skipped: %s",
            row.from(),
            row.to(),
            guardViolation
          )
        );
        skipped++;
        continue;
      }
      for (var from : fromStops) {
        for (var to : toStops) {
          double distance = from.getCoordinate().distanceTo(to.getCoordinate());
          var transfer = new CuratedPathTransfer(
            from,
            to,
            distance,
            row.minTransferTimeSeconds(),
            row.wheelchairMinTransferTimeSeconds(),
            row.wheelchairElevators()
          );
          transferRepository.replaceWalkTransfer(from, to, transfer);
          applied++;
        }
      }
    }
    transferRepository.index();
    LOG.info(
      "Applied {} curated transfers from {} rows ({} rows skipped)",
      applied,
      rows.size(),
      skipped
    );
  }

  /**
   * Returns a description of the violation if the endpoint's resolved name does not contain the
   * guard (case-insensitive), or null when the guard passes or is absent.
   */
  @Nullable
  private String nameGuardViolation(FeedScopedId id, @Nullable String guard) {
    if (guard == null) {
      return null;
    }
    var siteRepository = timetableRepository.getSiteRepository();
    String name = null;
    var stop = siteRepository.getRegularStop(id);
    if (stop != null && stop.getName() != null) {
      name = stop.getName().toString();
    } else {
      var station = siteRepository.getStationById(id);
      if (station != null && station.getName() != null) {
        name = station.getName().toString();
      }
    }
    if (name == null || !name.toLowerCase().contains(guard.toLowerCase())) {
      return "%s resolved to '%s' which does not match name guard '%s'".formatted(id, name, guard);
    }
    return null;
  }

  /**
   * A file endpoint may name a platform-level stop directly, or a station — terminals whose
   * per-gate stop ids churn every pick (Port Authority) are curated by their stable station id
   * and expanded to whatever child stops the current feed carries.
   */
  private List<RegularStop> resolve(FeedScopedId id) {
    var siteRepository = timetableRepository.getSiteRepository();
    var stop = siteRepository.getRegularStop(id);
    if (stop != null) {
      return List.of(stop);
    }
    var station = siteRepository.getStationById(id);
    if (station != null) {
      return station
        .getChildStops()
        .stream()
        .filter(RegularStop.class::isInstance)
        .map(RegularStop.class::cast)
        .toList();
    }
    return List.of();
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
