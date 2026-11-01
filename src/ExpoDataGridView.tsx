import { requireNativeView } from 'expo';
import * as React from 'react';

import { ExpoDataGridViewProps } from './ExpoDataGrid.types';

const NativeView: React.ComponentType<ExpoDataGridViewProps> = requireNativeView('ExpoDataGrid');

export default function ExpoDataGridView(props: ExpoDataGridViewProps) {
  return <NativeView {...props} />;
}
