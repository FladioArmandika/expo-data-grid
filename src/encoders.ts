/** Dictionary-encodes strings: each value becomes an index into a table of unique strings. */
export function encodeTextColumn(values: readonly string[]): { indices: Int32Array; table: string[] } {
  const indices = new Int32Array(values.length);
  const lookup = new Map<string, number>();
  const table: string[] = [];
  for (let i = 0; i < values.length; i++) {
    const v = values[i] as string;
    let idx = lookup.get(v);
    if (idx === undefined) {
      idx = table.length;
      table.push(v);
      lookup.set(v, idx);
    }
    indices[i] = idx;
  }
  return { indices, table };
}

/** Decodes a dictionary-encoded column back to strings (tests and debugging). */
export function decodeTextColumn(indices: Int32Array, table: readonly string[]): string[] {
  const out = new Array<string>(indices.length);
  for (let i = 0; i < indices.length; i++) out[i] = table[indices[i] as number] ?? '';
  return out;
}
