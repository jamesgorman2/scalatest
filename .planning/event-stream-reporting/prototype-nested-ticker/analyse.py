# PROTOTYPE - throwaway. Replays one recorded pseudo-terminal run (runs/<name>.chunks.jsonl: one
# [epoch ms, text] per chunk) through pyte, 160x40, and measures:
#  - the summary over time: every STEP ms it renders the screen and classifies the command's own
#    prompt line (key `1`) against the latest value the command set through Task.log.ticker
#    (runs/<name>-ticker.tsv), allowing TOL ms for Mill's 100 ms redraw interval and delivery;
#  - Task.log lines shown intact in the final rendering (scrollback included) against those written
#    (runs/<name>-console.txt), and stale prompt rows left in the scrollback.
# Usage: python analyse.py <name> [--dump-snapshots]
# Appends a block to runs/summary.txt and writes runs/<name>.timeline.tsv and runs/<name>.rendered.txt.
import json, re, sys
import pyte

STEP, TOL, BURST = 250, 200, 10
name = sys.argv[1]
dump = '--dump-snapshots' in sys.argv
meta = json.load(open(f'runs/{name}.meta.json'))
COLS, ROWS = meta['cols'], meta['rows']

def tsv(path):
    try: return [l.rstrip('\n').split('\t') for l in open(path, encoding='utf-8') if l.strip()]
    except FileNotFoundError: return []

chunks = [json.loads(l) for l in open(f'runs/{name}.chunks.jsonl', encoding='utf-8')]
ticks = [(int(t), int(n), v) for t, n, v in tsv(f'runs/{name}-ticker.tsv')]
markers = {m: int(t) for t, m in tsv(f'runs/{name}-markers.tsv')}
written = [l.rstrip() for l in open(f'runs/{name}-console.txt', encoding='utf-8') if l.strip()] \
    if tsv(f'runs/{name}-console.txt') else []

class Screen(pyte.HistoryScreen):
    # The pseudo-console sends some cursor moves with a second parameter, which pyte rejects.
    def cursor_forward(self, count=None, *_): super().cursor_forward(count)
    def cursor_back(self, count=None, *_): super().cursor_back(count)
    def cursor_up(self, count=None, *_): super().cursor_up(count)
    def cursor_down(self, count=None, *_): super().cursor_down(count)

screen = Screen(COLS, ROWS, history=100000, ratio=0.001)
stream = pyte.Stream(screen)

HEADER = re.compile(r'^(\d+)/(\d+)\+?(, [^\]]*)?\] ')
# The command's prompt row: `1] nt.test <seconds>s <detail>`. The bare `1] nt.test` is the log line
# Mill prints once when the command starts.
CMD = re.compile(r'^1\] nt\.(test|tiny)\b(?! *$)')
# Key 1 showing another task's message, e.g. a nested task numbered 1.
OTHER1 = re.compile(r'^1\] (?!nt\.test\b|nt\.tiny\b|NTL )\S+( \d+s)?')
NTS = re.compile(r'NTS#(\d{4})')
FINAL = re.compile(r'\d+/\d+\+?, (\d+ FAILED|SUCCESS)\]')

def display(): return [screen.buffer[y] for y in range(ROWS)]
def row_text(line): return ''.join(line[x].data for x in range(COLS)).rstrip()

def classify(rows, T):
    """State of the command's prompt line in one rendered screen at time T."""
    expected = max([n for t, n, _ in ticks if t <= T - TOL], default=0)
    latest = max([n for t, n, _ in ticks if t <= T], default=0)
    cmd = [r for r in rows if CMD.match(r)]
    other1 = [r for r in rows if OTHER1.match(r)]
    if other1 and not cmd: return 'other-text', None, other1[-1][:100]
    if not cmd:
        if any('more threads' in r for r in rows):
            return 'hidden', None, next(r for r in rows if 'more threads' in r)[:80]
        hdr = [r for r in rows if HEADER.match(r)]
        return ('absent' if hdr else 'noprompt'), None, (hdr[-1][:60] if hdr else '')
    m = NTS.search(cmd[-1])
    if not m: return 'no-detail', None, cmd[-1][:100]
    n = int(m.group(1))
    if n >= expected: return 'current', n, ''
    return 'stale', n, f'shows {n}, expected >= {expected} (latest {latest})'

start = markers.get('execute-start', chunks[0][0]); ret = markers.get('execute-returned', chunks[-1][0])
end = markers.get('command-end', chunks[-1][0])
T = start - 1000
snaps = []
i = 0
while T <= end + 1500:
    while i < len(chunks) and chunks[i][0] <= T:
        stream.feed(chunks[i][1]); i += 1
    # Settle: finish a burst in progress (chunks less than BURST ms apart), so that a snapshot does
    # not cut a redraw in half.
    while 0 < i < len(chunks) and chunks[i][0] - chunks[i - 1][0] < BURST:
        stream.feed(chunks[i][1]); i += 1
    rows = [row_text(l) for l in display()]
    state, n, info = classify(rows, T)
    snaps.append((T, state, n, info))
    if dump: print(f'--- {T - start:+7d} ms {state} {info}\n' + '\n'.join(r for r in rows if r))
    T += STEP
while i < len(chunks):
    stream.feed(chunks[i][1]); i += 1

with open(f'runs/{name}.timeline.tsv', 'w', encoding='utf-8') as f:
    f.write('ms_from_execute_start\tstate\tshown\tinfo\n')
    for T, s, n, info in snaps: f.write(f'{T - start}\t{s}\t{n if n is not None else ""}\t{info}\n')

def intervals(sel):
    out, cur = [], None
    for T, s, n, info in sel:
        if cur and cur[2] == s: cur[1] = T
        else:
            if cur: out.append(cur)
            cur = [T, T, s, info]
    if cur: out.append(cur)
    return out

nested = [s for s in snaps if start <= s[0] <= ret]
after = [s for s in snaps if ret < s[0] <= end]
cur = sum(1 for s in nested if s[1] == 'current')
report = [f'{name}: {meta["terminal"]} {COLS}x{ROWS} NT_ROUTE={meta["route"]} {" ".join(meta["args"])}',
          f'  nested evaluation {(ret - start) / 1000:.1f} s; summary current in {cur} of {len(nested)} snapshots'
          f' ({100 * cur / max(1, len(nested)):.0f}%); ticker calls {len(ticks)}']
counts = {}
for s in nested: counts[s[1]] = counts.get(s[1], 0) + 1
report.append('  nested states: ' + ', '.join(f'{k}={v}' for k, v in sorted(counts.items())))
gaps = [g for g in intervals(nested) if g[2] != 'current']
report.append(f'  non-current intervals: {len(gaps)}, of which lasting 2 or more snapshots: '
              f'{sum(1 for a, b, s, _ in gaps if b > a)}')
for a, b, s, info in intervals(nested):
    if s != 'current':
        report.append(f'    {s:10s} {(a - start) / 1000:6.2f}s..{(b - start) / 1000:6.2f}s  {info}')
acounts = {}
for s in after: acounts[s[1]] = acounts.get(s[1], 0) + 1
report.append(f'  after execute returned ({(end - ret) / 1000:.1f} s to command end): ' +
              ', '.join(f'{k}={v}' for k, v in sorted(acounts.items())))

# Final header strings seen in the stream, with time relative to execute-returned and command-end.
plain_chunks = [(t, re.sub(r'\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07', '', d)) for t, d in chunks]
finals = [(t, m.group(0)) for t, d in plain_chunks for m in FINAL.finditer(d)]
for t, s in finals:
    report.append(f'  final header "{s}" in stream at {(t - ret) / 1000:+.2f}s from execute-returned,'
                  f' {(t - end) / 1000:+.2f}s from command-end')

# Final rendering, scrollback included, joined where the terminal wrapped a row.
rows = [row_text(l) for l in screen.history.top] + [row_text(l) for l in display()]
lines = [r for r in rows]
while lines and not lines[-1]: lines.pop()
open(f'runs/{name}.rendered.txt', 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
if written:
    flat = re.sub(r'\s+', '', ''.join(rows))
    shown = [l for l in written if re.sub(r'\s+', '', l) in flat]
    missing = [l for l in written if l not in shown]
    # Prompt rows left in the scrollback: headers (other than Mill's final one), rows with the summary,
    # and task rows with a seconds suffix. Mill's final header, printed when it closes the prompt, is not stale.
    stale = [l for l in lines if not FINAL.search(l) and (HEADER.match(l) or NTS.search(l) or
             re.match(r'^\d+(-\d+)*\] \S+ \d+s( |$)', l))]
    report.append(f'  Task.log lines written={len(written)} shown intact={len(shown)} stale prompt rows={len(stale)}')
    raw = ''.join(d for _, d in chunks)
    starts, pos = [], 0
    for t, d in chunks: starts.append((pos, t)); pos += len(d)
    def time_at(p): return max((t for s, t in starts if s <= p), default=0)
    with open(f'runs/{name}.lost.txt', 'w', encoding='utf-8') as lost:
        for l in missing:
            inraw = re.sub(r'\s+', '', l) in re.sub(r'\s+', '', ''.join(d for _, d in plain_chunks))
            report.append(f'    not shown: {l[:100]}  (its text in the stream: {inraw})')
            # The raw bytes around the line, or around where its neighbours are, escapes made visible.
            key = l[:8]  # "NTL 0123"
            p = raw.find(key)
            if p < 0:
                n = int(l[4:8]); p = max(raw.find(f'NTL {n - 1:04d}'), 0)
            lost.write(f'=== {l}\n  in stream: {inraw}; at {time_at(p) - start} ms from execute-start\n')
            lost.write(repr(raw[max(0, p - 600):p + 900]) + '\n\n')
    for l in stale[:3]: report.append('    stale: ' + l[:120])
print('\n'.join(report))
open('runs/summary.txt', 'a', encoding='utf-8').write('\n'.join(report) + '\n')
