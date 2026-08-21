package org.opentripplanner.ext.fares.service.gtfs.v2.custom;

import com.google.common.collect.Multimap;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.ext.fares.service.gtfs.GtfsFaresService;
import org.opentripplanner.model.fare.FareOffer;
import org.opentripplanner.model.fare.FareProduct;
import org.opentripplanner.model.fare.ItineraryFare;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.TransitLeg;
import org.opentripplanner.transit.model.basic.Money;
import org.opentripplanner.transit.model.site.StopLocation;

/**
 * Fare composition for New York's OMNY fare system, which spans several GTFS datasets (the
 * subway feed and the borough bus feeds). Base prices come from the Fares V2 products authored
 * into each feed; this service owns only the composition rules the GTFS spec cannot express
 * across datasets (NYC Transit Passenger Tariff, effective 2026-01-04):
 *
 * <ul>
 *   <li>Consecutive subway legs interchanging within the same station (complex) are one fare —
 *       riding within fare control is unlimited.</li>
 *   <li>One free transfer within the transfer window of the initial fare: subway↔bus, bus↔bus,
 *       and the tariff's designated free out-of-system subway↔subway pairs (Appendix I). A
 *       transfer to a costlier service (local → express bus) charges the difference and
 *       consumes the free transfer.</li>
 *   <li>Any other subway→subway re-entry is a new full fare — the tariff grants no general
 *       subway-to-subway transfer.</li>
 * </ul>
 *
 * Legs of feeds outside the OMNY group keep whatever the stock GTFS fare service computed
 * (e.g. PATH's own Fares V2 data). Composition runs independently per rider category, so
 * adult and reduced fares both surface. Fare capping is deliberately not modeled — it is
 * per-payment-method spend accounting that cannot be derived from an itinerary.
 */
public class NycFaresService implements org.opentripplanner.routing.fares.FareService {

  private final GtfsFaresService delegate;
  private final NycFareParams params;

  public NycFaresService(GtfsFaresService delegate, NycFareParams params) {
    this.delegate = Objects.requireNonNull(delegate);
    this.params = Objects.requireNonNull(params);
  }

  @Override
  public ItineraryFare calculateFares(Itinerary itinerary) {
    ItineraryFare stock = delegate.calculateFares(itinerary);
    var result = ItineraryFare.empty();
    Multimap<Leg, FareOffer> stockProducts = stock.getLegProducts();
    for (var entry : stockProducts.entries()) {
      if (!isOmnyLeg(entry.getKey())) {
        result.addFareProduct(entry.getKey(), entry.getValue());
      }
    }
    List<TransitLeg> omnyLegs = itinerary
      .listTransitLegs()
      .stream()
      .filter(this::isOmnyLeg)
      .toList();
    if (omnyLegs.isEmpty()) {
      return result;
    }
    for (String category : riderCategories(omnyLegs, stockProducts)) {
      composeChain(omnyLegs, stockProducts, category, result);
    }
    return result;
  }

  /**
   * Rider category ids present on the OMNY legs' base products; a single null element when the
   * fare data carries no categories at all. Each feed scopes its own rider categories
   * (mta-subway:adult vs mta-bus-company:adult name the same rider), so categories are keyed by
   * their local id — the OMNY feeds deliberately author matching local ids.
   */
  private Set<String> riderCategories(
    List<TransitLeg> legs,
    Multimap<Leg, FareOffer> stockProducts
  ) {
    var categories = new LinkedHashSet<String>();
    var uncategorized = false;
    for (TransitLeg leg : legs) {
      for (FareOffer offer : stockProducts.get(leg)) {
        var category = offer.fareProduct().category();
        if (category == null) {
          uncategorized = true;
        } else {
          categories.add(category.id().getId());
        }
      }
    }
    if (categories.isEmpty() || uncategorized) {
      categories.add(null);
    }
    return categories;
  }

  /**
   * Walk the OMNY legs in itinerary order, tracking the fare paid, the transfer window anchored
   * at the initial fare, and the single free-transfer budget. Legs covered by one fare share the
   * same {@link FareOffer} instance, so the API surfaces them as a single purchase.
   */
  private void composeChain(
    List<TransitLeg> legs,
    Multimap<Leg, FareOffer> stockProducts,
    @Nullable String category,
    ItineraryFare result
  ) {
    ZonedDateTime windowStart = null;
    int budget = 0;
    Money credit = null;
    FareOffer charged = null;
    TransitLeg previous = null;
    for (TransitLeg leg : legs) {
      FareProduct base = baseProduct(stockProducts.get(leg), category);
      if (base == null) {
        // The fare data has no product for this leg and category (e.g. no reduced fare row):
        // leave the leg unpriced for this category rather than inventing a number.
        previous = leg;
        continue;
      }
      boolean inWindow =
        windowStart != null && !leg.startTime().isAfter(windowStart.plus(params.transferWindow()));
      boolean bothSubway = previous != null && isSubway(previous) && isSubway(leg);
      if (bothSubway && charged != null && sameStation(previous, leg)) {
        // interchange within fare control — still the same fare, budget untouched
        result.addFareProduct(leg, charged);
      } else if (
        bothSubway &&
        charged != null &&
        budget > 0 &&
        inWindow &&
        isDesignatedOosPair(previous, leg)
      ) {
        result.addFareProduct(leg, charged);
        budget--;
      } else if (previous != null && !bothSubway && charged != null && budget > 0 && inWindow) {
        // subway↔bus or bus↔bus: the one free transfer, stepping up if the next ride costs more
        if (base.price().greaterThan(credit)) {
          FareProduct upgrade = FareProduct.of(
            new FeedScopedId(feedId(leg), "omny-transfer-upgrade"),
            "Transfer upgrade to " + base.name(),
            base.price().minus(credit)
          )
            .withCategory(base.category())
            .build();
          result.addFareProduct(
            leg,
            FareOffer.of(windowStart, upgrade, List.of(charged.fareProduct()))
          );
          credit = base.price();
        } else {
          result.addFareProduct(leg, charged);
        }
        budget--;
      } else {
        charged = FareOffer.of(leg.startTime(), base);
        result.addFareProduct(leg, charged);
        windowStart = leg.startTime();
        budget = 1;
        credit = base.price();
      }
      previous = leg;
    }
  }

  @Nullable
  private FareProduct baseProduct(Collection<FareOffer> offers, @Nullable String category) {
    return offers
      .stream()
      .filter(o -> o instanceof FareOffer.DefaultFareOffer)
      .map(FareOffer::fareProduct)
      .filter(p ->
        Objects.equals(p.category() == null ? null : p.category().id().getId(), category)
      )
      .min(Comparator.comparing(FareProduct::price))
      .orElse(null);
  }

  private boolean isOmnyLeg(Leg leg) {
    return leg instanceof TransitLeg transitLeg && params.omnyFeeds().contains(feedId(transitLeg));
  }

  private boolean isSubway(TransitLeg leg) {
    return params.subwayFeed().equals(feedId(leg));
  }

  private static String feedId(TransitLeg leg) {
    return leg.agency().getId().getFeedId();
  }

  private static boolean sameStation(TransitLeg previous, TransitLeg current) {
    return stationOrStopId(previous.to().stop).equals(stationOrStopId(current.from().stop));
  }

  private boolean isDesignatedOosPair(TransitLeg previous, TransitLeg current) {
    var alight = stationOrStopId(previous.to().stop);
    var board = stationOrStopId(current.from().stop);
    return params
      .freeOutOfSystemTransfers()
      .stream()
      .anyMatch(pair -> pair.matches(alight, board));
  }

  private static FeedScopedId stationOrStopId(StopLocation stop) {
    var station = stop.getParentStation();
    return station != null ? station.getId() : stop.getId();
  }
}
