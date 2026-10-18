#!/usr/bin/env python3
"""Coverage summary for the CI run page (IS212 rubric: "100% coverage unless
not possible and stated why"). Coverage is a diagnostic, not a target (Week 6):
it shows what no test executes; it cannot show that a test checks the right
thing. Stated exclusions live in docs/testing-strategy.md.

Usage:
  python .github/scripts/coverage-summary.py backend  backend/target/site/jacoco/jacoco.csv
  python .github/scripts/coverage-summary.py frontend frontend/coverage/coverage-summary.json

Prints line/branch totals, the classes or files behind the focus stories, and
the ten least-covered ones; appends the same to GITHUB_STEP_SUMMARY in CI.
Never fails the build: the gate is the tests, not a percentage.
"""
import csv
import json
import os
import sys

# Classes/files behind the stories this sprint focuses on (EC-NEW2, EC08, EO04).
FOCUS_BACKEND = [
    'JourneyStatus', 'JourneyStatusResolver', 'StatusView', 'EventLifecycleService',
    'EventArrangementsService', 'EventNotReadyException', 'EventLifecycleException',
    'EventLifecycleValidationException',
]
FOCUS_FRONTEND = ['JourneyStatus.tsx', 'EventReadinessPanel.tsx', 'OrganiserArrangements.tsx', 'types/status.ts']


def pct(covered, total):
    return 100.0 if total == 0 else 100.0 * covered / total


def backend(path):
    rows = list(csv.DictReader(open(path, encoding='utf-8')))
    per_class = {}
    for r in rows:
        name = r['CLASS'].split('.')[-1]
        lines = (int(r['LINE_COVERED']), int(r['LINE_COVERED']) + int(r['LINE_MISSED']))
        branches = (int(r['BRANCH_COVERED']), int(r['BRANCH_COVERED']) + int(r['BRANCH_MISSED']))
        prev = per_class.get(name, ((0, 0), (0, 0)))
        per_class[name] = ((prev[0][0] + lines[0], prev[0][1] + lines[1]),
                           (prev[1][0] + branches[0], prev[1][1] + branches[1]))
    total_l = [sum(v[0][i] for v in per_class.values()) for i in (0, 1)]
    total_b = [sum(v[1][i] for v in per_class.values()) for i in (0, 1)]
    focus = {n: v for n, v in per_class.items() if n.split('$')[0] in FOCUS_BACKEND}
    return ('Backend (JaCoCo)', pct(*total_l), pct(*total_b),
            [(n, pct(*v[0]), pct(*v[1])) for n, v in sorted(focus.items())],
            sorted(((n, pct(*v[0]), pct(*v[1])) for n, v in per_class.items() if v[0][1] >= 10),
                   key=lambda t: t[1])[:10])


def frontend(path):
    data = json.load(open(path, encoding='utf-8'))
    total = data.pop('total')
    files = [(f.replace('\\', '/').split('/src/')[-1], v['lines']['pct'], v['branches']['pct'])
             for f, v in data.items()]
    focus = [f for f in files if any(f[0].endswith(x) for x in FOCUS_FRONTEND)]
    return ('Frontend (Vitest/V8)', total['lines']['pct'], total['branches']['pct'], sorted(focus),
            sorted((f for f in files if f[1] < 100), key=lambda t: t[1])[:10])


def main():
    kind, path = sys.argv[1], sys.argv[2]
    if not os.path.exists(path):
        print(f'No coverage report at {path}; skipping.')
        return
    title, lines, branches, focus, lowest = (backend if kind == 'backend' else frontend)(path)
    out = [f'### {title} coverage', '',
           f'**Lines {lines:.1f}% · Branches {branches:.1f}%**', '',
           '| Focus stories (EC-NEW2, EC08, EO04) | Lines | Branches |', '|---|---:|---:|']
    out += [f'| `{n}` | {l:.1f}% | {b:.1f}% |' for n, l, b in focus] or ['| (none found) | | |']
    out += ['', '| Least covered | Lines | Branches |', '|---|---:|---:|']
    out += [f'| `{n}` | {l:.1f}% | {b:.1f}% |' for n, l, b in lowest]
    text = '\n'.join(out) + '\n'
    print(text)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as f:
            f.write(text + '\n')


if __name__ == '__main__':
    main()
