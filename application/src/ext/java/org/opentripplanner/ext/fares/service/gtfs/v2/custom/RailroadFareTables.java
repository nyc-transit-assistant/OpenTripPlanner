package org.opentripplanner.ext.fares.service.gtfs.v2.custom;

import com.google.common.collect.Multimap;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.ext.fares.model.FareLegRule;
import org.opentripplanner.model.fare.FareProduct;
import org.opentripplanner.transit.model.site.StopLocation;

/**
 * Origin-destination fare lookup for the zone-priced railroads (LIRR, Metro-North, NJ Transit
 * rail). Built at graph-build time from the feeds' authored Fares V2 data: the zone areas
 * (stop_areas) and the area-pair leg rules. The railroad composer joins consecutive legs of one
 * railroad and prices the end-to-end pair here — the same data prices single legs correctly in
 * the stock engine, this table exists so multi-leg trips (change at Jamaica, Secaucus) are one
 * fare, matching how railroad tickets actually work.
 *
 * <p>A stop can sit in several areas (Far Rockaway is both zone 4 and the Far Rockaway Ticket
 * area; Fordham is Harlem zone 2 and New Haven zone 11), so a lookup returns every product of
 * every matching area pair — the composer picks the cheapest applicable ticket, which is what a
 * rational rider buys.
 */
public class RailroadFareTables implements Serializable {

  /** One directional area-pair pricing rule. */
  public record OdRule(
    FeedScopedId fromArea,
    FeedScopedId toArea,
    List<FareProduct> products
  ) implements Serializable {}

  private final Map<String, List<OdRule>> rulesByFeed;
  private final Map<FeedScopedId, Set<FeedScopedId>> areasByStop;

  private RailroadFareTables(
    Map<String, List<OdRule>> rulesByFeed,
    Map<FeedScopedId, Set<FeedScopedId>> areasByStop
  ) {
    this.rulesByFeed = rulesByFeed;
    this.areasByStop = areasByStop;
  }

  public static RailroadFareTables of(
    Set<String> railroadFeeds,
    Collection<FareLegRule> legRules,
    Multimap<FeedScopedId, FeedScopedId> stopAreas
  ) {
    Map<String, List<OdRule>> rules = new HashMap<>();
    for (FareLegRule rule : legRules) {
      if (
        railroadFeeds.contains(rule.feedId()) &&
        rule.fromAreaId() != null &&
        rule.toAreaId() != null
      ) {
        rules
          .computeIfAbsent(rule.feedId(), f -> new ArrayList<>())
          .add(new OdRule(rule.fromAreaId(), rule.toAreaId(), List.copyOf(rule.fareProducts())));
      }
    }
    Map<FeedScopedId, Set<FeedScopedId>> areas = new HashMap<>();
    for (var entry : stopAreas.entries()) {
      if (railroadFeeds.contains(entry.getKey().getFeedId())) {
        areas.computeIfAbsent(entry.getKey(), s -> new HashSet<>()).add(entry.getValue());
      }
    }
    return new RailroadFareTables(rules, areas);
  }

  public boolean isEmpty() {
    return rulesByFeed.isEmpty();
  }

  /** Every product priced for the given feed and stop pair, across all matching area pairs. */
  public List<FareProduct> lookup(String feed, StopLocation board, StopLocation alight) {
    var rules = rulesByFeed.get(feed);
    if (rules == null) {
      return List.of();
    }
    var fromAreas = areasOf(board);
    var toAreas = areasOf(alight);
    if (fromAreas.isEmpty() || toAreas.isEmpty()) {
      return List.of();
    }
    List<FareProduct> products = new ArrayList<>();
    for (OdRule rule : rules) {
      if (fromAreas.contains(rule.fromArea()) && toAreas.contains(rule.toArea())) {
        products.addAll(rule.products());
      }
    }
    return products;
  }

  private Set<FeedScopedId> areasOf(StopLocation stop) {
    Set<FeedScopedId> areas = new HashSet<>(areasByStop.getOrDefault(stop.getId(), Set.of()));
    var station = stop.getParentStation();
    if (station != null) {
      areas.addAll(areasByStop.getOrDefault(station.getId(), Set.of()));
    }
    return areas;
  }
}
