import { registerWebModule, NativeModule } from 'expo';

// The grid is native only.
class ExpoDataGridModule extends NativeModule<{}> {}

export default registerWebModule(ExpoDataGridModule, 'ExpoDataGridModule');
