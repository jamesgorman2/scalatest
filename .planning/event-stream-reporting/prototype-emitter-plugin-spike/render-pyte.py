# PROTOTYPE - throwaway. Renders a terminal log (screen plus scrollback, 160x40) with pyte and
# compares it with every line the command wrote, counting as conpty-run.py does.
# Usage: python render-pyte.py <name> <terminal log> <console.txt or ->
# Appends one block to runs/mintty-summary.txt and writes runs/<name>.rendered.txt.
import re, sys
import pyte

COLS, ROWS = 160, 40
name, log, echo = sys.argv[1:4]
raw = open(log, 'rb').read().decode('utf-8', 'replace')
class Screen(pyte.HistoryScreen):
    # The pseudo-console sends some cursor moves with a second parameter, which pyte rejects.
    def cursor_forward(self, count=None, *_): super().cursor_forward(count)
    def cursor_back(self, count=None, *_): super().cursor_back(count)
    def cursor_up(self, count=None, *_): super().cursor_up(count)
    def cursor_down(self, count=None, *_): super().cursor_down(count)
screen = Screen(COLS, ROWS, history=100000, ratio=0.001)
pyte.Stream(screen).feed(raw)
def row(line): return ''.join(line[x].data for x in range(COLS))
rows = [row(l) for l in screen.history.top] + [row(screen.buffer[y]) for y in range(ROWS)]
lines = [r.rstrip() for r in rows]
while lines and not lines[-1]: lines.pop()
open(f'runs/{name}.rendered.txt', 'w', encoding='utf-8').write('\n'.join(lines) + '\n')

plain = re.sub(r'\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07', '', raw)
prompt_lines = len(re.findall(r'testForked( \d+s)? \d+/\d+ completed\.', plain))
report = [f'{name}:', f'  prompt detected: {prompt_lines > 0} ({prompt_lines} prompt task lines in the log)']
if echo != '-':
    want = set(l.rstrip() for l in open(echo, encoding='utf-8') if l.strip())
    flat = re.sub(r'\s+', '', ''.join(rows))  # spaces aside, in order: tolerates wrapped lines
    shown = set(l for l in want if re.sub(r'\s+', '', l) in flat)
    prompt_re = re.compile(r'^\d+(-\d+)*\] \S+( \d+s)*( \d+/\d+ completed\.)?$|^\d+/\d+\+?(, \d+ failing)?\] ')
    remnants = [l for l in lines if prompt_re.search(l) and not re.match(r'^1\] spike\.test$', l)]
    report.append(f'  command lines written={len(want)} shown intact={len(shown)} stale prompt lines={len(remnants)}'
                  f' worker MARK lines shown={sum("MARK-" in l for l in lines)}')
    for l in sorted(want - shown)[:3]: report.append('    not shown: ' + l[:120])
print('\n'.join(report))
open('runs/mintty-summary.txt', 'a', encoding='utf-8').write('\n'.join(report) + '\n')
