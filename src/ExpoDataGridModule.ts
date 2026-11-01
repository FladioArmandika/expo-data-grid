import { NativeModule, requireNativeModule } from 'expo';

import type { DeviceInfo } from './ExpoDataGrid.types';
import type { GridData } from './GridData';

declare class ExpoDataGridModule extends NativeModule<{}> {
  GridData: typeof GridData;
  getDeviceInfo(): DeviceInfo;
  getResidentMemoryMB(): number;
}

export default requireNativeModule<ExpoDataGridModule>('ExpoDataGrid');
