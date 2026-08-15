package org.opentripplanner.service.equipmentstatus.configure;

import dagger.Binds;
import dagger.Module;
import org.opentripplanner.service.equipmentstatus.EquipmentStatusRepository;
import org.opentripplanner.service.equipmentstatus.internal.DefaultEquipmentStatusService;

@Module
public interface EquipmentStatusRepositoryModule {
  @Binds
  EquipmentStatusRepository bindRepository(DefaultEquipmentStatusService service);
}
