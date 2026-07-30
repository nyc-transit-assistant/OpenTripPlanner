package org.opentripplanner.graph_builder.issues;

import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.graph_builder.issue.api.DataImportIssue;

/**
 * A trip pattern had no shape of its own and its geometry was borrowed from other patterns. Only
 * reported when some of the borrowed geometry is an approximation rather than a copy of the same
 * trackage, so that synthesized alignments are not mistaken for surveyed ones.
 */
public record SynthesizedHopGeometry(
  FeedScopedId routeId,
  FeedScopedId fromStopId,
  FeedScopedId toStopId,
  int stitched,
  int reversed,
  int straight
) implements DataImportIssue {
  private static final String FMT =
    "Pattern on route %s (%s -> %s) has no shape; %d hop(s) stitched from a longer sequence, " +
    "%d reversed from the opposite direction, %d left as straight lines.";

  @Override
  public String getMessage() {
    return String.format(FMT, routeId, fromStopId, toStopId, stitched, reversed, straight);
  }
}
