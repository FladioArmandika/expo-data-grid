import { registerWebModule, NativeModule } from 'expo';

// ExpoDataGridModule is not available on the web platform.
class ExpoDataGridModule extends NativeModule<{}> {}

export default registerWebModule(ExpoDataGridModule, 'ExpoDataGridModule');
