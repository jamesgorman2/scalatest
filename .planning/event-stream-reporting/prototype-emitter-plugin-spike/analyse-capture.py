# PROTOTYPE - throwaway. Analyses the runs saved by run-capture.ps1 / run-capture.sh.
# Usage: python analyse-capture.py runs/capture/<platform>
# Per run: (1) worker lines on the console; (2) for the routes, each captured worker stream's order,
# content and tie to its worker folder; (3) per fork group, against testForked of the same mode,
# outcome and round: classes run, duplicates, workers, JVM arguments, environment, working
# directory, exit status, task result and the JUnit XML report.
import collections, glob, json, os, re, sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
_out = open(os.path.join(root, 'summary.txt'), 'w', encoding='utf-8')
_print = print


def print(*a, **k):
    _print(*a, **k)
    _print(*a, **k, file=_out)


ANSI = re.compile(r'\x1b\[[0-9;?]*[A-Za-z]')
PREFIX = re.compile(r'^\d+(-\d+)*\] ?')
MARKERS = [('SEQ-OUT', 'SEQ-OUT'), ('SEQ-ERR', 'SEQ-ERR'), ('CHILD-OUT', 'CHILD-OUT'), ('CHILD-ERR', 'CHILD-ERR'),
           ('jvm-warning', 'Server VM warning'), ('Running Test Class', 'Running Test Class'),
           ('scalatest-summary', 'Run completed in'), ('scalatest-suite', re.compile(r'^G\d\d:$')),
           ('scalatest-test', re.compile(r'^- t\d')), ('scalatest-failed', '*** FAILED ***')]


def read(p, binary=False):
    if not os.path.exists(p):
        return b'' if binary else ''
    with open(p, 'rb') as f:
        b = f.read()
    return b if binary else b.decode('utf-8', 'replace')


def clean(line):
    return PREFIX.sub('', ANSI.sub('', line)).rstrip('\r')


def marker_hits(lines):
    c = collections.Counter()
    for l in lines:
        for name, m in MARKERS:
            if (m.search(l) if hasattr(m, 'search') else m in l):
                c[name] += 1
    return c


def cases():
    out = collections.defaultdict(dict)
    for p in sorted(glob.glob(os.path.join(root, '*.exit'))):
        name = os.path.basename(p)[:-5]
        mode, outcome, cmd, rnd = name.split('-')
        out[(mode, outcome, rnd)][cmd] = name
    return out


def workers(dest):
    """Per worker folder holding out.json: group, worker, classes run, pid and normalised info."""
    res = []
    for oj in sorted(glob.glob(os.path.join(dest, '*', 'worker-*', 'out.json'))):
        wf = os.path.dirname(oj)
        group = os.path.basename(os.path.dirname(wf))
        done, results = json.loads(read(oj))
        classes = collections.Counter(r['fullyQualifiedName'] for r in results)
        infos = glob.glob(os.path.join(wf, 'sandbox', 'worker-info-*.txt'))
        info = read(infos[0]) if infos else ''
        res.append(dict(group=group, worker=os.path.basename(wf), folder=wf, classes=set(classes),
                        results=results, info=info, ninfo=len(infos)))
    return res


def norm_info(info, dest, folder):
    lines = []
    for l in info.splitlines():
        if l.startswith('pid\t'):
            continue
        for p, rep in ((folder, '<WORKER>'), (os.path.abspath(dest), '<DEST>')):
            for v in {p, p.replace('\\', '/')}:
                l = l.replace(v, rep)
        l = re.sub(r'run-\d+\.jar', 'run-<N>.jar', l)
        # MILL_PARAMS is set by the launcher to the command line, which names the task run.
        if l.startswith('env	MILL_PARAMS='):
            l = re.sub(r'gamma\.test\.\w+', 'gamma.test.<cmd>', l)
        lines.append(l)
    return '\n'.join(lines)


def abs_dest(name):
    """The dest path Mill used, recovered from a worker-info userDir line."""
    for f in glob.glob(os.path.join(root, name + '-dest', '*', 'worker-*', 'sandbox', 'worker-info-*.txt')):
        for l in read(f).splitlines():
            if l.startswith('userDir\t'):
                ud = l.split('\t', 1)[1]
                return re.split(r'[\\/]group-', ud)[0]
    return None


def xml_canon(p):
    if not os.path.exists(p):
        return None, collections.Counter()
    t = ET.parse(p).getroot()
    cases_ = collections.Counter()

    def strip(e):
        for a in ('time', 'timestamp'):
            e.attrib.pop(a, None)
        if e.text:
            e.text = e.text.strip()
        for c in e:
            strip(c)
        e[:] = sorted(e, key=lambda c: (c.tag, sorted(c.attrib.items())))
    strip(t)
    for tc in t.iter('testcase'):
        cases_[(tc.get('classname'), tc.get('name'))] += 1
    return ET.tostring(t, encoding='unicode'), cases_


def task_result(name):
    j = read(os.path.join(root, name + '.json'))
    err = [clean(l) for l in read(os.path.join(root, name + '.err')).splitlines()]
    fail = []
    for i, l in enumerate(err):
        if 'tests failed' in l:
            fail = [re.sub(r'gamma\.test\.\w+', 'gamma.test.<cmd>', l)] + [x for x in err[i + 1:i + 3] if x.startswith('  ')]
    if j:
        v = json.loads(j).get('value')
        if v:
            rs = sorted((r['fullyQualifiedName'], r['selector'], r['status']) for r in v['results'])
            msg = ANSI.sub('', v['msg'])
            tests = sum(int(x) for x in re.findall(r'Total number of tests run: (\d+)', msg))
            suites = sum(int(x) for x in re.findall(r'Suites: completed (\d+)', msg))
            return dict(kind='value', results=rs, tests=tests, suites=suites, fail=fail)
    return dict(kind='no value', results=None, fail=fail)


def check_console(name):
    out = [clean(l) for l in read(os.path.join(root, name + '.out')).splitlines()]
    err = [clean(l) for l in read(os.path.join(root, name + '.err')).splitlines()]
    return marker_hits(out + err), out + err


def check_capture(name, console_lines):
    dest = os.path.join(root, name + '-dest')
    cap = os.path.join(dest, 'captured')
    index = [l.split('\t') for l in read(os.path.join(cap, 'index.tsv')).splitlines() if l]
    problems, notes = [], []
    captured_lines = set()
    per = []
    for label, folder, thread in index:
        o = read(os.path.join(cap, label + '.out'), True).decode('utf-8', 'replace')
        e = read(os.path.join(cap, label + '.err'), True).decode('utf-8', 'replace')
        if not o and not e:
            per.append(f'{label}: no bytes (no JVM spawned)')
            continue
        ol, el = o.splitlines(), e.splitlines()
        captured_lines.update(clean(l) for l in ol + el if clean(l).strip())
        info = []
        for stream, lines, tag in (('out', ol, 'SEQ-OUT'), ('err', el, 'SEQ-ERR')):
            seq = [(m.group(1), int(m.group(2))) for m in (re.search(tag + r' pid=(\d+) n=(\d+)', l) for l in lines) if m]
            pids = {p for p, _ in seq}
            ns = [n for _, n in seq]
            ok = ns == list(range(1, len(ns) + 1))
            if not ok or len(pids) != 1:
                problems.append(f'{label}.{stream}: order/pid problem pids={pids} first-bad={next((i for i, n in enumerate(ns) if n != i + 1), None)}')
            info.append(f'{stream}: {len(lines)} lines, {tag} 1..{len(ns)} in order={ok} pids={sorted(pids)}')
        hits = marker_hits([clean(l) for l in ol]), marker_hits([clean(l) for l in el])
        ran = [l.split()[-1] for l in el if l.startswith('Running Test Class')]
        child_out = sum(1 for l in ol if l.startswith('CHILD-OUT'))
        child_err = sum(1 for l in el if l.startswith('CHILD-ERR'))
        warn = sum(1 for l in el if 'Server VM warning' in l)
        summ = sum(1 for l in ol if 'Run completed in' in clean(l))
        # Tie: label -> folder (from the route) -> worker-info pid and out.json classes.
        lf = os.path.basename(folder.replace('\\', '/').rstrip('/'))
        gf = os.path.basename(os.path.dirname(folder.replace('\\', '/').rstrip('/')))
        wdir = os.path.join(dest, gf, lf)
        infos = glob.glob(os.path.join(wdir, 'sandbox', 'worker-info-*.txt'))
        ipid = {os.path.basename(f)[12:-4] for f in infos}
        spid = {m.group(1) for m in (re.search(r'SEQ-(?:OUT|ERR) pid=(\d+)', l) for l in ol + el) if m}
        oj = os.path.join(wdir, 'out.json')
        jcls = sorted({r['fullyQualifiedName'] for r in json.loads(read(oj))[1]}) if os.path.exists(oj) else []
        tie = (ipid == spid) and sorted(set(ran)) == jcls
        if not tie:
            problems.append(f'{label}: tie failed info-pid={ipid} seq-pid={spid} ran={ran} out.json={jcls}')
        if not (child_out == child_err == len(ran) and warn == 1 and summ == 1):
            problems.append(f'{label}: child-out={child_out} child-err={child_err} classes={len(ran)} jvm-warning={warn} scalatest-summary={summ}')
        per.append(f'{label} -> {gf}/{lf} thread={thread}: {"; ".join(info)}; classes={ran}; '
                   f'child out/err={child_out}/{child_err}; jvm-warning={warn}; scalatest-summary={summ}; tie={tie}')
    leaked = [l for l in console_lines if l.strip() and l in captured_lines]
    return per, problems, leaked


print(f'# {root}\n')
totals = collections.Counter()
for (mode, outcome, rnd), cmds in sorted(cases().items()):
    base = cmds.get('testForked')
    bdest = os.path.join(root, base + '-dest')
    bws = workers(bdest)
    babs = abs_dest(base)
    bres = task_result(base)
    bxml, bcases = xml_canon(os.path.join(bdest, 'test-report.xml'))
    print(f'## {mode} {outcome} round {rnd}')
    for cmd in ('testForked', 'routeCtx', 'routeQueue'):
        name = cmds.get(cmd)
        if not name:
            continue
        exitc = read(os.path.join(root, name + '.exit')).strip()
        hits, lines = check_console(name)
        print(f'### {cmd} ({name}) exit={exitc}')
        print(f'  console marker lines: {dict(hits) or "none"}')
        dest = os.path.join(root, name + '-dest')
        ws = workers(dest)
        dups = collections.Counter()
        groups = collections.defaultdict(list)
        for w in ws:
            groups[w['group']].append(w)
        for g, gw in sorted(groups.items()):
            c = collections.Counter(x for w in gw for x in w['classes'])
            d = sorted(k for k, v in c.items() if v > 1)
            dups[g] = len(d)
            print(f'  group {g}: workers={len(gw)} ({",".join(w["worker"] for w in gw)}) classes={len(c)} duplicated={d or "none"}')
        if dups and sum(dups.values()):
            totals[(cmd, mode, 'dup-runs')] += 1
        totals[(cmd, mode, 'runs')] += 1
        if cmd != 'testForked':
            per, problems, leaked = check_capture(name, lines)
            for p in per:
                print('  captured ' + p)
            print(f'  capture problems: {problems or "none"}')
            print(f'  console lines equal to a captured line: {len(leaked)} {leaked[:5]}')
            totals[(cmd, 'leak-runs')] += bool(sum(hits.values()) or leaked)
            totals[(cmd, 'capture-problem-runs')] += bool(problems)
            # Comparison with testForked.
            diffs = []
            bg = collections.defaultdict(list)
            for w in bws:
                bg[w['group']].append(w)
            if set(groups) != set(bg):
                diffs.append(f'group folders {sorted(groups)} vs {sorted(bg)}')
            aabs = abs_dest(name)
            for g in sorted(set(groups) | set(bg)):
                a, b = groups.get(g, []), bg.get(g, [])
                ca, cb = set().union(*[w['classes'] for w in a]), set().union(*[w['classes'] for w in b])
                if ca != cb:
                    diffs.append(f'{g}: classes {sorted(ca)} vs {sorted(cb)}')
                if len(a) != len(b):
                    diffs.append(f'{g}: workers {len(a)} vs {len(b)}')
                na = {norm_info(w['info'], aabs, os.path.join(aabs, g, w['worker'])) for w in a}
                nb = {norm_info(w['info'], babs, os.path.join(babs, g, w['worker'])) for w in b}
                if na != nb:
                    la = set('\n'.join(na).splitlines()); lb = set('\n'.join(nb).splitlines())
                    diffs.append(f'{g}: worker info differs; route-only={sorted(la - lb)[:6]} testForked-only={sorted(lb - la)[:6]}')
                if any(w['ninfo'] != 1 for w in a + b):
                    diffs.append(f'{g}: a worker folder without exactly one worker-info file')
            if exitc != read(os.path.join(root, base + '.exit')).strip():
                diffs.append('exit status differs')
            res = task_result(name)
            for k in ('kind', 'results', 'tests', 'suites'):
                if res.get(k) != bres.get(k):
                    diffs.append(f'task result {k}: {str(res.get(k))[:120]} vs {str(bres.get(k))[:120]}')
            if res['fail'] != bres['fail']:
                diffs.append(f'failure message: {res["fail"]} vs {bres["fail"]}')
            axml, acases = xml_canon(os.path.join(dest, 'test-report.xml'))
            if axml != bxml:
                more = {k: (acases[k], bcases[k]) for k in set(acases) | set(bcases) if acases[k] != bcases[k]}
                diffs.append(f'JUnit XML differs (ignoring time, timestamp, order); testcase counts route vs testForked: {more}')
            print(f'  against testForked: {diffs or "same"}')
            # Differences that duplicate runs or a different worker count explain: the same
            # classes, results and JUnit testcases as sets, and nothing else differs.
            same_sets = (set(res['results'] or []) == set(bres['results'] or [])
                         and set(acases) == set(bcases))
            kinds_ok = all(re.search(r': workers \d+ vs \d+$|^task result (results|tests|suites)|^JUnit XML differs|^failure message', d)
                           for d in diffs)
            explained = bool(diffs) and same_sets and kinds_ok
            if diffs:
                print(f'  as sets (results, JUnit testcases) the same: {same_sets}; every difference is worker count or duplicate runs: {explained}')
            totals[(cmd, mode, 'differs-runs')] += bool(diffs)
            totals[(cmd, mode, 'differs-runs-not-explained-by-duplicates-or-worker-count')] += bool(diffs) and not explained
    print()

print('## totals')
for k, v in sorted(totals.items()):
    print(f'  {k}: {v}')
