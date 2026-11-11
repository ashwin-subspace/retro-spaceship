"""Run the desktop test harness for the touch gesture recogniser.

Two things the emulator cannot usefully judge live here: the gesture recogniser,
because `adb shell input` has no multitouch, and the flight model, because "does
the ship still go where you put it" is a number rather than an impression.
Neither class imports anything Android, so both can be compiled and driven on a
plain JVM -- far faster and far more decisive than puppeting an emulator.

Usage:  py tools/run_tests.py
"""

import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_apk import JAVA, JAVAC, ROOT  # noqa: E402  (reuses the JDK lookup)

SRC = os.path.join(ROOT, "android", "java", "com", "ashwin", "retrospaceship")
TESTS = os.path.join(ROOT, "tools", "test")

# (class under test, harness) -- both sides must stay free of Android imports.
SUITES = [
    ("Gestures.java", "GestureTest"),
    ("Flight.java", "FlightTest"),
]


def main():
    work = tempfile.mkdtemp(prefix="retro-tests-")
    try:
        classes = os.path.join(work, "classes")
        os.makedirs(classes)
        sources = []
        for source, harness in SUITES:
            sources.append(os.path.join(SRC, source))
            sources.append(os.path.join(TESTS, harness + ".java"))
        compile_result = subprocess.run(
            [JAVAC, "-nowarn", "-encoding", "UTF-8", "-d", classes] + sources,
            capture_output=True, text=True)
        if compile_result.returncode != 0:
            sys.stderr.write(compile_result.stderr)
            return 1

        failed = 0
        for _source, harness in SUITES:
            run = subprocess.run(
                [JAVA, "-cp", classes, "com.ashwin.retrospaceship." + harness],
                capture_output=True, text=True)
            sys.stdout.write(run.stdout)
            sys.stderr.write(run.stderr)
            failed |= run.returncode
        return failed
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
