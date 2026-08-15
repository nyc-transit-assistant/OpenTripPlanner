package org.opentripplanner.service.equipmentstatus.configure;

import dagger.Binds;
import dagger.Module;
import org.opentripplanner.service.equipmentstatus.EquipmentStatusService;
import org.opentripplanner.service.equipmentstatus.internal.DefaultEquipmentStatusService;

@Module
public interface EquipmentStatusServiceModule {
  @Binds
  EquipmentStatusService bindService(DefaultEquipmentStatusService service);
}
