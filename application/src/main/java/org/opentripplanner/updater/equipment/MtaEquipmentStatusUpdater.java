package org.opentripplanner.updater.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import org.opentripplanner.framework.io.OtpHttpClient;
import org.opentripplanner.framework.io.OtpHttpClientFactory;
import org.opentripplanner.service.equipmentstatus.EquipmentStatusRepository;
import org.opentripplanner.service.equipmentstatus.model.EquipmentOutage;
import org.opentripplanner.service.equipmentstatus.model.PlannedEquipmentOutage;
import org.opentripplanner.updater.spi.PollingGraphUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the MTA elevator/escalator current-outages feed (the {@code nyct_ene.json} shape) and
 * replaces the {@link EquipmentStatusRepository} snapshot on every successful poll.
 * <p>
 * Semantics learned from surveying the MTA's three E&E feeds:
 * <ul>
 *   <li>This outages feed is authoritative for live status. The equipment registry's
 *       {@code isactive} flag lags badly (roughly half of live outages missing) — never use it
 *       for realtime.</li>
 *   <li>The feed mixes future planned outages into the current list, flagged with
 *       {@code isupcomingoutage=Y} (the separate upcoming feed is exactly this subset). A unit
 *       is only OUT when the flag is N <em>and</em> its outage start is in the past; upcoming
 *       rows are stored as planned windows instead.</li>
 *   <li>Timestamps are US-formatted local times in America/New_York.</li>
 * </ul>
 * A failed poll keeps the previous snapshot; the repository's staleness cutoff degrades answers
 * to unknown if polls keep failing longer than {@code staleAfter}.
 */
public class MtaEquipmentStatusUpdater extends PollingGraphUpdater {

  private static final Logger LOG = LoggerFactory.getLogger(MtaEquipmentStatusUpdater.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId FEED_ZONE = ZoneId.of("America/New_York");
  private static final DateTimeFormatter FEED_TIME = DateTimeFormatter.ofPattern(
    "MM/dd/yyyy hh:mm:ss a",
    Locale.US
  );

  private final MtaEquipmentStatusUpdaterParameters parameters;
  private final EquipmentStatusRepository repository;
  private final OtpHttpClient otpHttpClient;

  public MtaEquipmentStatusUpdater(
    MtaEquipmentStatusUpdaterParameters parameters,
    EquipmentStatusRepository repository
  ) {
    super(parameters);
    this.parameters = parameters;
    this.repository = repository;
    this.otpHttpClient = new OtpHttpClientFactory().create(LOG);
  }

  @Override
  protected void runPolling() {
    var rows = otpHttpClient.getAndMap(
      URI.create(parameters.url()),
      parameters.headers(),
      response -> MAPPER.readTree(response.body())
    );
    var snapshot = parseSnapshot(rows, Instant.now());
    updateGraph(context ->
      repository.update(
        snapshot.currentOutages(),
        snapshot.plannedOutages(),
        Instant.now(),
        parameters.staleAfter()
      )
    );
    LOG.info(
      "Equipment status updated: {} units out, {} with planned windows",
      snapshot.currentOutages().size(),
      snapshot.plannedOutages().size()
    );
  }

  record Snapshot(
    Map<String, EquipmentOutage> currentOutages,
    Map<String, List<PlannedEquipmentOutage>> plannedOutages
  ) {}

  /**
   * Package-private for tests. {@code now} decides whether an outage whose start is in the
   * future counts as current (it does not, regardless of flags).
   */
  static Snapshot parseSnapshot(JsonNode rows, Instant now) {
    Map<String, EquipmentOutage> current = new HashMap<>();
    Map<String, List<PlannedEquipmentOutage>> planned = new HashMap<>();
    if (!rows.isArray()) {
      throw new IllegalArgumentException("expected a JSON array of outage rows");
    }
    for (JsonNode row : rows) {
      String code = text(row, "equipment");
      if (code == null || code.isBlank()) {
        continue;
      }
      Instant start = parseFeedTime(text(row, "outagedate"));
      Instant estimatedReturn = parseFeedTime(text(row, "estimatedreturntoservice"));
      String reason = text(row, "reason");
      boolean upcoming =
        "Y".equalsIgnoreCase(text(row, "isupcomingoutage")) ||
        (start != null && start.isAfter(now));
      if (upcoming) {
        if (start != null) {
          planned
            .computeIfAbsent(code, k -> new ArrayList<>())
            .add(new PlannedEquipmentOutage(start, estimatedReturn, reason));
        }
      } else {
        current.put(code, new EquipmentOutage(reason, start, estimatedReturn));
      }
    }
    planned.replaceAll((code, windows) -> List.copyOf(windows));
    return new Snapshot(current, planned);
  }

  @Nullable
  private static String text(JsonNode row, String field) {
    var node = row.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  @Nullable
  static Instant parseFeedTime(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(value.trim(), FEED_TIME).atZone(FEED_ZONE).toInstant();
    } catch (Exception e) {
      LOG.debug("Unparseable equipment feed timestamp: {}", value);
      return null;
    }
  }

  @Override
  public String toString() {
    return "MtaEquipmentStatusUpdater{url=" + parameters.url() + "}";
  }
}
