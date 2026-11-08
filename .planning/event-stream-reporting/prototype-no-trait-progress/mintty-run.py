# PROTOTYPE - throwaway. Runs one Mill command in a hidden Git Bash MinTTY window (160 by <rows>) with
# MinTTY's --log, polling the log's size every 20 ms so that each new run of bytes gets the time it
# appeared, and writes runs/<name>.chunks.jsonl in the format conpty-run.py writes; then collects the
# run's folders (collect.py). `./mill` needs MILL_VERSION=1.1.10-jvm on Windows.
# Usage: python mintty-run.py <name> <rows> <fail: 0|1> -- <mill args...>
import codecs, json, os, re, shlex, subprocess, sys, time
import collect

MINTTY = r'C:\Program Files\Git\usr\bin\mintty.exe'
COLS = 160
here = os.path.dirname(os.path.abspath(__file__))
os.chdir(here)
os.makedirs('runs', exist_ok=True)
name, rows, fail = sys.argv[1], int(sys.argv[2]), sys.argv[3] == '1'
mill_args = [a for a in sys.argv[5:] if a]
json.dump({'cols': COLS, 'rows': rows, 'terminal': 'mintty', 'fail': fail, 'args': mill_args},
          open(f'runs/{name}.meta.json', 'w'))
collect.before(mill_args)
posix_here = '/' + here[0].lower() + here[2:].replace('\\', '/')
log = os.path.join('runs', f'{name}.log')
if os.path.exists(log): os.remove(log)
fail_env = 'SPIKE_FAIL=1' if fail else ''
quoted = ' '.join(shlex.quote(a) for a in mill_args)
# A non-login bash: in this environment a login bash in MinTTY exited before running its command.
script = (f"export PATH=/usr/bin:$PATH; unset SPIKE_FAIL; cd '{posix_here}' && {fail_env} MILL_VERSION=1.1.10-jvm"
          f" ./mill {quoted}; echo MINTTY-EXIT=$?; sleep 1")
t0 = time.time()
subprocess.Popen([MINTTY, '-w', 'hide', '--hold', 'never', '-s', f'{COLS},{rows}', '--log', log,
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
    if time.time() - t0 > 120: break
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
text = data.decode('utf-8', 'replace')
code = text[text.rfind('MINTTY-EXIT=') + 12:].split()[0].strip() if 'MINTTY-EXIT=' in text else '?'
if code == '?':
    # The log sometimes ended before the exit line; take the status from Mill's final header instead.
    finals = re.findall(r'\d+/\d+, (SUCCESS|\d+ FAILED)\]', re.sub(r'\[[0-9;?]*[A-Za-z]', '', text))
    if finals:
        code = '0' if finals[-1] == 'SUCCESS' else '1'
        json.dump({**json.load(open(f'runs/{name}.meta.json')), 'exit_from': 'final header'},
                  open(f'runs/{name}.meta.json', 'w'))
os.remove(log)
print(f'{name}: ./mill {" ".join(mill_args)} fail={fail} exit={code} seconds={time.time() - t0:.1f} samples={len(sizes)}')
collect.after(name, code, mill_args)
