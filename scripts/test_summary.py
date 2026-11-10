"""Summarize JUnit outcomes and slow tests in the Actions job summary."""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


def main(directory):
    files = sorted(Path(directory).glob("TEST-*.xml"))
    if not files:
        print("No JUnit test results found.")
        return 1
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    cases = []
    for file in files:
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
        for case in suite.iter("testcase"):
            cases.append((float(case.get("time", "0")), case.get("classname", "") + "." + case.get("name", "")))
    print("## Unit tests\n")
    print(", ".join(f"{key}: {value}" for key, value in totals.items()))
    print(f"\nSum of test durations: {sum(seconds for seconds, _ in cases):.2f} s\n")
    print("| Slowest tests | Seconds |\n| --- | ---: |")
    for seconds, name in sorted(cases, reverse=True)[:10]:
        print(f"| `{name.replace('|', '&#124;')}` | {seconds:.2f} |")
    return 0 if totals["tests"] > 0 else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1]))
