package org.opentripplanner.ext.fares.service.gtfs.v2.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.model.plan.TestItineraryBuilder.newItinerary;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.opentripplanner.core.model.id.FeedScopedId;
import org.opentripplanner.ext.fares.model.FareLegRule;
import org.opentripplanner.ext.fares.service.gtfs.GtfsFaresService;
import org.opentripplanner.ext.fares.service.gtfs.v1.DefaultFareService;
import org.opentripplanner.ext.fares.service.gtfs.v2.GtfsFaresV2Service;
import org.opentripplanner.model.fare.FareOffer;
import org.opentripplanner.model.fare.FareProduct;
import org.opentripplanner.model.fare.RiderCategory;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.Place;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.basic.Money;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.network.GroupOfRoutes;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.organization.Agency;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.Station;

class NycFaresServiceTest {

  private static final String SUBWAY = "mta-subway";
  private static final String BUS = "mta-bus-brooklyn";
  private static final String PATH = "panynj-path";

  private static final TimetableRepositoryForTest MODEL = TimetableRepositoryForTest.of();

  private static final RiderCategory ADULT = RiderCategory.of(new FeedScopedId(SUBWAY, "adult"))
    .withName("Adult")
    .build();
  private static final RiderCategory REDUCED = RiderCategory.of(new FeedScopedId(SUBWAY, "reduced"))
    .withName("Reduced Fare")
    .build();

  // real feeds each scope their own rider categories: the bus feed's "adult" is a different
  // FeedScopedId than the subway feed's — the service must match them by local id
  private static final RiderCategory BUS_ADULT_CAT = RiderCategory.of(
    new FeedScopedId(BUS, "adult")
  )
    .withName("Adult")
    .build();
  private static final RiderCategory BUS_REDUCED_CAT = RiderCategory.of(
    new FeedScopedId(BUS, "reduced")
  )
    .withName("Reduced Fare")
    .build();

  private static final FareProduct SUBWAY_ADULT = product(SUBWAY, "local_single", 3.00f, ADULT);
  private static final FareProduct SUBWAY_REDUCED = product(SUBWAY, "local_single", 1.50f, REDUCED);
  private static final FareProduct BUS_ADULT = product(BUS, "local_single", 3.00f, BUS_ADULT_CAT);
  private static final FareProduct BUS_REDUCED = product(
    BUS,
    "local_single",
    1.50f,
    BUS_REDUCED_CAT
  );
  private static final FareProduct EXPRESS_ADULT = product(
    BUS,
    "express_single",
    7.25f,
    BUS_ADULT_CAT
  );
  private static final FareProduct PATH_SINGLE = product(PATH, "single", 3.25f, null);

  private static final FeedScopedId EXPRESS_NETWORK = new FeedScopedId(BUS, "express");
  private static final FeedScopedId SIR_NETWORK = new FeedScopedId(SUBWAY, "sir");

  private static final FareProduct SIR_FREE_ADULT = product(SUBWAY, "sir_free", 0.00f, ADULT);
  private static final FareProduct SIR_FREE_REDUCED = product(SUBWAY, "sir_free", 0.00f, REDUCED);
  private static final FareProduct EXPRESS_REDUCED = product(
    BUS,
    "express_single",
    3.60f,
    BUS_REDUCED_CAT
  );

  // subway stations: two joined by a designated OOS transfer, one unrelated
  private static final Station LEX_59 = station(SUBWAY, "CX613");
  private static final Station LEX_63 = station(SUBWAY, "B08");
  private static final Station UNION_SQ = station(SUBWAY, "CX602");
  private static final Station FAR_AWAY = station(SUBWAY, "250");

  private static final RegularStop LEX_59_PLATFORM = stop(SUBWAY, "R11N", LEX_59);
  private static final RegularStop LEX_63_PLATFORM = stop(SUBWAY, "B08S", LEX_63);
  private static final RegularStop UNION_L = stop(SUBWAY, "L03N", UNION_SQ);
  private static final RegularStop UNION_456 = stop(SUBWAY, "635S", UNION_SQ);
  private static final RegularStop FAR_PLATFORM = stop(SUBWAY, "250N", FAR_AWAY);
  private static final RegularStop BUS_STOP_1 = stop(BUS, "B100", null);
  private static final RegularStop BUS_STOP_2 = stop(BUS, "B200", null);
  private static final RegularStop BUS_STOP_3 = stop(BUS, "B300", null);
  private static final RegularStop PATH_STOP = stop(PATH, "26733", null);

  private static final Route SUBWAY_ROUTE = route(SUBWAY, "6", TransitMode.SUBWAY, null);
  private static final Route BUS_ROUTE = route(BUS, "B41", TransitMode.BUS, null);
  private static final Route EXPRESS_ROUTE = route(BUS, "BM2", TransitMode.BUS, EXPRESS_NETWORK);
  private static final Route PATH_ROUTE = route(PATH, "PATH", TransitMode.RAIL, null);
  private static final Route SIR_ROUTE = route(SUBWAY, "SI", TransitMode.RAIL, SIR_NETWORK);

  private static final RegularStop SIR_STOP_1 = stop(SUBWAY, "S19N", null);
  private static final RegularStop SIR_STOP_2 = stop(SUBWAY, "S22N", null);

  private static final NycFaresService SERVICE = new NycFaresService(
    stockService(),
    new NycFareParams(
      SUBWAY,
      Set.of(SUBWAY, BUS),
      Set.of(new NycFareParams.OosPair(LEX_59.getId(), LEX_63.getId())),
      Duration.ofMinutes(120)
    )
  );

  @Test
  void subwayToBusSharesOneFare() {
    var itinerary = newItinerary(Place.forStop(UNION_L), 0)
      .transit(SUBWAY_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(FAR_PLATFORM), null, null, null)
      .walk(60, Place.forStop(BUS_STOP_1))
      .transit(BUS_ROUTE, "t2", 1200, 1800, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    var subwayAdult = offer(fare, legs.get(0), ADULT);
    var busAdult = offer(fare, legs.get(1), ADULT);
    assertEquals(subwayAdult.uniqueId(), busAdult.uniqueId());
    assertEquals(Money.usDollars(3.00f), subwayAdult.fareProduct().price());
    var subwayReduced = offer(fare, legs.get(0), REDUCED);
    var busReduced = offer(fare, legs.get(1), REDUCED);
    assertEquals(subwayReduced.uniqueId(), busReduced.uniqueId());
    assertEquals(Money.usDollars(1.50f), subwayReduced.fareProduct().price());
  }

  @Test
  void inSystemSubwayChainKeepsFreeTransferForBus() {
    var itinerary = newItinerary(Place.forStop(FAR_PLATFORM), 0)
      .transit(SUBWAY_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(UNION_L), null, null, null)
      .walk(120, Place.forStop(UNION_456))
      .transit(SUBWAY_ROUTE, "t2", 900, 1500, 5, 7, Place.forStop(FAR_PLATFORM), null, null, null)
      .walk(60, Place.forStop(BUS_STOP_1))
      .transit(BUS_ROUTE, "t3", 1800, 2400, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    var first = offer(fare, legs.get(0), ADULT);
    assertEquals(first.uniqueId(), offer(fare, legs.get(1), ADULT).uniqueId());
    assertEquals(first.uniqueId(), offer(fare, legs.get(2), ADULT).uniqueId());
  }

  @Test
  void outOfSystemSubwayReentryChargesAgain() {
    var itinerary = newItinerary(Place.forStop(UNION_L), 0)
      .transit(SUBWAY_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(FAR_PLATFORM), null, null, null)
      .walk(300, Place.forStop(LEX_59_PLATFORM))
      .transit(SUBWAY_ROUTE, "t2", 1200, 1800, 5, 7, Place.forStop(UNION_456), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    var first = offer(fare, legs.get(0), ADULT);
    var second = offer(fare, legs.get(1), ADULT);
    assertNotEquals(first.uniqueId(), second.uniqueId());
    assertEquals(Money.usDollars(3.00f), second.fareProduct().price());
  }

  @Test
  void designatedOosPairIsFreeAndConsumesTheTransfer() {
    var itinerary = newItinerary(Place.forStop(UNION_L), 0)
      .transit(SUBWAY_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(LEX_59_PLATFORM), null, null, null)
      .walk(300, Place.forStop(LEX_63_PLATFORM))
      .transit(SUBWAY_ROUTE, "t2", 1200, 1800, 5, 7, Place.forStop(FAR_PLATFORM), null, null, null)
      .walk(60, Place.forStop(BUS_STOP_1))
      .transit(BUS_ROUTE, "t3", 2400, 3000, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    var first = offer(fare, legs.get(0), ADULT);
    // OOS transfer is free but consumes the budget: the later bus ride pays anew
    assertEquals(first.uniqueId(), offer(fare, legs.get(1), ADULT).uniqueId());
    assertNotEquals(first.uniqueId(), offer(fare, legs.get(2), ADULT).uniqueId());
  }

  @Test
  void secondBusTransferChargesAgain() {
    var itinerary = newItinerary(Place.forStop(BUS_STOP_1), 0)
      .transit(BUS_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .transit(BUS_ROUTE, "t2", 900, 1500, 5, 7, Place.forStop(BUS_STOP_3), null, null, null)
      .transit(BUS_ROUTE, "t3", 1800, 2400, 5, 7, Place.forStop(BUS_STOP_1), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    var first = offer(fare, legs.get(0), ADULT);
    assertEquals(first.uniqueId(), offer(fare, legs.get(1), ADULT).uniqueId());
    assertNotEquals(first.uniqueId(), offer(fare, legs.get(2), ADULT).uniqueId());
  }

  @Test
  void localToExpressChargesTheDifference() {
    var itinerary = newItinerary(Place.forStop(BUS_STOP_1), 0)
      .transit(BUS_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .transit(EXPRESS_ROUTE, "t2", 900, 1500, 5, 7, Place.forStop(BUS_STOP_3), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    var express = offer(fare, legs.get(1), ADULT);
    assertInstanceOf(FareOffer.DependentFareOffer.class, express);
    assertEquals(Money.usDollars(4.25f), express.fareProduct().price());
  }

  @Test
  void expressToLocalIsFree() {
    var itinerary = newItinerary(Place.forStop(BUS_STOP_1), 0)
      .transit(EXPRESS_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .transit(BUS_ROUTE, "t2", 900, 1500, 5, 7, Place.forStop(BUS_STOP_3), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    var first = offer(fare, legs.get(0), ADULT);
    assertEquals(Money.usDollars(7.25f), first.fareProduct().price());
    assertEquals(first.uniqueId(), offer(fare, legs.get(1), ADULT).uniqueId());
  }

  @Test
  void transferWindowExpires() {
    var itinerary = newItinerary(Place.forStop(UNION_L), 0)
      .transit(SUBWAY_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(FAR_PLATFORM), null, null, null)
      .walk(60, Place.forStop(BUS_STOP_1))
      .transit(BUS_ROUTE, "t2", 8000, 8600, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    assertNotEquals(
      offer(fare, legs.get(0), ADULT).uniqueId(),
      offer(fare, legs.get(1), ADULT).uniqueId()
    );
  }

  @Test
  void pathLegsPassThroughUntouched() {
    var itinerary = newItinerary(Place.forStop(PATH_STOP), 0)
      .transit(PATH_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(PATH_STOP), null, null, null)
      .walk(60, Place.forStop(UNION_L))
      .transit(SUBWAY_ROUTE, "t2", 1200, 1800, 5, 7, Place.forStop(FAR_PLATFORM), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    var pathOffers = fare.getLegProducts().get(legs.get(0));
    assertEquals(1, pathOffers.size());
    assertEquals(Money.usDollars(3.25f), pathOffers.iterator().next().fareProduct().price());
    assertEquals(Money.usDollars(3.00f), offer(fare, legs.get(1), ADULT).fareProduct().price());
  }

  @Test
  void freeSirRideIsZeroAndPreservesTheTransfer() {
    var itinerary = newItinerary(Place.forStop(UNION_L), 0)
      .transit(SUBWAY_ROUTE, "t1", 0, 600, 5, 7, Place.forStop(FAR_PLATFORM), null, null, null)
      .walk(60, Place.forStop(SIR_STOP_1))
      .transit(SIR_ROUTE, "t2", 900, 1500, 5, 7, Place.forStop(SIR_STOP_2), null, null, null)
      .walk(60, Place.forStop(BUS_STOP_1))
      .transit(BUS_ROUTE, "t3", 1800, 2400, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .build();
    var fare = SERVICE.calculateFares(itinerary);
    var legs = itinerary.listTransitLegs();
    var subway = offer(fare, legs.get(0), ADULT);
    var sir = offer(fare, legs.get(1), ADULT);
    var bus = offer(fare, legs.get(2), ADULT);
    assertEquals(Money.usDollars(0.00f), sir.fareProduct().price());
    assertNotEquals(subway.uniqueId(), sir.uniqueId());
    // the free SIR ride neither consumed nor granted anything: the bus still rides free
    assertEquals(subway.uniqueId(), bus.uniqueId());
  }

  @Test
  void reducedExpressAtPeakPaysFullPrice() {
    // SERVICE_DAY 2020-02-02 is a Sunday and builder times are UTC
    var peakService = new NycFaresService(
      stockService(),
      new NycFareParams(
        SUBWAY,
        Set.of(SUBWAY, BUS),
        Set.of(),
        Duration.ofMinutes(120),
        new NycFareParams.ReducedFarePeakExclusion(
          "express_single",
          "reduced",
          List.of(new NycFareParams.PeakWindow(LocalTime.MIDNIGHT, LocalTime.of(2, 0))),
          Set.of(DayOfWeek.SUNDAY),
          ZoneOffset.UTC
        )
      )
    );
    var peak = newItinerary(Place.forStop(BUS_STOP_1), 0)
      .transit(EXPRESS_ROUTE, "t1", 600, 1200, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .build();
    var peakFare = peakService.calculateFares(peak);
    var reduced = offer(peakFare, peak.listTransitLegs().get(0), REDUCED);
    assertEquals(Money.usDollars(7.25f), reduced.fareProduct().price());
    assertEquals("reduced", reduced.fareProduct().category().id().getId());

    var offPeak = newItinerary(Place.forStop(BUS_STOP_1), 0)
      .transit(EXPRESS_ROUTE, "t1", 8000, 8600, 5, 7, Place.forStop(BUS_STOP_2), null, null, null)
      .build();
    var offPeakFare = peakService.calculateFares(offPeak);
    var offPeakReduced = offer(offPeakFare, offPeak.listTransitLegs().get(0), REDUCED);
    assertEquals(Money.usDollars(3.60f), offPeakReduced.fareProduct().price());
  }

  private static FareOffer offer(
    org.opentripplanner.model.fare.ItineraryFare fare,
    Leg leg,
    @Nullable RiderCategory category
  ) {
    Collection<FareOffer> offers = fare.getLegProducts().get(leg);
    var matching = offers
      .stream()
      .filter(o ->
        Objects.equals(
          o.fareProduct().category() == null ? null : o.fareProduct().category().id().getId(),
          category == null ? null : category.id().getId()
        )
      )
      .toList();
    assertTrue(!matching.isEmpty(), "no offer for category " + category + " on leg " + leg);
    assertEquals(1, matching.size(), "expected exactly one offer per category, got " + matching);
    return matching.getFirst();
  }

  private static GtfsFaresService stockService() {
    var v2 = GtfsFaresV2Service.of()
      .withLegRules(
        FareLegRule.of(new FeedScopedId(SUBWAY, "r1"), List.of(SUBWAY_ADULT, SUBWAY_REDUCED))
          .withLegGroupId(new FeedScopedId(SUBWAY, "subway"))
          .build(),
        FareLegRule.of(new FeedScopedId(BUS, "r2"), List.of(BUS_ADULT, BUS_REDUCED))
          .withLegGroupId(new FeedScopedId(BUS, "local"))
          .build(),
        FareLegRule.of(new FeedScopedId(BUS, "r3"), List.of(EXPRESS_ADULT, EXPRESS_REDUCED))
          .withLegGroupId(new FeedScopedId(BUS, "express"))
          .withNetworkId(EXPRESS_NETWORK)
          .build(),
        FareLegRule.of(new FeedScopedId(SUBWAY, "r5"), List.of(SIR_FREE_ADULT, SIR_FREE_REDUCED))
          .withLegGroupId(new FeedScopedId(SUBWAY, "sir"))
          .withNetworkId(SIR_NETWORK)
          .build(),
        FareLegRule.of(new FeedScopedId(PATH, "r4"), List.of(PATH_SINGLE))
          .withLegGroupId(new FeedScopedId(PATH, "path"))
          .build()
      )
      .build();
    return new GtfsFaresService(new DefaultFareService(), v2);
  }

  private static FareProduct product(
    String feed,
    String id,
    float amount,
    @Nullable RiderCategory category
  ) {
    var builder = FareProduct.of(new FeedScopedId(feed, id), id, Money.usDollars(amount));
    if (category != null) {
      builder = builder.withCategory(category);
    }
    return builder.build();
  }

  private static Station station(String feed, String id) {
    return Station.of(new FeedScopedId(feed, id))
      .withName(new org.opentripplanner.core.model.i18n.NonLocalizedString(id))
      .withCoordinate(40.7, -74.0)
      .build();
  }

  private static RegularStop stop(String feed, String id, @Nullable Station parent) {
    var builder = MODEL.siteRepositoryBuilder()
      .regularStop(new FeedScopedId(feed, id))
      .withName(new org.opentripplanner.core.model.i18n.NonLocalizedString(id))
      .withCoordinate(40.7, -74.0);
    if (parent != null) {
      builder = builder.withParentStation(parent);
    }
    return builder.build();
  }

  private static Route route(
    String feed,
    String shortName,
    TransitMode mode,
    @Nullable FeedScopedId network
  ) {
    var agency = Agency.of(new FeedScopedId(feed, "agency"))
      .withName(feed)
      .withTimezone("America/New_York")
      .build();
    var builder = Route.of(new FeedScopedId(feed, shortName))
      .withAgency(agency)
      .withShortName(shortName)
      .withMode(mode);
    if (network != null) {
      builder.getGroupsOfRoutes().add(GroupOfRoutes.of(network).build());
    }
    return builder.build();
  }
}
