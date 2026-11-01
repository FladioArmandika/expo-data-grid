import { SharedObject, useReleasingSharedObject } from 'expo';

import ExpoDataGridModule from './ExpoDataGridModule';

/**
 * Column data owned by native code. JS hands columns over once; the grid view only receives
 * a reference to this object. Spike surface only: columns are addressed by index.
 */
export declare class GridData extends SharedObject {
  readonly rowCount: number;
  readonly columnCount: number;
  /** Bytes held natively by all columns. */
  readonly byteSize: number;

  reset(rows: number, columns: number): void;
  /** Fills the store with deterministic pseudo-random numbers natively. Resolves with the fill time in ms. */
  fillSyntheticAsync(rows: number, columns: number): Promise<number>;
  /** Copies a Float64Array into native memory. Returns the native copy time in ms. */
  setNumberColumn(column: number, values: Float64Array): number;
  /** Copies dictionary-encoded strings. Returns the native copy time in ms (excludes string[] conversion). */
  setTextColumn(column: number, indices: Int32Array, table: string[]): number;
}

export function createGridData(): GridData {
  return new ExpoDataGridModule.GridData();
}

/** Creates a GridData and releases its native memory when the component unmounts. */
export function useGridData(): GridData {
  return useReleasingSharedObject(() => new ExpoDataGridModule.GridData(), []);
}
