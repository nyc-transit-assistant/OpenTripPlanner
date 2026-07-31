package org.opentripplanner.apis.gtfs.datafetchers;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import org.opentripplanner.apis.gtfs.generated.GraphQLDataFetchers;
import org.opentripplanner.routing.alertpatch.TimePeriod;

/**
 * One agency-published time window during which an alert is in effect. Exposing these individually
 * (rather than only the effectiveStartDate/effectiveEndDate envelope) lets clients render honest
 * validity for alerts with disjoint windows — e.g. NYCT's one-window-per-game-day stadium notices.
 */
public class AlertActivePeriodImpl implements GraphQLDataFetchers.GraphQLAlertActivePeriod {

  @Override
  public DataFetcher<Long> endTime() {
    return environment -> {
      long end = getSource(environment).endTime;
      // OPEN_ENDED is an internal sentinel, not a timestamp; surface it as "no explicit end".
      return end == TimePeriod.OPEN_ENDED ? null : end;
    };
  }

  @Override
  public DataFetcher<Long> startTime() {
    return environment -> {
      long start = getSource(environment).startTime;
      // A zero start means the window had no explicit start in the source feed.
      return start == 0 ? null : start;
    };
  }

  private TimePeriod getSource(DataFetchingEnvironment environment) {
    return environment.getSource();
  }
}
