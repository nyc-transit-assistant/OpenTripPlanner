package org.opentripplanner.apis.gtfs.datafetchers;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Objects;
import org.opentripplanner.apis.gtfs.GraphQLRequestContext;
import org.opentripplanner.apis.gtfs.generated.GraphQLDataFetchers;
import org.opentripplanner.apis.gtfs.generated.GraphQLTypes;
import org.opentripplanner.service.equipmentstatus.EquipmentStatusService;
import org.opentripplanner.service.equipmentstatus.model.EquipmentOutage;
import org.opentripplanner.service.equipmentstatus.model.PlannedEquipmentOutage;
import org.opentripplanner.transit.model.site.Entrance;
import org.opentripplanner.transit.model.site.StationEquipment;
import org.opentripplanner.transit.service.TransitService;

public class StationEquipmentImpl implements GraphQLDataFetchers.GraphQLStationEquipment {

  @Override
  public DataFetcher<String> id() {
    return environment -> source(environment).id().toString();
  }

  @Override
  public DataFetcher<String> code() {
    return environment -> source(environment).code();
  }

  @Override
  public DataFetcher<GraphQLTypes.GraphQLEquipmentType> type() {
    return environment ->
      switch (source(environment).type()) {
        case ELEVATOR -> GraphQLTypes.GraphQLEquipmentType.ELEVATOR;
        case ESCALATOR -> GraphQLTypes.GraphQLEquipmentType.ESCALATOR;
      };
  }

  @Override
  public DataFetcher<String> serving() {
    return environment -> source(environment).serving();
  }

  @Override
  public DataFetcher<Boolean> ada() {
    return environment -> source(environment).ada();
  }

  @Override
  public DataFetcher<Boolean> routable() {
    return environment -> source(environment).routable();
  }

  @Override
  public DataFetcher<Boolean> operational() {
    return environment -> equipmentStatus(environment).operational(source(environment).code());
  }

  @Override
  public DataFetcher<EquipmentOutage> outage() {
    return environment -> equipmentStatus(environment).currentOutage(source(environment).code());
  }

  @Override
  public DataFetcher<Iterable<PlannedEquipmentOutage>> plannedOutages() {
    return environment ->
      List.copyOf(equipmentStatus(environment).plannedOutages(source(environment).code()));
  }

  @Override
  public DataFetcher<Object> stop() {
    return environment -> {
      var stationId = source(environment).stationId();
      return stationId == null ? null : getTransitService(environment).getStation(stationId);
    };
  }

  @Override
  public DataFetcher<Iterable<Entrance>> entrances() {
    return environment -> {
      var transitService = getTransitService(environment);
      return source(environment)
        .entranceIds()
        .stream()
        .map(id -> {
          try {
            return transitService.getEntrance(id);
          } catch (RuntimeException e) {
            return null;
          }
        })
        .filter(Objects::nonNull)
        .toList();
    };
  }

  private static StationEquipment source(DataFetchingEnvironment environment) {
    return environment.getSource();
  }

  private static TransitService getTransitService(DataFetchingEnvironment environment) {
    return environment.<GraphQLRequestContext>getContext().transitService();
  }

  private static EquipmentStatusService equipmentStatus(DataFetchingEnvironment environment) {
    return environment.<GraphQLRequestContext>getContext().equipmentStatusService();
  }
}
