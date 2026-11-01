import type { StyleProp, ViewStyle } from 'react-native';

import type { GridData } from './GridData';

/** Rendering candidates in the Milestone 0 spike. iOS: A, B. Android: A, B, C. */
export type SpikeRenderer = 'A' | 'B' | 'C';

export type BenchmarkResult = {
  platform: 'ios' | 'android';
  renderer: SpikeRenderer;
  rendererName: string;
  rows: number;
  columns: number;
  frames: number;
  droppedFrames: number;
  durationMs: number;
  avgFps: number;
  refreshRate: number;
  worstFrameMs: number;
  blankAvgPct: number;
  blankMaxPct: number;
  peakResidentMB: number;
  timeToFirstFrameMs: number;
};

export type FirstFrameEvent = {
  renderer: SpikeRenderer;
  timeToFirstFrameMs: number;
};

export type SpikeGridViewProps = {
  data: GridData | null;
  renderer?: SpikeRenderer;
  frozenColumns?: number;
  rowHeight?: number;
  headerHeight?: number;
  columnWidth?: number;
  showPerfOverlay?: boolean;
  /** Change to a new positive number to start the scripted fling benchmark. */
  benchmarkRunId?: number;
  onBenchmarkResult?: (event: { nativeEvent: BenchmarkResult }) => void;
  onFirstFrame?: (event: { nativeEvent: FirstFrameEvent }) => void;
  style?: StyleProp<ViewStyle>;
};

export type DeviceInfo = {
  platform: 'ios' | 'android';
  model: string;
  osVersion: string;
  totalMemoryMB: number;
  maxRefreshRate: number;
  isDebugBuild: boolean;
  heapLimitMB?: number;
  largeHeapLimitMB?: number;
};
