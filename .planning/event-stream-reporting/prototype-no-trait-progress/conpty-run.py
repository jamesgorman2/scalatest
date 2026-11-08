# PROTOTYPE - throwaway. Runs one Mill command under a Windows pseudo-console (ConPTY, through
# pywinpty), 160 by <rows>, saving every chunk ConPTY emits with its arrival time (epoch ms) as JSON
# lines in runs/<name>.chunks.jsonl, then collects the run's folders (collect.py).
# Usage: python conpty-run.py <name> <rows> <fail: 0|1> -- <mill args...>
# Needs: pip install pywinpty pyte
import json, os, queue, sys, threading, time
import winpty
import collect

COLS = 160
here = os.path.dirname(os.path.abspath(__file__))
os.chdir(here)
os.makedirs('runs', exist_ok=True)
name, rows, fail = sys.argv[1], int(sys.argv[2]), sys.argv[3] == '1'
mill_args = [a for a in sys.argv[5:] if a]
json.dump({'cols': COLS, 'rows': rows, 'terminal': 'conpty', 'fail': fail, 'args': mill_args},
          open(f'runs/{name}.meta.json', 'w'))
collect.before(mill_args)
env = dict(os.environ)
env.pop('SPIKE_FAIL', None)
if fail: env['SPIKE_FAIL'] = '1'
proc = winpty.PtyProcess.spawn(r'cmd /c .\mill.bat ' +' '.join(mill_args), cwd=here, env=env,
                               dimensions=(rows, COLS))
q = queue.Queue()
def pump():  # pywinpty's read blocks, so read on a thread and stamp each chunk on arrival
    while True:
        try: d = proc.read(65536)
        except EOFError: q.put(None); return
        q.put((time.time() * 1000, d))
threading.Thread(target=pump, daemon=True).start()
t0 = time.time()
with open(f'runs/{name}.chunks.jsonl', 'w', encoding='utf-8') as out:
    while True:
        try: item = q.get(timeout=0.05)
        except queue.Empty: item = ''
        if item is None: break
        if item: out.write(json.dumps(item) + '\n')
        if not item and not proc.isalive() and q.empty(): break
print(f'{name}: mill {" ".join(mill_args)} fail={fail} exit={proc.exitstatus} seconds={time.time() - t0:.1f}')
collect.after(name, str(proc.exitstatus), mill_args)
