package org.opentripplanner.apis.gtfs.datafetchers;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.time.Instant;
import java.time.OffsetDateTime;
import javax.annotation.Nullable;
import org.opentripplanner.apis.gtfs.GraphQLRequestContext;
import org.opentripplanner.apis.gtfs.generated.GraphQLDataFetchers;
import org.opentripplanner.service.equipmentstatus.model.EquipmentOutage;

public class EquipmentOutageImpl implements GraphQLDataFetchers.GraphQLEquipmentOutage {

  @Override
  public DataFetcher<String> reason() {
    return environment -> source(environment).reason();
  }

  @Override
  public DataFetcher<OffsetDateTime> since() {
    return environment -> toOffsetDateTime(environment, source(environment).since());
  }

  @Override
  public DataFetcher<OffsetDateTime> estimatedReturnToService() {
    return environment ->
      toOffsetDateTime(environment, source(environment).estimatedReturnToService());
  }

  private static EquipmentOutage source(DataFetchingEnvironment environment) {
    return environment.getSource();
  }

  @Nullable
  static OffsetDateTime toOffsetDateTime(
    DataFetchingEnvironment environment,
    @Nullable Instant instant
  ) {
    if (instant == null) {
      return null;
    }
    var zone = environment.<GraphQLRequestContext>getContext().transitService().getTimeZone();
    return OffsetDateTime.ofInstant(instant, zone);
  }
}
