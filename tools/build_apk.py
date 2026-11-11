"""Build a signed, installable APK without Gradle.

The app deliberately has no library dependencies, which means the whole build is
four tools in a row -- aapt2, javac, d8, apksigner -- and can run straight off
the installed SDK with no download step and no wrapper. Everything it writes
goes under the project's own build/ and dist/ directories.

Usage:  py tools/build_apk.py
"""

import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = os.path.join(ROOT, "android")
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")

SDK = (
    os.environ.get("ANDROID_HOME")
    or os.environ.get("ANDROID_SDK_ROOT")
    or os.path.join(os.path.expanduser("~"), "AppData", "Local", "Android", "Sdk")
)
BUILD_TOOLS_VERSION = "35.0.0"
PLATFORM = "android-36"

BUILD_TOOLS = os.path.join(SDK, "build-tools", BUILD_TOOLS_VERSION)
ANDROID_JAR = os.path.join(SDK, "platforms", PLATFORM, "android.jar")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe" if os.name == "nt" else "aapt2")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe" if os.name == "nt" else "zipalign")
D8_JAR = os.path.join(BUILD_TOOLS, "lib", "d8.jar")
APKSIGNER_JAR = os.path.join(BUILD_TOOLS, "lib", "apksigner.jar")

EXE = ".exe" if os.name == "nt" else ""


def find_jdk():
    """Locate a JDK that actually has javac and keytool, not just java.

    JAVA_HOME is checked but not trusted: a stale or uninstalled entry there is
    common, and the Oracle javapath shim on PATH ships java and javac without
    keytool, so signing would fail late instead of here.
    """
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(os.environ["JAVA_HOME"])
    candidates += [
        r"F:\android studio\jbr",
        r"C:\Program Files\Android\Android Studio\jbr",
    ]
    for base in candidates:
        binaries = [os.path.join(base, "bin", name + EXE)
                    for name in ("java", "javac", "keytool")]
        if all(os.path.exists(path) for path in binaries):
            return base
    return None


JAVA_HOME = find_jdk()
if JAVA_HOME is None:
    print("No complete JDK found (need java, javac and keytool).", file=sys.stderr)
    print("Set JAVA_HOME to a full JDK and re-run.", file=sys.stderr)
    sys.exit(1)

JAVA = os.path.join(JAVA_HOME, "bin", "java" + EXE)
JAVAC = os.path.join(JAVA_HOME, "bin", "javac" + EXE)
KEYTOOL = os.path.join(JAVA_HOME, "bin", "keytool" + EXE)

KEYSTORE = os.path.join(DIST, "signing.keystore")
KEY_ALIAS = "retrospaceship"
KEY_PASS = "retrospaceship"

APK_NAME = "RetroSpaceship.apk"


def run(command, label):
    print("  ..", label)
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        print("FAILED:", label, file=sys.stderr)
        print(" ".join(str(c) for c in command), file=sys.stderr)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        sys.exit(1)
    return result


def check_toolchain():
    missing = [path for path in (AAPT2, ZIPALIGN, D8_JAR, APKSIGNER_JAR,
                                 ANDROID_JAR, JAVAC, JAVA) if not os.path.exists(path)]
    if missing:
        print("Missing from the toolchain:", file=sys.stderr)
        for path in missing:
            print("  ", path, file=sys.stderr)
        sys.exit(1)


def ensure_keystore():
    """Create a signing key on first build.

    The key lives in the project, not in the user profile: the machine's system
    drive is off limits for anything this build generates.
    """
    if os.path.exists(KEYSTORE):
        return
    os.makedirs(DIST, exist_ok=True)
    run([KEYTOOL, "-genkeypair", "-v",
         "-keystore", KEYSTORE,
         "-alias", KEY_ALIAS,
         "-keyalg", "RSA", "-keysize", "2048",
         "-validity", "10000",
         "-storepass", KEY_PASS, "-keypass", KEY_PASS,
         "-dname", "CN=Retro Spaceship, OU=Games, O=Ashwin, L=, ST=, C=IN"],
        "creating signing key")


def java_sources():
    sources = []
    for folder, _dirs, files in os.walk(os.path.join(ANDROID, "java")):
        for name in files:
            if name.endswith(".java"):
                sources.append(os.path.join(folder, name))
    return sorted(sources)


def main():
    check_toolchain()
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    for folder in (BUILD, DIST, os.path.join(BUILD, "classes"),
                   os.path.join(BUILD, "dex"), os.path.join(BUILD, "res")):
        os.makedirs(folder, exist_ok=True)
    ensure_keystore()

    print("building Retro Spaceship")

    # 1. Compile resources.
    compiled = os.path.join(BUILD, "res.zip")
    run([AAPT2, "compile", "--dir", os.path.join(ANDROID, "res"), "-o", compiled],
        "aapt2 compile")

    # 2. Link resources, manifest and assets into a resources-only APK.
    linked = os.path.join(BUILD, "linked.apk")
    run([AAPT2, "link",
         "-o", linked,
         "-I", ANDROID_JAR,
         "--manifest", os.path.join(ANDROID, "AndroidManifest.xml"),
         "-A", os.path.join(ANDROID, "assets"),
         "--min-sdk-version", "24",
         "--target-sdk-version", "35",
         "--auto-add-overlay",
         compiled],
        "aapt2 link")

    # 3. Compile Java. android.jar goes on the classpath rather than the boot
    #    path, which is what modern JDKs expect; the app touches no JDK-only API.
    sources = java_sources()
    sources_file = os.path.join(BUILD, "sources.txt")
    with open(sources_file, "w", encoding="utf-8") as handle:
        handle.write("\n".join(sources))
    run([JAVAC,
         "-source", "11", "-target", "11",
         "-encoding", "UTF-8",
         "-nowarn", "-Xlint:none",
         "-classpath", ANDROID_JAR,
         "-d", os.path.join(BUILD, "classes"),
         "@" + sources_file],
        "javac (%d files)" % len(sources))

    # 4. Dex.
    classes = []
    for folder, _dirs, files in os.walk(os.path.join(BUILD, "classes")):
        for name in files:
            if name.endswith(".class"):
                classes.append(os.path.join(folder, name))
    run([JAVA, "-cp", D8_JAR, "com.android.tools.r8.D8",
         "--min-api", "24",
         "--lib", ANDROID_JAR,
         "--output", os.path.join(BUILD, "dex")] + sorted(classes),
        "d8 (%d classes)" % len(classes))

    # 5. Fold the dex into the linked APK.
    unsigned = os.path.join(BUILD, "unsigned.apk")
    shutil.copyfile(linked, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(os.listdir(os.path.join(BUILD, "dex"))):
            if name.endswith(".dex"):
                archive.write(os.path.join(BUILD, "dex", name), name)
    print("  .. packaged dex")

    # 6. Align, then sign. Alignment has to happen first -- apksigner preserves
    #    it, and zipalign after signing would break the signature.
    aligned = os.path.join(BUILD, "aligned.apk")
    run([ZIPALIGN, "-p", "-f", "4", unsigned, aligned], "zipalign")

    output = os.path.join(DIST, APK_NAME)
    if os.path.exists(output):
        os.remove(output)
    run([JAVA, "-jar", APKSIGNER_JAR, "sign",
         "--ks", KEYSTORE,
         "--ks-key-alias", KEY_ALIAS,
         "--ks-pass", "pass:" + KEY_PASS,
         "--key-pass", "pass:" + KEY_PASS,
         "--v1-signing-enabled", "true",
         "--v2-signing-enabled", "true",
         "--min-sdk-version", "24",
         "--out", output,
         aligned],
        "apksigner sign")

    verify = run([JAVA, "-jar", APKSIGNER_JAR, "verify", "--print-certs", output],
                 "apksigner verify")

    size = os.path.getsize(output)
    print()
    print("APK: %s" % output)
    print("size: %.2f MB" % (size / 1024 / 1024))
    for line in verify.stdout.splitlines():
        if "Signer #1 certificate DN" in line or "SHA-256" in line:
            print(line.strip())


if __name__ == "__main__":
    main()
