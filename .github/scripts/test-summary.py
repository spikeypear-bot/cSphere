#!/usr/bin/env python3
"""Writes a Markdown summary of Maven Surefire results to the GitHub job summary.

Usage: test-summary.py "<title>" <surefire-reports-dir>
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main():
    title, reports_dir = sys.argv[1], sys.argv[2]
    lines = [f"## {title}", ""]
    suites = [ET.parse(path).getroot() for path in sorted(glob.glob(os.path.join(reports_dir, "TEST-*.xml")))]

    if not suites:
        lines.append("No test results were produced (the step did not run or failed before testing).")
    else:
        rows, failed_tests = [], []
        totals = {"tests": 0, "failed": 0, "skipped": 0, "time": 0.0}
        for suite in suites:
            tests = int(suite.get("tests", 0))
            failed = int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            skipped = int(suite.get("skipped", 0))
            seconds = float(suite.get("time", 0))
            totals["tests"] += tests
            totals["failed"] += failed
            totals["skipped"] += skipped
            totals["time"] += seconds
            name = suite.get("name", "").rsplit(".", 1)[-1]
            rows.append((name, tests, failed, skipped, seconds))
            for case in suite.iter("testcase"):
                problem = case.find("failure")
                if problem is None:
                    problem = case.find("error")
                if problem is not None:
                    message = (problem.get("message") or "").strip().splitlines()
                    failed_tests.append((name, case.get("name", ""), message[0][:200] if message else ""))

        passed = totals["tests"] - totals["failed"] - totals["skipped"]
        status = "❌ Failed" if totals["failed"] else "✅ Passed"
        lines.append(f"**{status}** — {passed} passed, {totals['failed']} failed, "
                     f"{totals['skipped']} skipped, {totals['tests']} total in {totals['time']:.1f}s")
        lines += ["", "| Test class | Tests | Failed | Skipped | Time |", "|---|---:|---:|---:|---:|"]
        for name, tests, failed, skipped, seconds in sorted(rows, key=lambda row: (-row[2], row[0])):
            mark = "❌ " if failed else ""
            lines.append(f"| {mark}{name} | {tests} | {failed} | {skipped} | {seconds:.1f}s |")
        if failed_tests:
            lines += ["", "### Failed tests", ""]
            for class_name, test_name, message in failed_tests:
                detail = f": {message}" if message else ""
                lines.append(f"- `{class_name}.{test_name}`{detail}")

    output = "\n".join(lines) + "\n\n"
    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with open(summary_file, "a", encoding="utf-8") as handle:
            handle.write(output)
    else:
        sys.stdout.write(output)


if __name__ == "__main__":
    main()
