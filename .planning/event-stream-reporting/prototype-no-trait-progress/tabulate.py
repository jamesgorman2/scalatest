# PROTOTYPE - throwaway. Aggregates <runs folder>/summary.tsv (analyse.py) per route, and per route
# and terminal, into <runs folder>/tables.txt.
# Usage: python tabulate.py <runs folder>
import collections, csv, re, sys

root = sys.argv[1]
rows = list(csv.DictReader(open(f'{root}/summary.tsv', encoding='utf-8'), delimiter='\t'))
out = open(f'{root}/tables.txt', 'w', encoding='utf-8')


def say(s=''):
    print(s)
    print(s, file=out)


def route_of(name):
    return name.split('-', 4)[4]


def agg(sel):
    n = len(sel)
    def cnt(f): return sum(1 for r in sel if f(r))
    return dict(
        runs=n,
        worker_line=cnt(lambda r: int(r['worker_line_snaps']) > 0),
        worker_ticker=cnt(lambda r: int(r['worker_ticker_snaps']) > 0),
        completion=cnt(lambda r: int(r['completion_snaps']) > 0),
        stream_classes=cnt(lambda r: int(r['stream_class_names']) > 0),
        stream_completed=cnt(lambda r: int(r['stream_completed']) > 0),
        failed_list=cnt(lambda r: int(r['failed_list']) > 0),
        summary=cnt(lambda r: int(r['scalatest_summary']) > 0),
        worker_output=cnt(lambda r: int(r['worker_output_lines']) > 0),
        capture_ok=cnt(lambda r: r['capture_ok'] == 'ok'),
        r053_same=cnt(lambda r: r['r053'] == 'same'),
        r053_variation=cnt(lambda r: r['r053'].startswith('same except')),
        r053_differs=cnt(lambda r: r['r053'].startswith('DIFFERS')),
        snaps=sum(int(r['snapshots']) for r in sel),
        snaps_worker=sum(int(r['worker_line_snaps']) for r in sel),
        snaps_ticker=sum(int(r['worker_ticker_snaps']) for r in sel),
        snaps_completion=sum(int(r['completion_snaps']) for r in sel),
    )


routes = ['control', 'queue', 'command', 'command-quiet', 'member-quiet', 'command-dummy']
cols = ['runs', 'worker_line', 'worker_ticker', 'completion', 'stream_classes', 'stream_completed', 'failed_list',
        'summary', 'worker_output', 'capture_ok', 'r053_same', 'r053_variation', 'r053_differs']
say('Runs with each finding, per route (all terminals, modes and outcomes)')
say('route\t' + '\t'.join(cols) + '\tsnapshots: total/worker/ticker/completion')
for rt in routes:
    a = agg([r for r in rows if route_of(r['run']) == rt])
    if a['runs']:
        say(f'{rt}\t' + '\t'.join(str(a[c]) for c in cols) +
            f"\t{a['snaps']}/{a['snaps_worker']}/{a['snaps_ticker']}/{a['snaps_completion']}")
say()
say('Per route and terminal: runs; runs with a worker line / ticker / completion line (snapshots)')
for rt in routes:
    for term in ('conpty', 'mintty', 'linux'):
        sel = [r for r in rows if route_of(r['run']) == rt and r['run'].startswith(term + '-')]
        if sel:
            a = agg(sel)
            say(f"{rt}\t{term}\t{a['runs']}\t{a['worker_line']}/{a['worker_ticker']}/{a['completion']}"
                f"\tsnapshots {a['snaps']}/{a['snaps_worker']}/{a['snaps_ticker']}/{a['snaps_completion']}")
say()
say('R-053 differences')
diffs = collections.Counter()
for r in rows:
    if r['r053'] not in ('same', '-'):
        say(f"{r['run']}\t{r['r053']}")
        for d in r['r053'].split(': ', 1)[1].split('; '):
            diffs[(route_of(r['run']), re.sub(r'\d+ vs \d+', 'N vs M', d))] += 1
say()
for (rt, d), c in sorted(diffs.items()):
    say(f'{rt}\t{d}\t{c}')
say()
say('Capture problems')
for r in rows:
    if r['capture_ok'] not in ('ok', '-'):
        say(f"{r['run']}\t{r['capture_ok']}")
