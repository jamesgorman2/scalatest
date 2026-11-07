# PROTOTYPE - throwaway. Runs one Mill command under a Windows pseudo-console (ConPTY, through
# pywinpty), 160x40, saves the bytes ConPTY emits, renders them with pyte (screen plus scrollback)
# and compares the result with every line the command wrote (out/spike/test.dest/console.txt).
# Usage: python conpty-run.py <name> <raw|log> <lines|cr> [--ctrl-c <seconds>] -- <mill args...>
# Needs: pip install pywinpty pyte psutil
import os, re, sys, time, shutil, queue, threading
import psutil, pyte, winpty

COLS, ROWS = 160, 40
here = os.path.dirname(os.path.abspath(__file__))
os.chdir(here)
os.makedirs('runs', exist_ok=True)
name, console, progress = sys.argv[1:4]
rest = sys.argv[4:]
ctrl_c = None
if rest[0] == '--ctrl-c':
    ctrl_c = float(rest[1]); rest = rest[2:]
mill_args = [a for a in rest[1:] if a]  # after '--'

def mill_java(kind):
    out = []
    for p in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            if (p.info['name'] or '').lower() == 'java.exe' and any(kind in a for a in p.info['cmdline'] or []):
                out.append(p.info['pid'])
        except psutil.Error:
            pass
    return out

echo = os.path.join('out', 'spike', 'test.dest', 'console.txt')
if os.path.exists(echo): os.remove(echo)
env = dict(os.environ, SPIKE_CONSOLE=console, SPIKE_PROGRESS=progress)
cmd = 'cmd /c .\\mill.bat ' + ' '.join(mill_args)
proc = winpty.PtyProcess.spawn(cmd, cwd=here, env=env, dimensions=(ROWS, COLS))
chunks, t0, sent, answered = [], time.time(), False, False
q = queue.Queue()
def pump():  # pywinpty's read blocks, so read on a thread
    while True:
        try: q.put(proc.read(65536))
        except EOFError: q.put(None); return
threading.Thread(target=pump, daemon=True).start()
while True:
    if ctrl_c is not None and not sent and time.time() - t0 >= ctrl_c:
        proc.write('\x03'); sent = True; t_sent = time.time()
    try: data = q.get(timeout=0.05)
    except queue.Empty: data = ''
    if data is None: break
    if data: chunks.append(data)
    if sent and not answered and 'Terminate batch job' in ''.join(chunks)[-400:]:
        proc.write('N\r'); answered = True   # cmd.exe asks after Ctrl-C ends mill.bat's child
    if not data and not proc.isalive() and q.empty(): break
    if sent and time.time() - t_sent > 60: proc.terminate(force=True); break
raw = ''.join(chunks)
with open(f'runs/conpty-{name}.typescript', 'w', encoding='utf-8', newline='') as f: f.write(raw)
report = [f'{name}: mill {" ".join(mill_args)} SPIKE_CONSOLE={console} SPIKE_PROGRESS={progress}'
          f' exit={proc.exitstatus} seconds={time.time() - t0:.1f}']
if ctrl_c is not None:
    report.append(f'  Ctrl-C written at {ctrl_c}s; exited {time.time() - t_sent:.1f}s later; cmd asked "Terminate batch job": {answered}')
    report.append(f'  right after exit: test workers {mill_java("MillTestRunnerMain")} daemons {mill_java("MillDaemonMain")}')
    time.sleep(20)
    report.append(f'  20 s later:      test workers {mill_java("MillTestRunnerMain")} daemons {mill_java("MillDaemonMain")}')

# Render: screen plus scrollback.
screen = pyte.HistoryScreen(COLS, ROWS, history=100000, ratio=0.001)
pyte.Stream(screen).feed(raw)
def row(line): return ''.join(line[x].data for x in range(COLS))
rows = [row(l) for l in screen.history.top] + [row(screen.buffer[y]) for y in range(ROWS)]
lines, k = [], 0
while k < len(rows):  # join rows the terminal wrapped (a full row followed by more text)
    s = rows[k]
    while len(s.rstrip()) and len(rows[k]) == COLS and rows[k][-1] != ' ' and k + 1 < len(rows):
        k += 1; s += rows[k]
    lines.append(s.rstrip()); k += 1
while lines and not lines[-1]: lines.pop()
with open(f'runs/conpty-{name}.rendered.txt', 'w', encoding='utf-8') as f: f.write('\n'.join(lines) + '\n')

# Did Mill treat the pseudo-console as interactive? Its prompt shows running classes and counts.
plain = re.sub(r'\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07', '', raw)
prompt_lines = len(re.findall(r'testForked( \d+s)? \d+/\d+ completed\.', plain))
report.append(f'  prompt detected: {prompt_lines > 0} ({prompt_lines} prompt task lines in the stream)')
if os.path.exists(echo):
    shutil.copy(echo, f'runs/conpty-{name}-console.txt')
    want = set(l.rstrip() for l in open(echo, encoding='utf-8') if l.strip())
    # A written line counts as shown when its characters, spaces aside, appear in order in the
    # rendering with rows run together: this tolerates the terminal wrapping long lines.
    flat = re.sub(r'\s+', '', ''.join(rows))
    shown = set(l for l in want if re.sub(r'\s+', '', l) in flat)
    prompt_re = re.compile(r'^\d+(-\d+)*\] \S+( \d+s)*( \d+/\d+ completed\.)?$|^\d+/\d+\+?(, \d+ failing)?\] ')
    remnants = [l for l in lines if prompt_re.search(l) and not re.match(r'^1\] spike\.test$', l)]
    report.append(f'  command lines written={len(want)} shown intact={len(want & shown)}'
                  f' stale prompt lines={len(remnants)} worker MARK lines shown={sum("MARK-" in l for l in lines)}')
    for l in remnants[:3]: report.append('    stale: ' + l[:120])
else:
    report.append('  no console.txt: the command body did not write')
print('\n'.join(report))
with open('runs/conpty-summary.txt', 'a', encoding='utf-8') as f: f.write('\n'.join(report) + '\n')
if ctrl_c is not None and os.path.exists(echo):
    written = open(echo, encoding='utf-8').read()
    last = [l for l in written.splitlines() if l.strip()][-1]
    extra = f'  command wrote after Ctrl-C: execute-returned={"execute-returned" in written} body-end={"body-end" in written}; its last line: {last[:100]}'
    print(extra)
    with open('runs/conpty-summary.txt', 'a', encoding='utf-8') as f: f.write(extra + '\n')
