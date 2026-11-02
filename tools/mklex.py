import os
import re
import sys

out = sys.argv[1]
srcs = [p for p in sys.argv[2:] if os.path.exists(p)]
MAXN = 200000

entries = {}
for path in srcs:
    started = False
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n')
        if not started:
            if line.strip() == '...':
                started = True
            continue
        if not line or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) < 2:
            continue
        word = parts[0].strip()
        code = parts[1].strip().replace('ü', 'v')
        w = 1
        if len(parts) >= 3:
            try:
                w = int(float(parts[2].strip().rstrip('%')))
            except ValueError:
                w = 1
        w = max(1, min(w, 2000000000))
        py = code.replace(' ', '').replace("'", '')
        if not re.fullmatch(r'[a-z]{1,24}', py):
            continue
        if not re.fullmatch(r'[\u4e00-\u9fff]{1,8}', word):
            continue
        key = (py, word)
        if key not in entries or entries[key] < w:
            entries[key] = w

rows = [(py, word, w) for (py, word), w in entries.items()]
singles = [r for r in rows if len(r[1]) == 1]
words = [r for r in rows if len(r[1]) > 1]
words.sort(key=lambda r: -r[2])
rows = singles + words[:max(0, MAXN - len(singles))]
rows.sort(key=lambda r: (r[0], -r[2]))

with open(out, 'w', encoding='utf-8') as f:
    for py, word, w in rows:
        f.write(py + '\t' + word + '\t' + str(w) + '\n')
print('entries', len(rows), 'from', len(entries))
