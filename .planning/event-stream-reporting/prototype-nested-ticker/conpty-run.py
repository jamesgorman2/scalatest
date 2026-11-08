# PROTOTYPE - throwaway. Runs one Mill command under a Windows pseudo-console (ConPTY, through
# pywinpty), 160x40, saving every chunk ConPTY emits with its arrival time (epoch ms) as JSON lines,
# then copies the command's own records from out/nt/<cmd>.dest and runs analyse.py.
# Usage: python conpty-run.py <name> <NT_ROUTE> <rows> -- <mill args...>
# Needs: pip install pywinpty pyte psutil
import json, os, queue, shutil, subprocess, sys, threading, time
import winpty

COLS = 160
here = os.path.dirname(os.path.abspath(__file__))
os.chdir(here)
os.makedirs('runs', exist_ok=True)
name, route, rows = sys.argv[1], sys.argv[2], int(sys.argv[3])
ROWS = rows
mill_args = [a for a in sys.argv[5:] if a]  # after '--'
json.dump({'cols': COLS, 'rows': ROWS, 'terminal': 'conpty', 'route': route, 'args': mill_args},
          open(f'runs/{name}.meta.json', 'w'))
cmd_name = next(a for a in mill_args if a.startswith('nt.'))
dest = os.path.join('out', 'nt', cmd_name[3:] + '.dest')
for f in ('ticker.tsv', 'console.tsv', 'console.txt', 'markers.tsv', 'keys.tsv'):
    if os.path.exists(os.path.join(dest, f)): os.remove(os.path.join(dest, f))

env = dict(os.environ, NT_ROUTE=route)
proc = winpty.PtyProcess.spawn('cmd /c .\\mill.bat ' + ' '.join(mill_args), cwd=here, env=env,
                               dimensions=(ROWS, COLS))
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
print(f'{name}: mill {" ".join(mill_args)} NT_ROUTE={route} exit={proc.exitstatus} seconds={time.time() - t0:.1f}')
for f in ('ticker.tsv', 'console.tsv', 'console.txt', 'markers.tsv', 'keys.tsv'):
    if os.path.exists(os.path.join(dest, f)): shutil.copy(os.path.join(dest, f), f'runs/{name}-{f}')
subprocess.run([sys.executable, 'analyse.py', name])
