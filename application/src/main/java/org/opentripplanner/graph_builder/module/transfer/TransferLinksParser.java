package org.opentripplanner.graph_builder.module.transfer;

import com.csvreader.CsvReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.transfer.regular.model.CuratedPathTransfer;

/**
 * Parses the transfer-links file: a CSV shaped like a GTFS transfers.txt, except the stop ids are
 * feed-scoped ({@code feedId:stopId}) so a row can span datasets — the cross-feed case GTFS itself
 * cannot express. Columns:
 *
 * <pre>
 * from_stop_id,to_stop_id,transfer_type,min_transfer_time,wheelchair_min_transfer_time
 * mta-subway:127,path:26734,2,240,360
 * </pre>
 *
 * {@code transfer_type} must be 2 (minimum-time transfer, the only supported type).
 * {@code wheelchair_min_transfer_time} is the extension column: empty means the transfer is not
 * wheelchair-accessible. Rows are directional; curate both directions explicitly.
 * <p>
 * Optional {@code wheelchair_elevators} is a pipe-separated list of operator elevator unit
 * codes (e.g. {@code EL290|EL291}) the wheelchair path requires — every listed unit must be in
 * service or the transfer is omitted from wheelchair searches. List only single points of
 * failure on the signed accessible route, not redundant parallel elevators.
 * <p>
 * Optional {@code from_stop_name}/{@code to_stop_name} columns are name guards: publishers like
 * NJ Transit REASSIGN numeric stop ids between picks, which would silently re-point a curated
 * link at a different station. When present, the resolved stop or station's name must contain
 * the guard (case-insensitive) or the row is skipped with an issue.
 */
public class TransferLinksParser {

  public record TransferLinkRow(
    FeedScopedId from,
    FeedScopedId to,
    int minTransferTimeSeconds,
    int wheelchairMinTransferTimeSeconds,
    @Nullable String fromNameGuard,
    @Nullable String toNameGuard,
    List<String> wheelchairElevators
  ) {
    public TransferLinkRow(
      FeedScopedId from,
      FeedScopedId to,
      int minTransferTimeSeconds,
      int wheelchairMinTransferTimeSeconds
    ) {
      this(
        from,
        to,
        minTransferTimeSeconds,
        wheelchairMinTransferTimeSeconds,
        null,
        null,
        List.of()
      );
    }
  }

  public static List<TransferLinkRow> parse(InputStream is) {
    try {
      var reader = new CsvReader(is, StandardCharsets.UTF_8);
      reader.setDelimiter(',');
      reader.readHeaders();

      var rows = new ArrayList<TransferLinkRow>();
      while (reader.readRecord()) {
        var from = FeedScopedId.parseStrict(reader.get("from_stop_id"));
        var to = FeedScopedId.parseStrict(reader.get("to_stop_id"));
        var transferType = reader.get("transfer_type").trim();
        if (!"2".equals(transferType)) {
          throw new IllegalArgumentException(
            "Unsupported transfer_type '%s' for %s -> %s: only 2 (minimum-time) is supported".formatted(
              transferType,
              from,
              to
            )
          );
        }
        int minTransferTime = Integer.parseInt(reader.get("min_transfer_time").trim());
        if (minTransferTime <= 0) {
          throw new IllegalArgumentException(
            "min_transfer_time must be positive for %s -> %s".formatted(from, to)
          );
        }
        var wheelchairRaw = reader.get("wheelchair_min_transfer_time");
        int wheelchairTime = (wheelchairRaw == null || wheelchairRaw.isBlank())
          ? CuratedPathTransfer.NOT_WHEELCHAIR_ACCESSIBLE
          : Integer.parseInt(wheelchairRaw.trim());
        var elevatorsRaw = blankToNull(reader.get("wheelchair_elevators"));
        List<String> elevators = elevatorsRaw == null
          ? List.of()
          : Arrays.stream(elevatorsRaw.split("\\|"))
              .map(String::trim)
              .filter(e -> !e.isEmpty())
              .toList();
        rows.add(
          new TransferLinkRow(
            from,
            to,
            minTransferTime,
            wheelchairTime,
            blankToNull(reader.get("from_stop_name")),
            blankToNull(reader.get("to_stop_name")),
            elevators
          )
        );
      }
      return rows;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Nullable
  private static String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
