# PROTOTYPE - throwaway. Before a run, clears the command's records; after it, copies what the run left
# into runs/: the test folder of Mill's own task (out/jvm/test/testForked.dest or testOnly.dest), or the
# route's module folder (out/ntp/modules/<route>/jvm.test), with ntp.run's markers.tsv and results.txt.
# Usage: python collect.py before|after <name> <exit code or -> -- <mill args...>
import os, shutil, sys

RECORDS = ('markers.tsv', 'results.txt')


def dests(args):
    if 'ntp.run' in args:
        route = args[args.index('--route') + 1]
        return [os.path.join('out', 'ntp', 'modules', route, 'jvm.test')]
    for t in ('testForked', 'testOnly'):
        if any(a.endswith('.' + t) for a in args):
            return [os.path.join('out', 'jvm', 'test', t + '.dest')]
    return []


def before(args):
    for f in RECORDS:
        p = os.path.join('out', 'ntp', 'run.dest', f)
        if os.path.exists(p): os.remove(p)


def after(name, code, args):
    os.makedirs('runs', exist_ok=True)
    if code != '-':
        open(f'runs/{name}.exit', 'w').write(code + '\n')
    for i, d in enumerate(dests(args)):
        target = f'runs/{name}-dest{i}'
        shutil.rmtree(target, ignore_errors=True)
        if os.path.exists(d): shutil.copytree(d, target)
    for f in RECORDS:
        p = os.path.join('out', 'ntp', 'run.dest', f)
        if 'ntp.run' in args and os.path.exists(p): shutil.copy(p, f'runs/{name}-{f}')


if __name__ == '__main__':
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    step, name, code = sys.argv[1], sys.argv[2], sys.argv[3]
    args = [a for a in sys.argv[5:] if a]
    before(args) if step == 'before' else after(name, code, args)
