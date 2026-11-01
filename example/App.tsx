import {
  ExpoDataGridModule,
  SpikeGridView,
  runLoadBenchmark,
  useGridData,
  type BenchmarkResult,
  type LoadBenchmarkResult,
  type SpikeRenderer,
} from '@fladioarmandika/expo-data-grid';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  Platform,
  Pressable,
  ScrollView,
  Share,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';

const RENDERERS: SpikeRenderer[] = Platform.OS === 'android' ? ['A', 'B', 'C'] : ['A', 'B'];
const ROW_OPTIONS = [1_000, 10_000, 100_000, 1_000_000];
const COLUMNS = 50;
const RUNS_PER_RENDERER = 3;

type RunRecord = BenchmarkResult & { run: number };

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function median(values: number[]): number {
  const s = [...values].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? (s[mid] as number) : ((s[mid - 1] as number) + (s[mid] as number)) / 2;
}

export default function App() {
  const dark = useColorScheme() === 'dark';
  const data = useGridData();
  const device = useMemo(() => ExpoDataGridModule.getDeviceInfo(), []);

  const [renderer, setRenderer] = useState<SpikeRenderer>('A');
  const [rows, setRows] = useState(1_000_000);
  const [loaded, setLoaded] = useState<{ rows: number; fillMs: number } | null>(null);
  const [mountKey, setMountKey] = useState(0);
  const [benchmarkRunId, setBenchmarkRunId] = useState(0);
  const [busy, setBusy] = useState<string | null>(null);
  const [results, setResults] = useState<RunRecord[]>([]);
  const [loadResult, setLoadResult] = useState<LoadBenchmarkResult | null>(null);

  const firstFrame = useRef<((ms: number) => void) | null>(null);
  const benchmarkDone = useRef<((r: BenchmarkResult) => void) | null>(null);

  const fill = useCallback(
    async (n: number) => {
      setBusy(`Filling ${n.toLocaleString()} × ${COLUMNS} natively…`);
      const fillMs = await data.fillSyntheticAsync(n, COLUMNS);
      setLoaded({ rows: n, fillMs });
      setMountKey((k) => k + 1);
      setBusy(null);
    },
    [data]
  );

  const runOne = useCallback(
    async (r: SpikeRenderer, run: number) => {
      // Remount so time-to-first-frame includes creating the native view.
      const ttff = new Promise<number>((resolve) => (firstFrame.current = resolve));
      setRenderer(r);
      setMountKey((k) => k + 1);
      await Promise.race([ttff, sleep(5000)]);
      await sleep(1000);
      const result = new Promise<BenchmarkResult>((resolve) => (benchmarkDone.current = resolve));
      setBenchmarkRunId((id) => id + 1);
      const res = await result;
      setResults((prev) => [...prev, { ...res, run }]);
    },
    []
  );

  const runAll = useCallback(async () => {
    if (!loaded) return;
    setResults([]);
    for (const r of RENDERERS) {
      for (let run = 1; run <= RUNS_PER_RENDERER; run++) {
        setBusy(`Renderer ${r}, run ${run}/${RUNS_PER_RENDERER}`);
        await runOne(r, run);
        await sleep(500);
      }
    }
    setBusy(null);
  }, [loaded, runOne]);

  const runLoadTest = useCallback(async () => {
    setBusy('Load-path test…');
    await sleep(50);
    // Reuses the mounted store (a second 400 MB store would not fit on older phones).
    // Afterwards the grid shows the JS-generated columns.
    const res = runLoadBenchmark(data, { rows, numberColumns: COLUMNS, uniqueStrings: 10_000 });
    setLoadResult(res);
    setLoaded({ rows, fillMs: loaded?.fillMs ?? 0 });
    setMountKey((k) => k + 1);
    setBusy(null);
  }, [data, rows, loaded]);

  const summary = useMemo(() => {
    return RENDERERS.map((r) => {
      const runs = results.filter((x) => x.renderer === r);
      if (runs.length === 0) return null;
      return {
        renderer: r,
        name: runs[0]?.rendererName ?? r,
        runs: runs.length,
        avgFps: median(runs.map((x) => x.avgFps)),
        refreshRate: runs[0]?.refreshRate ?? 0,
        droppedFrames: median(runs.map((x) => x.droppedFrames)),
        worstFrameMs: median(runs.map((x) => x.worstFrameMs)),
        blankAvgPct: median(runs.map((x) => x.blankAvgPct)),
        blankMaxPct: median(runs.map((x) => x.blankMaxPct)),
        blankFramesPct: median(runs.map((x) => x.blankFramesPct)),
        peakResidentMB: median(runs.map((x) => x.peakResidentMB)),
        timeToFirstFrameMs: median(runs.map((x) => x.timeToFirstFrameMs)),
      };
    }).filter((x) => x !== null);
  }, [results]);

  const share = useCallback(() => {
    const payload = { device, rows: loaded?.rows, fillMs: loaded?.fillMs, summary, results, loadResult };
    Share.share({ message: JSON.stringify(payload, null, 2) });
  }, [device, loaded, summary, results, loadResult]);

  const fg = dark ? '#fff' : '#000';
  const sub = dark ? '#aaa' : '#555';

  return (
    <View style={[styles.root, { backgroundColor: dark ? '#000' : '#fff' }]}>
      <StatusBar style="auto" />
      <View style={styles.toolbar}>
        <Text style={[styles.title, { color: fg }]}>expo-data-grid · spike</Text>
        <Text style={[styles.small, { color: sub }]}>
          {device.model} · {device.osVersion} · {Math.round(device.totalMemoryMB)} MB RAM · {device.maxRefreshRate} Hz
          {device.isDebugBuild ? ' · DEBUG BUILD (numbers not valid)' : ' · release'}
        </Text>
        <Row label="Rows" color={fg}>
          {ROW_OPTIONS.map((n) => (
            <Chip key={n} label={n >= 1_000_000 ? '1M' : `${n / 1000}k`} active={rows === n} onPress={() => setRows(n)} />
          ))}
          <Chip label={loaded?.rows === rows ? 'Loaded' : 'Load'} active={false} onPress={() => fill(rows)} />
        </Row>
        <Row label="Renderer" color={fg}>
          {RENDERERS.map((r) => (
            <Chip key={r} label={r} active={renderer === r} onPress={() => { setRenderer(r); setMountKey((k) => k + 1); }} />
          ))}
        </Row>
        <Row label="Run" color={fg}>
          <Chip label="Benchmark once" active={false} onPress={() => setBenchmarkRunId((id) => id + 1)} />
          <Chip label={`All × ${RUNS_PER_RENDERER}`} active={false} onPress={runAll} />
          <Chip label="Load path" active={false} onPress={runLoadTest} />
          <Chip label="Share" active={false} onPress={share} />
        </Row>
        {busy ? <Text style={[styles.small, { color: sub }]}>{busy}</Text> : null}
        {loaded ? (
          <Text style={[styles.small, { color: sub }]}>
            Native fill: {loaded.rows.toLocaleString()} × {COLUMNS} in {loaded.fillMs.toFixed(0)} ms
          </Text>
        ) : null}
      </View>

      <View style={styles.grid}>
        {loaded ? (
          <SpikeGridView
            key={mountKey}
            style={StyleSheet.absoluteFill}
            data={data}
            renderer={renderer}
            frozenColumns={1}
            rowHeight={32}
            headerHeight={36}
            columnWidth={88}
            showPerfOverlay
            benchmarkRunId={benchmarkRunId}
            onFirstFrame={(e) => firstFrame.current?.(e.nativeEvent.timeToFirstFrameMs)}
            onBenchmarkResult={(e) => {
              const res = e.nativeEvent;
              if (benchmarkDone.current) {
                benchmarkDone.current(res);
                benchmarkDone.current = null;
              } else {
                setResults((prev) => [...prev, { ...res, run: 0 }]);
              }
            }}
          />
        ) : (
          <Text style={[styles.empty, { color: sub }]}>Pick a row count and tap Load.</Text>
        )}
      </View>

      <ScrollView style={styles.results} contentContainerStyle={{ padding: 12 }}>
        {summary.map((s) => (
          <Text key={s.renderer} style={[styles.mono, { color: fg }]}>
            {s.name} (median of {s.runs}): {s.avgFps.toFixed(1)}/{s.refreshRate.toFixed(0)} fps · drop {s.droppedFrames} · worst{' '}
            {s.worstFrameMs.toFixed(0)} ms · blank {s.blankAvgPct.toFixed(2)}% (max {s.blankMaxPct.toFixed(2)}%, {s.blankFramesPct.toFixed(1)}% of frames) · {s.peakResidentMB.toFixed(0)} MB · TTFF{' '}
            {s.timeToFirstFrameMs.toFixed(0)} ms
          </Text>
        ))}
        {loadResult ? (
          <Text style={[styles.mono, { color: fg }]}>
            Load path {loadResult.rows.toLocaleString()} rows: {loadResult.numberColumns} × Float64Array ({loadResult.numbersMB.toFixed(0)} MB) in{' '}
            {loadResult.numbersCallMs.toFixed(0)} ms (native copy {loadResult.numbersNativeCopyMs.toFixed(0)} ms, JS generate{' '}
            {loadResult.numbersGenerateMs.toFixed(0)} ms) · text column ({loadResult.uniqueStrings.toLocaleString()} unique) in{' '}
            {loadResult.textCallMs.toFixed(1)} ms (native copy {loadResult.textNativeCopyMs.toFixed(1)} ms)
          </Text>
        ) : null}
      </ScrollView>
    </View>
  );
}

function Row({ label, color, children }: { label: string; color: string; children: ReactNode }) {
  return (
    <View style={styles.row}>
      <Text style={[styles.rowLabel, { color }]}>{label}</Text>
      <View style={styles.chips}>{children}</View>
    </View>
  );
}

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} style={[styles.chip, active && styles.chipActive]}>
      <Text style={[styles.chipText, active && styles.chipTextActive]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, paddingTop: Platform.OS === 'ios' ? 54 : 32 },
  toolbar: { paddingHorizontal: 12, gap: 6, paddingBottom: 8 },
  title: { fontSize: 17, fontWeight: '700' },
  small: { fontSize: 11 },
  row: { flexDirection: 'row', alignItems: 'center' },
  rowLabel: { width: 70, fontSize: 12, fontWeight: '600' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, flex: 1 },
  chip: { paddingHorizontal: 10, paddingVertical: 5, borderRadius: 12, backgroundColor: '#8883' },
  chipActive: { backgroundColor: '#2563eb' },
  chipText: { fontSize: 12, color: '#888' },
  chipTextActive: { color: '#fff' },
  grid: { flex: 1 },
  empty: { textAlign: 'center', marginTop: 40 },
  results: { maxHeight: 160 },
  mono: { fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }), fontSize: 10, marginBottom: 6 },
});
