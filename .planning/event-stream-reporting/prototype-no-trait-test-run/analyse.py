# PROTOTYPE - throwaway. Analyses the runs saved by run.ps1 / run.sh.
# Usage: python analyse.py runs/<platform>
# Per run: (1) run output on the console; (2) for a route, what it captured, in order;
# (3) against the control of the same mode and outcome: exit status, failure line, per fork group
# the classes run and each worker's normalised JVM info, and the JUnit XML test cases.
import collections, glob, json, os, re, sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
_out = open(os.path.join(root, 'summary.txt'), 'w', encoding='utf-8')


def say(*a):
    print(*a)
    print(*a, file=_out)


ANSI = re.compile(r'\x1b\[[0-9;?]*[A-Za-z]')
PREFIX = re.compile(r'^\d+(-\d+)*\] ?')
MARKERS = ['SEQ-OUT', 'SEQ-ERR', 'CHILD-OUT', 'CHILD-ERR', 'Server VM warning', 'Running Test Class',
           'Run completed in', 'X-OUT', 'X-ERR', 'Y-OUT', 'Y-ERR', 'Z-OUT', 'Z-ERR']


def read(p):
    if not os.path.exists(p):
        return ''
    with open(p, 'rb') as f:
        return f.read().decode('utf-8', 'replace')


def clean(l):
    return PREFIX.sub('', ANSI.sub('', l)).rstrip('\r')


def hits(lines):
    c = collections.Counter()
    for l in lines:
        for m in MARKERS:
            if m in l:
                c[m] += 1
    return c


def fmt(c):
    return ' '.join(f'{k}={v}' for k, v in sorted(c.items())) or 'none'


def console(name):
    return [clean(l) for l in (read(f'{root}/{name}.out') + read(f'{root}/{name}.err')).splitlines()]


def workers(dest):
    res = []
    for info in sorted(glob.glob(os.path.join(dest, '**', 'worker-info-*.txt'), recursive=True)):
        sandbox = os.path.dirname(info)
        wf = os.path.dirname(sandbox)
        oj = os.path.join(wf, 'out.json')
        classes = set()
        if os.path.exists(oj):
            _, results = json.loads(read(oj))
            classes = {r['fullyQualifiedName'] for r in results}
        rel = os.path.relpath(wf, dest).replace('\\', '/')
        group = rel.split('/')[0] if '/' in rel else '.'
        res.append(dict(group=group, rel=rel, classes=classes, info=read(info)))
    return res


def norm(info):
    out = []
    for l in info.splitlines():
        if l.startswith('pid\t'):
            continue
        if l.startswith('userDir\t'):
            ud = l.split('\t', 1)[1].replace('\\', '/')
            l = 'userDir\t<DEST>' + re.sub(r'^.*?(/group-|/worker-|/sandbox)', r'\1', ud, count=1)
        l = re.sub(r'[A-Za-z]:[\\/][^\t;,"]*?(testForked|testOnly)\.dest', '<DEST>', l)
        l = re.sub(r'[A-Za-z]:[\\/][^\t;,"]*?modules[\\/]\w+[\\/][\w.]+test', '<DEST>', l)
        l = re.sub(r'/[^\t;,"]*?(testForked|testOnly)\.dest', '<DEST>', l)
        l = re.sub(r'/[^\t;,"]*?modules/\w+/[\w.]+test', '<DEST>', l)
        l = re.sub(r'run-\d+\.jar', 'run-<N>.jar', l)
        if l.startswith('env\tMILL_PARAMS='):
            l = 'env\tMILL_PARAMS=<launcher command line>'
        out.append(l.replace('\\', '/'))
    return '\n'.join(sorted(out))


def xml_cases(dest):
    p = os.path.join(dest, 'test-report.xml')
    if not os.path.exists(p):
        return None
    c = collections.Counter()
    for tc in ET.parse(p).getroot().iter('testcase'):
        fails = len(list(tc.iter('failure')))
        c[(tc.get('classname'), tc.get('name'), fails)] += 1
    return c


def failure_line(lines):
    return [re.sub(r'\S+\.(testForked|testOnly)|Task\.Anon@\S+', '<task>', l) for l in lines if 'tests failed' in l]


def order_ok(text, pat):
    """Per key, the numbers of `pat` run 1..N without gap or reordering."""
    seqs = collections.defaultdict(list)
    for m in re.finditer(pat, text):
        seqs[m.group(1)].append(int(m.group(2)))
    bad = [k for k, v in seqs.items() if v != list(range(1, len(v) + 1))]
    return len(seqs), bad


def captured(dest):
    files = glob.glob(os.path.join(dest, '**', 'captured', '*'), recursive=True)
    text = {os.path.relpath(f, dest).replace('\\', '/'): read(f) for f in files}
    allc = collections.Counter()
    for t in text.values():
        allc += hits(t.splitlines())
    out_text = '\n'.join(t for k, t in text.items() if k.endswith('.out') or 'stdout' in k)
    err_text = '\n'.join(t for k, t in text.items() if k.endswith('.err') or 'stderr' in k)
    o = order_ok(out_text, r'SEQ-OUT pid=(\d+) n=(\d+)')
    e = order_ok(err_text, r'SEQ-ERR pid=(\d+) n=(\d+)')
    xo = order_ok(out_text, r'X-OUT (t\d) n=(\d+)')
    xe = order_ok(err_text, r'X-ERR (t\d) n=(\d+)')
    return allc, dict(seqOut=o, seqErr=e, xOut=xo, xErr=xe), sorted(text)


names = sorted(os.path.basename(p)[:-5] for p in glob.glob(f'{root}/*.exit'))
for name in names:
    mode, outcome, kind, route = name.split('-')
    exitc = read(f'{root}/{name}.exit').strip()
    lines = console(name)
    say(f'== {name}: exit={exitc}')
    say(f'   console markers: {fmt(hits(lines))}')
    fl = failure_line(lines)
    if fl:
        say(f'   console failure lines: {fl}')
    for l in lines:
        if l.startswith('NOTRAIT module=') and 'result=' in l:
            say('   ' + l)
    if route == 'control':
        continue
    dests = sorted(glob.glob(f'{root}/{name}-dest*'))
    for d in dests:
        c, order, files = captured(d)
        say(f'   captured in {os.path.basename(d)}: {fmt(c)}')
        say(f'   order (keys, out-of-order keys): {order}')
    ctrl = f'{mode}-{outcome}-{kind}-control'
    if not os.path.exists(f'{root}/{ctrl}.exit'):
        continue
    cexit = read(f'{root}/{ctrl}.exit').strip()
    cfl = failure_line(console(ctrl))
    say(f'   vs {ctrl}: exit {"same" if cexit == exitc else f"DIFFERS ({cexit})"}; '
        f'failure line {"same" if cfl == fl else f"DIFFERS ({cfl})"}')
    cdests = sorted(glob.glob(f'{root}/{ctrl}-dest*'))
    for i, (d, cd) in enumerate(zip(dests, cdests)):
        xa, xb = xml_cases(d), xml_cases(cd)
        say(f'   JUnit XML dest{i}: {"same test cases" if xa == xb else f"DIFFERS route={dict(xa or {})} control={dict(xb or {})}"}')
        wa, wb = workers(d), workers(cd)
        if not wa and not wb:
            continue
        ga = collections.defaultdict(list)
        gb = collections.defaultdict(list)
        for w in wa:
            ga[w['group']].append(w)
        for w in wb:
            gb[w['group']].append(w)
        say(f'   groups route={sorted(ga)} control={sorted(gb)}')
        for g in sorted(set(ga) | set(gb)):
            ca = set().union(*[w['classes'] for w in ga[g]]) if ga[g] else set()
            cb = set().union(*[w['classes'] for w in gb[g]]) if gb[g] else set()
            dup_a = sum(len(w['classes']) for w in ga[g]) - len(ca)
            dup_b = sum(len(w['classes']) for w in gb[g]) - len(cb)
            na = {norm(w['info']) for w in ga[g]}
            nb = {norm(w['info']) for w in gb[g]}
            say(f'   group {g}: workers {len(ga[g])} vs {len(gb[g])}; classes {"same" if ca == cb else "DIFFER"}'
                f' ({len(ca)}); duplicate runs {dup_a} vs {dup_b}; normalised JVM info '
                f'{"same" if na == nb else "DIFFERS"} ({len(na)} vs {len(nb)} distinct)')
            if na != nb:
                la = set('\n'.join(na).splitlines())
                lb = set('\n'.join(nb).splitlines())
                for l in sorted(la - lb)[:6]:
                    say(f'      route only:   {l[:200]}')
                for l in sorted(lb - la)[:6]:
                    say(f'      control only: {l[:200]}')
