# PROTOTYPE - throwaway. Runs one Mill command in a hidden Git Bash MinTTY window (160 by <rows>) with
# MinTTY's --log, polling the log's size every 20 ms so that each new run of bytes gets the time it
# appeared, and writes runs/<name>.chunks.jsonl in the format conpty-run.py writes; then copies the
# command's own records and runs analyse.py. `./mill` needs MILL_VERSION=1.1.10-jvm on Windows.
# Usage: python mintty-run.py <name> <NT_ROUTE> <rows> <MSYS value, or `default`> -- <mill args...>
import codecs, json, os, shutil, subprocess, sys, time

MINTTY = r'C:\Program Files\Git\usr\bin\mintty.exe'
COLS = 160
here = os.path.dirname(os.path.abspath(__file__))
os.chdir(here)
os.makedirs('runs', exist_ok=True)
name, route, rows, msys = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
if msys == 'default': msys = ''
mill_args = [a for a in sys.argv[6:] if a]  # after '--'
json.dump({'cols': COLS, 'rows': rows, 'terminal': 'mintty' + (f' MSYS={msys}' if msys else ''),
           'route': route, 'args': mill_args}, open(f'runs/{name}.meta.json', 'w'))
cmd_name = next(a for a in mill_args if a.startswith('nt.'))
dest = os.path.join('out', 'nt', cmd_name[3:] + '.dest')
for f in ('ticker.tsv', 'console.tsv', 'console.txt', 'markers.tsv', 'keys.tsv'):
    if os.path.exists(os.path.join(dest, f)): os.remove(os.path.join(dest, f))

posix_here = '/' + here[0].lower() + here[2:].replace('\\', '/')
log = os.path.join('runs', f'{name}.log')
if os.path.exists(log): os.remove(log)
# A non-login bash: in this environment a login bash in MinTTY exited before running its command.
script = (f"export PATH=/usr/bin:$PATH; cd '{posix_here}' && MSYS={msys} MILL_VERSION=1.1.10-jvm NT_ROUTE={route}"
          f" ./mill {' '.join(mill_args)}; echo MINTTY-EXIT=$?")
t0 = time.time()
proc = subprocess.Popen([MINTTY, '-w', 'hide', '--hold', 'never', '-s', f'{COLS},{rows}', '--log', log,
                         '-e', '/usr/bin/bash', '-c', script])
# mintty.exe started from a Windows process returns at once and runs on detached, so wait for the
# exit line in its log instead of for the process.
sizes, last = [], 0
while True:
    size = os.path.getsize(log) if os.path.exists(log) else 0
    if size != last:
        sizes.append((time.time() * 1000, size)); last = size
        with open(log, 'rb') as f:
            f.seek(max(0, size - 300))
            if b'MINTTY-EXIT=' in f.read(): break
    if time.time() - t0 > 300: break
    time.sleep(0.02)
time.sleep(0.5)
size = os.path.getsize(log)
if size != last: sizes.append((time.time() * 1000, size))
data = open(log, 'rb').read()
dec = codecs.getincrementaldecoder('utf-8')(errors='replace')
with open(f'runs/{name}.chunks.jsonl', 'w', encoding='utf-8') as out:
    prev = 0
    for t, s in sizes:
        out.write(json.dumps([t, dec.decode(data[prev:s])]) + '\n'); prev = s
exit_line = next((l for l in data.decode('utf-8', 'replace').splitlines() if 'MINTTY-EXIT=' in l), '?')
print(f'{name}: ./mill {" ".join(mill_args)} NT_ROUTE={route} MSYS={msys} {exit_line.strip()}'
      f' seconds={time.time() - t0:.1f} size samples={len(sizes)}')
for f in ('ticker.tsv', 'console.tsv', 'console.txt', 'markers.tsv', 'keys.tsv'):
    if os.path.exists(os.path.join(dest, f)): shutil.copy(os.path.join(dest, f), f'runs/{name}-{f}')
subprocess.run([sys.executable, 'analyse.py', name])
