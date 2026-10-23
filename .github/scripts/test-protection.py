#!/usr/bin/env python3
"""Existing tests must not be deleted or weakened (IS212 Week 10).

Week 10 "Guidance vs Enforcement": AI_Context.md tells every agent never to
weaken, skip or delete an existing test (guidance). This check enforces it:
it compares each existing test file on the pull request with the base branch
and fails if a file was deleted, or now has fewer tests or fewer assertions.
That is the "weakened test" from the lecture's PR #57, where an agent deleted
an assertion and CI stayed green.

Unlike the lecture's example, editing an existing test is allowed: tests
legitimately change with the behaviour they check (e.g. a new UI element).
A change that really should remove tests (a feature removed, tests merged)
is approved by a human adding the "tests-reviewed" label to the PR, which
skips this step: a named person signs off, as the delegation dial asks.

Usage: python .github/scripts/test-protection.py <base-ref> [<head-ref>]
"""
import os
import re
import subprocess
import sys

TEST_FILE = re.compile(r'^(backend/src/test/.+Test\.java|frontend/src/.+\.test\.tsx?|frontend/e2e/.+\.spec\.ts)$')
JAVA_TESTS = re.compile(r'@(?:Test|ParameterizedTest|RepeatedTest)\b')
JAVA_ASSERTS = re.compile(r'\b(?:assert\w*|andExpect|verify)\s*\(')
TS_TESTS = re.compile(r'(?<![\w.])(?:it|test)(?:\.each\([^)]*\))?\s*\(')
TS_ASSERTS = re.compile(r'\bexpect(?:\.\w+)?\s*\(')


def git(*args):
    return subprocess.run(['git', *args], capture_output=True, check=True).stdout.decode('utf-8', 'replace')


def counts(path, text):
    if path.endswith('.java'):
        return len(JAVA_TESTS.findall(text)), len(JAVA_ASSERTS.findall(text))
    return len(TS_TESTS.findall(text)), len(TS_ASSERTS.findall(text))


def main():
    base = sys.argv[1]
    head = sys.argv[2] if len(sys.argv) > 2 else 'HEAD'
    merge_base = git('merge-base', base, head).strip()
    changes = git('diff', '--name-status', '--find-renames', merge_base, head).splitlines()

    problems, edited = [], []
    for line in changes:
        status, *paths = line.split('\t')
        old = paths[0]
        if not TEST_FILE.match(old):
            continue
        if status == 'D':
            problems.append(f'{old}: deleted')
            continue
        if not (status == 'M' or status.startswith('R')):
            continue
        new = paths[-1]
        before = counts(old, git('show', f'{merge_base}:{old}'))
        after = counts(new, git('show', f'{head}:{new}'))
        label = old if old == new else f'{old} -> {new}'
        edited.append(f'| `{label}` | {before[0]} → {after[0]} | {before[1]} → {after[1]} |')
        if after[0] < before[0]:
            problems.append(f'{label}: tests {before[0]} -> {after[0]}')
        if after[1] < before[1]:
            problems.append(f'{label}: assertions {before[1]} -> {after[1]}')

    summary = ['### Existing tests', '']
    if edited:
        summary += ['Existing test files changed in this PR (reviewers: check each still tests what its AC says).', '',
                    '| File | Tests | Assertions |', '|---|---|---|', *edited, '']
    else:
        summary.append('No existing test file was changed.')
    if problems:
        summary += ['**Weakened or deleted:**', *[f'- {p}' for p in problems], '',
                    'If this is intended, a reviewer adds the `tests-reviewed` label to the PR.']
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as f:
            f.write('\n'.join(summary) + '\n')
    print('\n'.join(summary))
    for p in problems:
        print(f'::error::Existing test weakened or deleted: {p}')
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
