package org.opentripplanner.graph_builder.module;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.onebusaway.csv_entities.CsvInputSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the MTA railroads' non-standard per-train {@code peak_offpeak} column from a bundle's
 * trips.txt. The flag is authoritative for peak fares (the published clock rules mis-classify a
 * few dozen weekday trips), and the vendored OneBusAway model no longer carries the MTA
 * extension fields, so the column is read from the raw CSV — the same pattern as the
 * equipment.txt sidecar in {@link StationEquipmentMapper}.
 */
public final class PeakTripsReader {

  private static final Logger LOG = LoggerFactory.getLogger(PeakTripsReader.class);
  private static final String TRIPS_FILE = "trips.txt";

  private PeakTripsReader() {}

  /** Trip ids (unscoped) flagged {@code peak_offpeak=1}; empty when the column is absent. */
  public static Set<String> readPeakTripIds(CsvInputSource source) {
    Set<String> peak = new HashSet<>();
    try {
      if (!source.hasResource(TRIPS_FILE)) {
        return peak;
      }
      try (
        var reader = new BufferedReader(
          new InputStreamReader(source.getResource(TRIPS_FILE), StandardCharsets.UTF_8)
        )
      ) {
        List<List<String>> records = StationEquipmentMapper.parseCsv(reader);
        if (records.isEmpty()) {
          return peak;
        }
        List<String> header = records.get(0);
        int tripIdIdx = header.indexOf("trip_id");
        int peakIdx = header.indexOf("peak_offpeak");
        if (tripIdIdx < 0 || peakIdx < 0) {
          return peak;
        }
        for (var record : records.subList(1, records.size())) {
          if (
            peakIdx < record.size() && tripIdIdx < record.size() && "1".equals(record.get(peakIdx))
          ) {
            peak.add(record.get(tripIdIdx));
          }
        }
      }
    } catch (IOException e) {
      LOG.warn("Could not read {} for peak flags: {}", TRIPS_FILE, e.toString());
    }
    return peak;
  }
}
