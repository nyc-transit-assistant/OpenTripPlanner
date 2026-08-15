package org.opentripplanner.service.equipmentstatus.model;

import java.time.Instant;
import javax.annotation.Nullable;

/**
 * A current out-of-service condition for one elevator/escalator unit, as reported by the
 * operator's outage feed.
 *
 * @param reason operator-supplied cause, e.g. {@code Capital Replacement}, {@code Repair}
 * @param since when the unit went out of service; null when the feed omitted or failed to parse it
 * @param estimatedReturnToService operator's estimate; null when unknown
 */
public record EquipmentOutage(
  @Nullable String reason,
  @Nullable Instant since,
  @Nullable Instant estimatedReturnToService
) {}
