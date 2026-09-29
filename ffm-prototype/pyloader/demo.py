"""Demo: everything the POC jbridge offers, in one Python-first process."""
import jbridge_loader

jbridge_loader.start_jvm()
import jbridge

print("1. Python -> Java:            jbridge.java_add(20, 22) =", jbridge.java_add(20, 22))
print("2. Java state visible:        jbridge.java_version()   =", jbridge.java_version())
print("3. Round trip Py->Java->Py:   jbridge.apply(lambda x: x * 2, 21) =",
      jbridge.apply(lambda x: x * 2, 21), "(Python doubled 21, Java added 100)")

try:
    jbridge.apply(lambda x: 1 / 0, 1)
except RuntimeError as e:
    print("4. Errors cross both ways:   ", e)

import threading
results = []
ts = [threading.Thread(target=lambda: results.append(jbridge.java_add(1, 1))) for _ in range(8)]
[t.start() for t in ts]
[t.join() for t in ts]
print("5. Any Python thread works:   8 threads ->", results)

print("6. Introspection:             jbridge.apply.__doc__ =", repr(jbridge.apply.__doc__))
