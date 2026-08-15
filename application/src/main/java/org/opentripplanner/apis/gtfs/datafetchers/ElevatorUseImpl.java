package org.opentripplanner.apis.gtfs.datafetchers;

import graphql.schema.DataFetcher;
import org.opentripplanner.apis.gtfs.GraphQLRequestContext;
import org.opentripplanner.apis.gtfs.generated.GraphQLDataFetchers;
import org.opentripplanner.apis.gtfs.generated.GraphQLTypes.GraphQLVerticalDirection;
import org.opentripplanner.apis.gtfs.mapping.VerticalDirectionMapper;
import org.opentripplanner.model.plan.walkstep.verticaltransportation.ElevatorUse;
import org.opentripplanner.service.streetdetails.model.Level;

public class ElevatorUseImpl implements GraphQLDataFetchers.GraphQLElevatorUse {

  @Override
  public DataFetcher<String> equipmentId() {
    return environment -> {
      ElevatorUse elevatorUse = environment.getSource();
      return elevatorUse.equipmentCode();
    };
  }

  @Override
  public DataFetcher<Boolean> operational() {
    return environment -> {
      ElevatorUse elevatorUse = environment.getSource();
      var code = elevatorUse.equipmentCode();
      if (code == null) {
        return null;
      }
      return environment
        .<GraphQLRequestContext>getContext()
        .equipmentStatusService()
        .operational(code);
    };
  }

  @Override
  public DataFetcher<Level> from() {
    return environment -> {
      ElevatorUse elevatorUse = environment.getSource();
      return elevatorUse.from();
    };
  }

  @Override
  public DataFetcher<Level> to() {
    return environment -> {
      ElevatorUse elevatorUse = environment.getSource();
      return elevatorUse.to();
    };
  }

  @Override
  public DataFetcher<GraphQLVerticalDirection> verticalDirection() {
    return environment -> {
      ElevatorUse elevatorUse = environment.getSource();
      return VerticalDirectionMapper.map(elevatorUse.verticalDirection());
    };
  }
}
