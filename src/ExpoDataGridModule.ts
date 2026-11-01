import { NativeModule, requireNativeModule } from 'expo';

import type { ExpoDataGridModuleSharedObject } from './ExpoDataGridModuleSharedObject';

declare class ExpoDataGridModule extends NativeModule<{}> {
  hello(): string;
  setValueAsync(value: string): Promise<void>;
  ExpoDataGridModuleSharedObject: typeof ExpoDataGridModuleSharedObject;
}

export default requireNativeModule<ExpoDataGridModule>('ExpoDataGrid');
