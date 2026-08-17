package org.opentripplanner.graph_builder.module.transfer;

import com.csvreader.CsvReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 */
public class TransferLinksParser {

  public record TransferLinkRow(
    FeedScopedId from,
    FeedScopedId to,
    int minTransferTimeSeconds,
    int wheelchairMinTransferTimeSeconds
  ) {}

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
        rows.add(new TransferLinkRow(from, to, minTransferTime, wheelchairTime));
      }
      return rows;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
