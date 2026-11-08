# PROTOTYPE - throwaway. For each recorded run, counts the command's row's position among the task rows
# below the prompt header (1 = first), over 250 ms snapshots of the nested evaluation, as analyse.py takes them.
# Usage: python prompt-position.py <name>...
import json, re, sys, collections, glob
import pyte
HEADER = re.compile(r'^(\d+)/(\d+)\+?(, [^\]]*)?\] ')
CMD = re.compile(r'^1\] nt\.(test|tiny)\b(?! *$)')
for name in sys.argv[1:]:
    meta = json.load(open(f'runs/{name}.meta.json'))
    C, R = meta['cols'], meta['rows']
    class S(pyte.HistoryScreen):
        def cursor_forward(self, c=None, *_): super().cursor_forward(c)
        def cursor_back(self, c=None, *_): super().cursor_back(c)
        def cursor_up(self, c=None, *_): super().cursor_up(c)
        def cursor_down(self, c=None, *_): super().cursor_down(c)
    s = S(C, R, history=100000, ratio=0.001); st = pyte.Stream(s)
    ch = [json.loads(l) for l in open(f'runs/{name}.chunks.jsonl', encoding='utf-8')]
    mk = {l.split('\t')[1].strip(): int(l.split('\t')[0]) for l in open(f'runs/{name}-markers.tsv')}
    T, i, pos = mk['execute-start'], 0, collections.Counter()
    while T <= mk['execute-returned']:
        while i < len(ch) and ch[i][0] <= T: st.feed(ch[i][1]); i += 1
        while 0 < i < len(ch) and ch[i][0] - ch[i - 1][0] < 10: st.feed(ch[i][1]); i += 1
        rows = [''.join(s.buffer[y][x].data for x in range(C)).rstrip() for y in range(R)]
        h = [k for k, r in enumerate(rows) if HEADER.match(r)]
        if h:
            body = [r for r in rows[h[-1] + 1:] if r]
            p = next((k + 1 for k, r in enumerate(body) if CMD.match(r)), None)
            pos[p] += 1
        T += 250
    print(name, dict(sorted(pos.items(), key=lambda kv: (kv[0] is None, kv[0] or 0))))
