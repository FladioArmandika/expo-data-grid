import { decodeTextColumn, encodeTextColumn } from '../encoders';

describe('encodeTextColumn', () => {
  it('dedupes into a table and round-trips', () => {
    const values = ['AAPL', 'MSFT', 'AAPL', 'GOOG', 'MSFT', ''];
    const { indices, table } = encodeTextColumn(values);
    expect(table).toEqual(['AAPL', 'MSFT', 'GOOG', '']);
    expect(Array.from(indices)).toEqual([0, 1, 0, 2, 1, 3]);
    expect(decodeTextColumn(indices, table)).toEqual(values);
  });

  it('handles empty input', () => {
    const { indices, table } = encodeTextColumn([]);
    expect(indices.length).toBe(0);
    expect(table).toEqual([]);
  });
});
