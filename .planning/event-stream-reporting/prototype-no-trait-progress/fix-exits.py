# PROTOTYPE - throwaway. For a MinTTY run whose log ended before its MINTTY-EXIT line (exit `?`), takes
# the exit status from Mill's final header in the recording (`SUCCESS]` 0, `N FAILED]` 1) and marks the
# run's meta with exit_from = final header. mintty-run.py does this itself since it gained `sleep 1`.
# Usage: python fix-exits.py <runs folder>
import glob, json, re, sys

ESC = re.compile(r'\x1b\[[0-9;?]*[A-Za-z]')
for p in glob.glob(f'{sys.argv[1]}/*.exit'):
    if open(p).read().strip() != '?':
        continue
    name = p[:-len('.exit')]
    text = ESC.sub('', ''.join(json.loads(l)[1] for l in open(name + '.chunks.jsonl', encoding='utf-8')))
    finals = re.findall(r'\d+/\d+, (SUCCESS|\d+ FAILED)\]', text)
    if finals:
        code = '0' if finals[-1] == 'SUCCESS' else '1'
        open(p, 'w').write(code + '\n')
        meta = json.load(open(name + '.meta.json'))
        meta['exit_from'] = 'final header'
        json.dump(meta, open(name + '.meta.json', 'w'))
        print(f'{name}: exit {code} from "{finals[-1]}"')
