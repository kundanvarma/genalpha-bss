#!/usr/bin/env python3
"""Fail unless every module's PostgresMigrationTest actually RAN against Postgres.

Testcontainers turns "no usable Docker" into "skipped", and Maven reports a skip
as success, so a CI run where Postgres was never reached looks identical to one
where every migration was proven. This reads the surefire reports after
`mvn verify` and fails on any module whose Postgres test was skipped, missing,
or ran nothing.

Usage: python3 ops/ci-postgres-ran.py   (from the repo root, after mvn verify)
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

modules = sorted({p.split(os.sep)[1] for p in
                  glob.glob("services/*/src/test/java/**/PostgresMigrationTest.java", recursive=True)})
if not modules:
    sys.exit("postgres-ran: no PostgresMigrationTest found under services/ — run from the repo root")

problems = []
for m in modules:
    reports = glob.glob(f"services/{m}/target/surefire-reports/TEST-*.PostgresMigrationTest.xml")
    if not reports:
        problems.append(f"{m}: no report (did its tests run?)")
        continue
    suite = ET.parse(reports[0]).getroot()
    tests, skipped = int(suite.get("tests", 0)), int(suite.get("skipped", 0))
    if tests == 0 or skipped:
        problems.append(f"{m}: {skipped} of {tests} skipped — Postgres was never reached")

if problems:
    print("postgres-ran: FAIL — a skipped Postgres test reads like a pass")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print(f"postgres-ran: ok — {len(modules)} modules proved their migrations on real Postgres")
