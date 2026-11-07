# PROTOTYPE - throwaway. Ctrl-C into a hidden Git Bash MinTTY window running
# `./mill [--no-daemon] spike.test slow.test`. It is delivered
# as WM_CHAR 0x03 posted to the MinTTY window (see below), not as a hardware keypress.
# Usage: python mintty-ctrl-c.py daemon|nodaemon        Output: runs/mintty-ctrl-c-<mode>.*
import ctypes, os, re, shutil, subprocess, sys, time
from ctypes import wintypes
import psutil

mode = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__)); os.chdir(here)
name = f'mintty-ctrl-c-{mode}'
log = f'runs/{name}.log'
for f in (log, 'out/spike/test.dest/console.txt'):
    if os.path.exists(f): os.remove(f)
flag = '--no-daemon ' if mode == 'nodaemon' else ''
unix_here = subprocess.run(['C:/Program Files/Git/usr/bin/cygpath.exe', '-u', here], capture_output=True, text=True).stdout.strip()
title = f'spike-{name}'
cmd = (f"cd '{unix_here}' && MILL_VERSION=1.1.10-jvm SPIKE_CONSOLE=raw ./mill {flag}spike.test slow.test; "
       "echo MINTTY-EXIT=$?; sleep 3")
mintty = subprocess.Popen(['C:/Program Files/Git/usr/bin/mintty.exe', '-w', 'hide', '--hold', 'never', '-s', '160,40',
                           '-t', title, '--log', log, '-e', '/usr/bin/bash', '-lc', cmd])
user32 = ctypes.WinDLL('user32', use_last_error=True)

def find_window():
    found = []
    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def cb(hwnd, _):
        pid = wintypes.DWORD(); user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        buf = ctypes.create_unicode_buffer(256); user32.GetWindowTextW(hwnd, buf, 256)
        if buf.value.startswith(title): found.append(hwnd)  # mintty re-spawns and pads its title; match the title
        return True
    user32.EnumWindows(cb, 0)
    return found[0] if found else None

def logtext():
    try: return open(log, 'rb').read().decode('utf-8', 'replace')
    except OSError: return ''

def java(kind):
    out = []
    for p in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            if (p.info['name'] or '').lower() == 'java.exe' and any(kind in a for a in p.info['cmdline'] or []):
                out.append(p.info['pid'])
        except psutil.Error: pass
    return out

report = [f'{name}: MinTTY, ./mill {flag}spike.test slow.test, SPIKE_CONSOLE=raw']
t0 = time.time()
def echoed():  # the command's own copy of what it wrote; MinTTY's log can lag
    try: return open('out/spike/test.dest/console.txt', encoding='utf-8').read()
    except OSError: return ''
while time.time() - t0 < 120 and 'hello' not in echoed(): time.sleep(0.2)
time.sleep(3)
hwnd = find_window()
WM_CHAR = 0x0102
sent = False
if hwnd:
    # MinTTY turns a Ctrl+C keypress into the byte 0x03 on its pty; WM_CHAR 0x03 posted to its window
    # takes the same path into MinTTY without needing the window to be in the foreground.
    sent = bool(user32.PostMessageW(hwnd, WM_CHAR, 0x03, 0)); t_sent = time.time()
if not sent:
    report.append(f'  ABORTED: MinTTY window found={bool(hwnd)}; nothing was sent')
    mintty.kill()
else:
    report.append(f'  WM_CHAR 0x03 posted to the MinTTY window {t_sent - t0:.1f}s after start')
    while time.time() - t_sent < 60 and find_window(): time.sleep(0.2)
    if find_window(): report.append('  MinTTY window still open 60 s after Ctrl-C')
    report.append(f'  MinTTY window closed {time.time() - t_sent:.1f}s after Ctrl-C')
    report.append(f'  right after: test workers {java("MillTestRunnerMain")} daemons {java("MillDaemonMain")}')
    time.sleep(20)
    report.append(f'  20 s later:  test workers {java("MillTestRunnerMain")} daemons {java("MillDaemonMain")}')
text = logtext()
m = re.search(r'MINTTY-EXIT=(\d+)', text)
report.append(f'  bash printed: {m.group(0) if m else "no MINTTY-EXIT line"}')
echo = 'out/spike/test.dest/console.txt'
if os.path.exists(echo):
    shutil.copy(echo, f'runs/{name}-console.txt')
    w = [l for l in open(echo, encoding='utf-8').read().splitlines() if l.strip()]
    report.append(f'  command wrote: execute-returned={any("execute-returned" in l for l in w)}'
                  f' body-end={any("body-end" in l for l in w)}; last lines:')
    report += ['    ' + l[:130] for l in w[-3:]]
print('\n'.join(report))
open('runs/mintty-summary.txt', 'a', encoding='utf-8').write('\n'.join(report) + '\n')
