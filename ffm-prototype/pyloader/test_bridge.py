"""Python-first test: python starts, boots a JVM, imports the Java-made module."""
import sys

import jbridge_loader

jbridge_loader.start_jvm()

import jbridge  # registered by Java via FFM; no .so anywhere

r = jbridge.java_add(20, 22)
assert r == 42, r
print("PASS jbridge.java_add(20, 22) ==", r)

v = jbridge.java_version()
assert v.startswith("25"), v
print("PASS jbridge.java_version() ==", v)

try:
    jbridge.java_add(1)
    raise SystemExit("FAIL: expected RuntimeError")
except RuntimeError as e:
    print("PASS error translation:", e)

print("doc:", jbridge.java_add.__doc__)
print("BRIDGE OK (python", sys.version.split()[0] + ", pure-python loader, zero C)")
