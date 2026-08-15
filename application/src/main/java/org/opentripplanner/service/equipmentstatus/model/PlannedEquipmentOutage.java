package org.opentripplanner.service.equipmentstatus.model;

import java.time.Instant;
import javax.annotation.Nullable;

/**
 * A scheduled future outage window for one elevator/escalator unit (typically overnight
 * maintenance). The unit is expected to be operational until {@code start}.
 *
 * @param start when the planned outage begins
 * @param end when the unit is expected back; null when the feed omitted or failed to parse it
 * @param reason operator-supplied cause, e.g. {@code Maintenance}
 */
public record PlannedEquipmentOutage(
  Instant start,
  @Nullable Instant end,
  @Nullable String reason
) {}
