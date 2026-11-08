# PROTOTYPE - throwaway. Reads a pseudo-terminal's output from stdin (piped from `script`) and writes
# each chunk with its arrival time (epoch ms) as JSON lines, the format conpty-run.py writes.
# Usage: script -q -e -c "<command>" /dev/null </dev/null | python3 tsrec.py <out.chunks.jsonl>
import codecs, json, os, sys, time

dec = codecs.getincrementaldecoder('utf-8')(errors='replace')
with open(sys.argv[1], 'w', encoding='utf-8') as out:
    while True:
        b = os.read(0, 65536)
        if not b: break
        out.write(json.dumps([time.time() * 1000, dec.decode(b)]) + '\n')
