package org.opentripplanner.updater.equipment;

import java.time.Duration;
import org.opentripplanner.framework.io.HttpHeaders;
import org.opentripplanner.updater.spi.PollingGraphUpdaterParameters;

/**
 * @param url the MTA E&E current-outages feed (nyct_ene.json shape)
 * @param staleAfter how long readers may trust the last successful poll before degrading to
 *                   unknown status
 */
public record MtaEquipmentStatusUpdaterParameters(
  String configRef,
  String url,
  Duration frequency,
  Duration staleAfter,
  HttpHeaders headers
) implements PollingGraphUpdaterParameters {}
