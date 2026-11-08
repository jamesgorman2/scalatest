# PROTOTYPE - throwaway. Analyses every run in a runs folder.
# Usage: python analyse.py <runs folder>          (writes <runs folder>/summary.txt, summary.tsv and
#                                                  <name>.timeline.tsv per run)
# Run names: <terminal>-<mode>-<outcome>-<kind>-<route>, route `control` being Mill's own task.
#
# Per run:
# 1. The console (R-044, R-040). The recording (runs/<name>.chunks.jsonl: [epoch ms, text] per chunk)
#    is replayed through pyte at the terminal's size and the screen is rendered every 250 ms. In each
#    snapshot, the prompt is the block from the last header row (`<n>/<m>] ...`) to the foot of the
#    screen; in it a row is
#      - a worker prompt line: its key has three or more parts (`139-0-1]`, `1-139-0-1]`);
#      - a worker ticker: it names a test class (`gamma.G01`);
#      - a completion line: it holds `<n>/<m> completed`.
#    Over the whole stream, escapes removed, the same patterns are also counted, which catches a frame
#    drawn between snapshots. The final rendering, scrollback included, is searched for Mill's failed-test
#    list, ScalaTest's end-of-run summary and any worker output line.
# 2. Capture (R-042): each captured worker stream's SEQ-OUT / SEQ-ERR numbers run 1..N, and the PID on
#    it is the PID of the worker-info file in the folder its index line names.
# 3. Against the control of the same terminal, mode, outcome and kind (R-053): exit status, per fork
#    group the classes run, workers, duplicate runs and each worker's normalised JVM info (working
#    directory, arguments, environment), each worker's testargs, and the JUnit XML test cases.
import collections, glob, json, os, re, sys
import xml.etree.ElementTree as ET
import pyte

root = sys.argv[1]
STEP, BURST = 250, 10
_out = open(os.path.join(root, 'summary.txt'), 'w', encoding='utf-8')
_tsv = open(os.path.join(root, 'summary.tsv'), 'w', encoding='utf-8')


def say(*a):
    print(*a)
    print(*a, file=_out)


ESC = re.compile(r'\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(\x07|\x1b\\)|\x1b[()][0-9A-Za-z]|\x1b[=>78]')
HEADER = re.compile(r'^\d+/\d+\+?(, [^\]]*)?\] ')
KEYROW = re.compile(r'^(\d+(?:-\d+)*)\] ?(.*)$')
CLASS = re.compile(r'gamma\.G\d\d')
COMPLETED = re.compile(r'\d+/\d+ completed')
FAILED_LIST = re.compile(r'tests failed|^\s+gamma\.G03 t2\s*$')
SUMMARY = re.compile(r'Run completed in|Total number of tests run|Suites: completed|Tests: succeeded|All tests passed|TESTS? FAILED')
WORKER_OUT = ['SEQ-OUT', 'SEQ-ERR', 'CHILD-OUT', 'CHILD-ERR', 'Server VM warning', 'Running Test Class',
              'Run completed in', '- t1', '- t2', '- t3', 'G01:', 'G03:', 'G10:']


def read(p):
    if not os.path.exists(p):
        return ''
    with open(p, 'rb') as f:
        return f.read().decode('utf-8', 'replace')


class Screen(pyte.HistoryScreen):
    # The pseudo-console sends some cursor moves with a second parameter, which pyte rejects.
    def cursor_forward(self, count=None, *_): super().cursor_forward(count)
    def cursor_back(self, count=None, *_): super().cursor_back(count)
    def cursor_up(self, count=None, *_): super().cursor_up(count)
    def cursor_down(self, count=None, *_): super().cursor_down(count)


def prompt_block(rows):
    hdr = [i for i, r in enumerate(rows) if HEADER.match(r)]
    if not hdr:
        return []
    return [r for r in rows[hdr[-1] + 1:] if r]


def classify(block):
    worker = [r for r in block if (m := KEYROW.match(r)) and len(m.group(1).split('-')) >= 3]
    ticker = [r for r in block if CLASS.search(r)]
    done = [r for r in block if COMPLETED.search(r)]
    return worker, ticker, done


def console(name):
    meta = json.load(open(f'{root}/{name}.meta.json'))
    cols, rows_n = meta['cols'], meta['rows']
    chunks = [json.loads(l) for l in open(f'{root}/{name}.chunks.jsonl', encoding='utf-8')]
    screen = Screen(cols, rows_n, history=200000, ratio=0.001)
    stream = pyte.Stream(screen)

    def display():
        return [''.join(screen.buffer[y][x].data for x in range(cols)).rstrip() for y in range(rows_n)]

    snaps, i = [], 0
    t0 = chunks[0][0] if chunks else 0
    T = t0
    while chunks and T <= chunks[-1][0] + STEP:
        while i < len(chunks) and chunks[i][0] <= T:
            stream.feed(chunks[i][1]); i += 1
        while 0 < i < len(chunks) and chunks[i][0] - chunks[i - 1][0] < BURST:
            stream.feed(chunks[i][1]); i += 1
        block = prompt_block(display())
        w, t, d = classify(block)
        snaps.append((T - t0, len(block) > 0, w, t, d, block))
        T += STEP
    while i < len(chunks):
        stream.feed(chunks[i][1]); i += 1
    final = [''.join(l[x].data for x in range(cols)).rstrip() for l in screen.history.top] + display()
    while final and not final[-1]:
        final.pop()
    plain = ESC.sub('', ''.join(c[1] for c in chunks))
    with open(f'{root}/{name}.timeline.tsv', 'w', encoding='utf-8') as f:
        f.write('ms\tprompt\tworker_lines\tworker_tickers\tcompletion_lines\tprompt_rows\n')
        for ms, has, w, t, d, block in snaps:
            f.write(f'{ms:.0f}\t{int(has)}\t{len(w)}\t{len(t)}\t{len(d)}\t{" | ".join(block)[:400]}\n')
    open(f'{root}/{name}.rendered.txt', 'w', encoding='utf-8').write('\n'.join(final) + '\n')
    return meta, snaps, final, plain


def workers(dest):
    res = []
    for info in sorted(glob.glob(os.path.join(dest, '**', 'worker-info-*.txt'), recursive=True)):
        sandbox = os.path.dirname(info)
        wf = os.path.dirname(sandbox)
        oj = os.path.join(wf, 'out.json')
        classes = []
        if os.path.exists(oj):
            _, results = json.loads(read(oj))
            classes = sorted({r['fullyQualifiedName'] for r in results})
        rel = os.path.relpath(wf, dest).replace('\\', '/')
        group = rel.split('/')[0] if '/' in rel else '.'
        pid = re.search(r'worker-info-(\d+)', info).group(1)
        res.append(dict(group=group, rel=rel, folder=wf, classes=set(classes), info=read(info), pid=pid,
                        testargs=read(os.path.join(wf, 'testargs'))))
    return res


def norm_path(l):
    l = re.sub(r'[A-Za-z]:[\\/][^\t;,"]*?(testForked|testOnly)\.dest', '<DEST>', l)
    l = re.sub(r'[A-Za-z]:[\\/][^\t;,"]*?modules[\\/][\w-]+[\\/][\w.]+test', '<DEST>', l)
    l = re.sub(r'/[^\t;,"]*?(testForked|testOnly)\.dest', '<DEST>', l)
    l = re.sub(r'/[^\t;,"]*?modules/[\w-]+/[\w.]+test', '<DEST>', l)
    l = re.sub(r'run-\d+\.jar', 'run-<N>.jar', l)
    return l.replace('\\\\', '/').replace('\\', '/')


def norm(info):
    out = []
    for l in info.splitlines():
        if l.startswith('pid\t'):
            continue
        if l.startswith('env\tMILL_PARAMS='):
            l = 'env\tMILL_PARAMS=<launcher command line>'
        out.append(norm_path(l))
    return '\n'.join(sorted(out))


def norm_testargs(t):
    if not t:
        return ''
    j = json.loads(t)
    for k in ('outputPath', 'resultPath'):
        j.pop(k, None)
    gs = j.get('globSelectors')
    if isinstance(gs, list) and len(gs) == 2 and isinstance(gs[1], list):
        j['globSelectors'] = [gs[0], ['<starting class>' if gs[1][0] else None, '<queue>', '<claim>']]
    return norm_path(json.dumps(j, sort_keys=True))


def xml_cases(dest):
    p = os.path.join(dest, 'test-report.xml')
    if not os.path.exists(p):
        return None
    c = collections.Counter()
    for tc in ET.parse(p).getroot().iter('testcase'):
        c[(tc.get('classname'), tc.get('name'), len(list(tc.iter('failure'))))] += 1
    return c


def capture_check(dest, ws):
    cap = os.path.join(dest, 'captured')
    if not os.path.isdir(cap):
        return None
    index = {}
    for l in read(os.path.join(cap, 'index.tsv')).splitlines():
        if l.strip():
            label, folder = l.split('\t')[:2]
            index[label] = os.path.normcase(os.path.normpath(folder))
    by_folder = {}
    for w in ws:
        by_folder[os.path.basename(os.path.dirname(w['folder'])) + '/' + os.path.basename(w['folder'])] = w
    bad_order, bad_pid, with_jvm, empty = [], [], 0, 0
    for label, folder in index.items():
        out, err = read(os.path.join(cap, label + '.out')), read(os.path.join(cap, label + '.err'))
        if not out and not err:
            empty += 1
            continue
        with_jvm += 1
        for text, pat in ((out, r'SEQ-OUT pid=(\d+) n=(\d+)'), (err, r'SEQ-ERR pid=(\d+) n=(\d+)')):
            seqs = collections.defaultdict(list)
            for m in re.finditer(pat, text):
                seqs[m.group(1)].append(int(m.group(2)))
            if len(seqs) != 1 or any(v != list(range(1, len(v) + 1)) for v in seqs.values()):
                bad_order.append(label)
            key = '/'.join(folder.replace('\\', '/').split('/')[-2:])
            w = next((w for k, w in by_folder.items() if k.lower() == key.lower()), None)
            if w is None or set(seqs) != {w['pid']}:
                bad_pid.append(label)
    return dict(workers_captured=with_jvm, labels_without_bytes=empty, out_of_order=sorted(set(bad_order)),
                pid_mismatch=sorted(set(bad_pid)), workers_with_info=len(ws))


names = sorted(os.path.basename(p)[:-len('.meta.json')] for p in glob.glob(f'{root}/*.meta.json'))
_tsv.write('run\texit\tsnapshots\tworker_line_snaps\tworker_ticker_snaps\tcompletion_snaps\tstream_class_names'
           '\tstream_completed\tfailed_list\tscalatest_summary\tworker_output_lines\tcapture_ok\tr053\n')
results = {}
for name in names:
    parts = name.split('-', 4)
    if len(parts) < 5:
        continue
    terminal, mode, outcome, kind, route = parts
    exitc = read(f'{root}/{name}.exit').strip()
    meta, snaps, final, plain = console(name)
    say(f'== {name}: exit={exitc} terminal={meta["terminal"]} {meta["cols"]}x{meta["rows"]} {" ".join(meta["args"])}')
    n = len(snaps)
    sw = [s for s in snaps if s[2]]
    st = [s for s in snaps if s[3]]
    sd = [s for s in snaps if s[4]]
    say(f'   snapshots {n} (every {STEP} ms), with a prompt {sum(1 for s in snaps if s[1])}; '
        f'with a worker prompt line {len(sw)}; with a worker ticker {len(st)}; with a completion line {len(sd)}')
    for label, sel, idx in (('worker line', sw, 2), ('worker ticker', st, 3), ('completion', sd, 4)):
        if sel:
            say(f'     first {label} at {sel[0][0] / 1000:.2f}s: {sel[0][idx][0][:110]}')
    goal_rows = collections.Counter(re.sub(r' \d+s\b', ' <s>', r)[:110] for s in snaps for r in s[5]
                                    if (m := KEYROW.match(r)) and len(m.group(1).split('-')) < 3)
    for r, c in goal_rows.most_common(4):
        say(f'     task row x{c}: {r}')
    stream_classes = len(CLASS.findall(plain))
    stream_completed = len(COMPLETED.findall(plain))
    say(f'   whole stream: test class names {stream_classes}; "<n>/<m> completed" {stream_completed}')
    fl = [l for l in final if FAILED_LIST.search(l)]
    sm = [l for l in final if SUMMARY.search(l)]
    wo = [l for l in final if any(m in l for m in WORKER_OUT)]
    say(f'   final rendering: failed-test list lines {len(fl)}; ScalaTest summary lines {len(sm)}; '
        f'worker output lines {len(wo)}')
    for l in (fl + sm + wo)[:3]:
        say(f'     e.g. {l[:120]}')
    dests = sorted(glob.glob(f'{root}/{name}-dest*'))
    ws = workers(dests[0]) if dests else []
    cap = capture_check(dests[0], ws) if dests and route != 'control' else None
    if cap is not None:
        say(f'   capture: {cap}')
    r053 = '-'
    ctrl = f'{terminal}-{mode}-{outcome}-{kind}-control'
    if route != 'control' and os.path.exists(f'{root}/{ctrl}.meta.json'):
        cexit = read(f'{root}/{ctrl}.exit').strip()
        cd = sorted(glob.glob(f'{root}/{ctrl}-dest*'))
        cws = workers(cd[0]) if cd else []
        diffs, variation = [], []
        if cexit != exitc:
            diffs.append(f'exit {exitc} vs {cexit}')
        xa, xb = xml_cases(dests[0]) if dests else None, xml_cases(cd[0]) if cd else None
        xml_same = xa == xb
        if not xml_same:
            if xa and xb and set(xa) == set(xb):
                variation.append('JUnit XML case counts (class run twice)')
            else:
                diffs.append('JUnit XML DIFFERS')
        ga, gb = collections.defaultdict(list), collections.defaultdict(list)
        for w in ws: ga[w['group']].append(w)
        for w in cws: gb[w['group']].append(w)
        lines = []
        for g in sorted(set(ga) | set(gb)):
            ca = set().union(*[w['classes'] for w in ga[g]]) if ga[g] else set()
            cb = set().union(*[w['classes'] for w in gb[g]]) if gb[g] else set()
            dup_a = sum(len(w['classes']) for w in ga[g]) - len(ca)
            dup_b = sum(len(w['classes']) for w in gb[g]) - len(cb)
            na, nb = {norm(w['info']) for w in ga[g]}, {norm(w['info']) for w in gb[g]}
            ta, tb = {norm_testargs(w['testargs']) for w in ga[g]}, {norm_testargs(w['testargs']) for w in gb[g]}
            lines.append(f'   group {g}: classes {"same" if ca == cb else "DIFFER"} ({len(ca)} vs {len(cb)}); workers '
                         f'{len(ga[g])} vs {len(gb[g])}; duplicate runs {dup_a} vs {dup_b}; JVM info '
                         f'{"same" if na == nb else "DIFFERS"}; testargs {"same" if ta == tb else "DIFFER"}')
            if ca != cb: diffs.append(f'{g} classes')
            if len(ga[g]) != len(gb[g]): variation.append(f'workers {len(ga[g])} vs {len(gb[g])}')
            if dup_a != dup_b: variation.append(f'duplicate runs {dup_a} vs {dup_b}')
            strip = lambda ns: {'\n'.join(l for l in x.splitlines() if not l.startswith('userDir')) for x in ns}
            if na != nb:
                if strip(na) == strip(nb):
                    variation.append('worker folder on one side')
                else:
                    diffs.append(f'{g} JVM info')
                la, lb = set('\n'.join(na).splitlines()), set('\n'.join(nb).splitlines())
                for l in sorted(la - lb)[:4]: lines.append(f'      route only:   {l[:180]}')
                for l in sorted(lb - la)[:4]: lines.append(f'      control only: {l[:180]}')
            if ta != tb:
                diffs.append(f'{g} testargs')
                la, lb = set(ta), set(tb)
                for l in sorted(la - lb)[:1]: lines.append(f'      route testargs:   {l[:300]}')
                for l in sorted(lb - la)[:1]: lines.append(f'      control testargs: {l[:300]}')
        say(f'   vs {ctrl}: exit {"same" if cexit == exitc else "DIFFERS"}; JUnit XML '
            f'{"same" if xml_same else "differs"}; groups {sorted(ga)} vs {sorted(gb)}')
        for l in lines: say(l)
        r053 = ('DIFFERS: ' + '; '.join(diffs)) if diffs else (
            'same' if not variation else 'same except: ' + '; '.join(sorted(set(variation))))
    cap_ok = '-' if cap is None else ('ok' if not cap['out_of_order'] and not cap['pid_mismatch']
                                      and cap['workers_captured'] == cap['workers_with_info'] else f'BAD {cap}')
    _tsv.write(f'{name}\t{exitc}\t{n}\t{len(sw)}\t{len(st)}\t{len(sd)}\t{stream_classes}\t{stream_completed}\t'
               f'{len(fl)}\t{len(sm)}\t{len(wo)}\t{cap_ok}\t{r053}\n')
