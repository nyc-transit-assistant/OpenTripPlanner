package org.opentripplanner.apis.gtfs.datafetchers;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.time.OffsetDateTime;
import org.opentripplanner.apis.gtfs.generated.GraphQLDataFetchers;
import org.opentripplanner.service.equipmentstatus.model.PlannedEquipmentOutage;

public class PlannedEquipmentOutageImpl
  implements GraphQLDataFetchers.GraphQLPlannedEquipmentOutage {

  @Override
  public DataFetcher<OffsetDateTime> start() {
    return environment ->
      EquipmentOutageImpl.toOffsetDateTime(environment, source(environment).start());
  }

  @Override
  public DataFetcher<OffsetDateTime> end() {
    return environment ->
      EquipmentOutageImpl.toOffsetDateTime(environment, source(environment).end());
  }

  @Override
  public DataFetcher<String> reason() {
    return environment -> source(environment).reason();
  }

  private static PlannedEquipmentOutage source(DataFetchingEnvironment environment) {
    return environment.getSource();
  }
}
