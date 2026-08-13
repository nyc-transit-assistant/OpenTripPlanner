package org.opentripplanner.transit.model.site;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;

/**
 * An elevator or escalator unit, keyed by the operator's equipment code (MTA E&amp;E "EL359").
 * <p>
 * Built at graph time from two sources: pathway edges whose signposted text carries the unit code
 * (which also yields the owning station and any entrance endpoints), and an optional
 * {@code equipment.txt} extension file in the GTFS bundle carrying the operator's registry
 * metadata (ADA flag, authoritative serving text, units not yet modeled as pathways).
 * <p>
 * Operational status is intentionally absent from this static model — it belongs to the realtime
 * layer, which joins on {@link #code()}.
 */
public final class StationEquipment implements Serializable {

  public enum EquipmentType {
    ELEVATOR,
    ESCALATOR,
  }

  private final FeedScopedId id;
  private final EquipmentType type;

  @Nullable
  private final String serving;

  @Nullable
  private final Boolean ada;

  @Nullable
  private final FeedScopedId stationId;

  private final List<FeedScopedId> entranceIds;

  /** True when at least one pathway edge in the graph carries this unit. */
  private final boolean routable;

  public StationEquipment(
    FeedScopedId id,
    EquipmentType type,
    @Nullable String serving,
    @Nullable Boolean ada,
    @Nullable FeedScopedId stationId,
    List<FeedScopedId> entranceIds,
    boolean routable
  ) {
    this.id = Objects.requireNonNull(id);
    this.type = Objects.requireNonNull(type);
    this.serving = serving;
    this.ada = ada;
    this.stationId = stationId;
    this.entranceIds = List.copyOf(entranceIds);
    this.routable = routable;
  }

  public FeedScopedId id() {
    return id;
  }

  /** The operator's unit code, e.g. "EL359" — the feed-agnostic join key. */
  public String code() {
    return id.getId();
  }

  public EquipmentType type() {
    return type;
  }

  @Nullable
  public String serving() {
    return serving;
  }

  @Nullable
  public Boolean ada() {
    return ada;
  }

  @Nullable
  public FeedScopedId stationId() {
    return stationId;
  }

  public List<FeedScopedId> entranceIds() {
    return entranceIds;
  }

  public boolean routable() {
    return routable;
  }
}
