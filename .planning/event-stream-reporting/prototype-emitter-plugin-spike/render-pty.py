# PROTOTYPE - throwaway. Replays a `script` typescript through a minimal terminal (160x40; only the
# CR, LF, ESC[nA, ESC[nD, ESC[nK, ESC[nJ and ESC[...m sequences Mill emits) and reports what the
# terminal shows at the end, scrollback included, against what the command wrote.
# Usage: python3 render-pty.py runs/pty-<name>.typescript [--dump]
import re, sys

COLS, ROWS = 160, 40
data = open(sys.argv[1], 'rb').read().decode('utf-8', 'replace')
data = data.split('\n', 1)[1]                      # drop "Script started" header
data = re.sub(r'\n?Script done.*$', '', data, flags=re.S)
buf, r, c = [[]], 0, 0
wrapped = set()  # rows the terminal wrapped onto the next row

def ensure(row):
    while len(buf) <= row: buf.append([])

def put(ch):
    global r, c
    if c >= COLS: wrapped.add(r); r += 1; c = 0
    ensure(r); line = buf[r]
    while len(line) < c: line.append(' ')
    if c < len(line): line[c] = ch
    else: line.append(ch)
    c += 1

i = 0
for m in re.finditer(r'\x1b\[([0-9;?]*)([A-Za-z])|\r|\n|[^\x1b\r\n]', data):
    t = m.group(0)
    if t == '\r': c = 0
    elif t == '\n': wrapped.discard(r); r += 1; ensure(r)
    elif t.startswith('\x1b'):
        n = int(m.group(1) or '0') if m.group(1).isdigit() else 0
        cmd = m.group(2)
        top = max(0, len(buf) - ROWS)
        if cmd == 'A': r = max(top, r - max(n, 1))
        elif cmd == 'D': c = max(0, c - max(n, 1))
        elif cmd == 'K': ensure(r); del buf[r][c:]; wrapped.discard(r)
        elif cmd == 'J':
            ensure(r); del buf[r][c:]
            for k in range(r, len(buf)): wrapped.discard(k)
            for k in range(r + 1, len(buf)): buf[k] = []
    else: put(t)

lines, k = [], 0
while k < len(buf):
    s = ''.join(buf[k])
    while k in wrapped and k + 1 < len(buf): k += 1; s += ''.join(buf[k])
    lines.append(s.rstrip()); k += 1
while lines and not lines[-1]: lines.pop()
written = [l for l in re.split(r'[\r\n]', re.sub(r'\x1b\[[0-9;?]*[A-Za-z]', '', data))
           if re.search(r'(^|\] |\[0J)?(SPIKE|PROGRESS) ', l)]
cmd_re = re.compile(r'(SPIKE|PROGRESS) .*')
want = set(cmd_re.search(l).group(0).rstrip() for l in written)
shown = [l for l in lines if cmd_re.search(l)]
intact = set(cmd_re.search(l).group(0).rstrip() for l in shown) & want
prompt_re = re.compile(r'^\d+(-\d+)*\] (\S+\.test\.testForked( \d+/\d+ completed\.)?|alpha\.A\d|beta\.Beta\w+|slow\.\w+|spike\.test)( \d+s)?$|^\d+/\d+\+?\] ')
remnants = [l for l in lines if prompt_re.search(l)]
marks = [l for l in lines if 'MARK-' in l]
print(f'{sys.argv[1]}: rendered lines={len(lines)} distinct command lines written={len(want)} '
      f'shown intact={len(intact)} missing={len(want - intact)} prompt-remnant lines={len(remnants)} '
      f'worker MARK lines shown={len(marks)}')
for l in sorted(want - intact)[:5]: print('  missing:', l[:140])
for l in remnants[:5]: print('  remnant:', l[:140])
if '--dump' in sys.argv: print('\n'.join(lines))
