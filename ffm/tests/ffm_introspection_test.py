"""
Introspection parity: what Python shows about jpy's types, objects and methods must match the C jpy,
except for the differences listed in KNOWN. The reference comes from running introspection_probe.py
on the C jpy (make_c_reference.sh), one file per interpreter, recorded with JDK 25: method lists in
dir() depend on the JDK, so other JDKs skip.
"""
import difflib
import os
import re
import sys
import sysconfig
import unittest

import jpyutil

jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=['target/test-classes'])
import jpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import introspection_probe

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data')

# Differences that come from building types with PyType_FromSpec (heap types) instead of the
# C jpy's static types, plus the metaclass (DESIGN.md §1). Applied to both sides before comparing.
KNOWN = [
    # Java types are instances of jpy.JTypeMeta, not type
    (re.compile(r"^(.* type\(T\)\.__name__) = JTypeMeta$"), r"\1 = type"),
    # heap types carry the heap-type flag
    (re.compile(r"^.* heaptype = .*$"), None),
    # no per-object C struct, so a smaller basic size
    (re.compile(r"^.* __basicsize__ = .*$"), None),
    # heap types keep __module__ in their dict, so it also shows up in dir()
    (re.compile(r"'__module__', "), ""),
    # the metaclass is a module attribute
    (re.compile(r"'JTypeMeta', "), ""),
]


def normalize(lines):
    out = []
    for line in lines:
        for pattern, repl in KNOWN:
            if repl is None:
                if pattern.match(line):
                    line = None
                    break
            else:
                line = pattern.sub(repl, line)
        if line is not None:
            out.append(line)
    return out


def tag():
    free_threaded = bool(sysconfig.get_config_var('Py_GIL_DISABLED'))
    return '%d.%d%s' % (sys.version_info[0], sys.version_info[1], 't' if free_threaded else '')


class TestIntrospectionParity(unittest.TestCase):
    def test_matches_c_jpy(self):
        System = jpy.get_type('java.lang.System')
        if System.getProperty('java.specification.version') != '25':
            self.skipTest('reference recorded with JDK 25')
        ref_file = os.path.join(DATA, 'c_jpy_introspection_%s.txt' % tag())
        if not os.path.exists(ref_file):
            self.skipTest('no C jpy reference for ' + tag())
        with open(ref_file) as f:
            expected = normalize(f.read().splitlines())
        actual = normalize(introspection_probe.facts(jpy))
        diff = list(difflib.unified_diff(expected, actual, 'C jpy', 'FFM jpy', lineterm='', n=0))
        self.assertEqual(diff, [], '\n' + '\n'.join(diff[:60]))


if __name__ == '__main__':
    print('\nRunning ' + __file__)
    unittest.main()
