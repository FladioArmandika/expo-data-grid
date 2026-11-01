import { requireNativeView } from 'expo';
import * as React from 'react';

import type { SpikeGridViewProps } from './ExpoDataGrid.types';

const NativeView: React.ComponentType<SpikeGridViewProps> = requireNativeView('ExpoDataGrid');

/** Milestone 0 spike view: one rendering candidate plus the native perf overlay. */
export function SpikeGridView(props: SpikeGridViewProps) {
  return <NativeView {...props} />;
}
