import type { GridData } from './GridData';

export type LoadBenchmarkOptions = {
  rows: number;
  numberColumns: number;
  /** Unique strings in the text column's table. */
  uniqueStrings: number;
  now?: () => number;
};

export type LoadBenchmarkResult = {
  rows: number;
  numberColumns: number;
  uniqueStrings: number;
  /** Time to build the Float64Arrays in JS (not part of the load path, reported for context). */
  numbersGenerateMs: number;
  /** Wall time of the setNumberColumn calls as seen from JS (JSI + native copy). */
  numbersCallMs: number;
  /** Sum of native memcpy time reported by setNumberColumn. */
  numbersNativeCopyMs: number;
  numbersMB: number;
  textGenerateMs: number;
  /** Wall time of setTextColumn, including converting the string table across JSI. */
  textCallMs: number;
  textNativeCopyMs: number;
};

const perf = (globalThis as { performance?: { now(): number } }).performance;
const defaultNow = () => (perf ? perf.now() : Date.now());

/**
 * Measures the data-load path: N numeric columns of `rows` doubles, then one dictionary-encoded
 * text column. Columns are generated one at a time so the JS heap holds at most one column.
 */
export function runLoadBenchmark(data: GridData, opts: LoadBenchmarkOptions): LoadBenchmarkResult {
  const now = opts.now ?? defaultNow;
  const { rows, numberColumns, uniqueStrings } = opts;
  data.reset(rows, numberColumns + 1);

  let generate = 0;
  let call = 0;
  let nativeCopy = 0;
  const column = new Float64Array(rows);
  for (let c = 0; c < numberColumns; c++) {
    let t = now();
    for (let r = 0; r < rows; r++) column[r] = ((r * 7919 + c * 104729) % 10_000_000) / 100 - 50_000;
    generate += now() - t;
    t = now();
    nativeCopy += data.setNumberColumn(c, column);
    call += now() - t;
  }

  let t = now();
  const table = new Array<string>(uniqueStrings);
  for (let i = 0; i < uniqueStrings; i++) table[i] = `SYM${i.toString(36).toUpperCase()}`;
  const indices = new Int32Array(rows);
  for (let r = 0; r < rows; r++) indices[r] = (r * 2654435761) % uniqueStrings;
  const textGenerate = now() - t;
  t = now();
  const textNative = data.setTextColumn(numberColumns, indices, table);
  const textCall = now() - t;

  return {
    rows,
    numberColumns,
    uniqueStrings,
    numbersGenerateMs: generate,
    numbersCallMs: call,
    numbersNativeCopyMs: nativeCopy,
    numbersMB: (rows * numberColumns * 8) / 1_048_576,
    textGenerateMs: textGenerate,
    textCallMs: textCall,
    textNativeCopyMs: textNative,
  };
}
