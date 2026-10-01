"""
Improvement over the C jpy: public static non-final fields are visible and writable from
Python, live, through the class and through instances. Also: jpy.JTypeMeta, and class-level
access resolves an unresolved type.
"""
import sys

import jpyutil

jpyutil.init_jvm(jvm_classpath=['target/test-classes', 'ffm/build/fixtures'])
import jpy

checks = []


def check(name, ok):
    checks.append((name, ok))
    print(('PASS ' if ok else 'FAIL ') + name)


F = jpy.get_type('org.jpy.ffm.fixtures.StaticFieldsFixture')
Child = jpy.get_type('org.jpy.ffm.fixtures.StaticFieldsChild')

# metaclass shape
check('type(T) is jpy.JTypeMeta', type(F) is jpy.JTypeMeta)
check('type(jpy.JTypeMeta) is type', type(jpy.JTypeMeta) is type)
check('jpy.JTypeMeta subclasses type', issubclass(jpy.JTypeMeta, type))
check('jpy.JType is still the base of every Java class', issubclass(F, jpy.JType))
check('Java objects are still instances of jpy.JType', isinstance(F(), jpy.JType))
check("str(T) unchanged", str(F) == "<class 'org.jpy.ffm.fixtures.StaticFieldsFixture'>")
check("str of a dot-less type unchanged", str(jpy.get_type('[I')) == "<class '[I'>")

# live reads and writes through the class
check('class read: int', F.counter == 7)
F.counter = 41
check('class write reaches Java', F.bump() == 42 and F.counter == 42)
F.label = 'changed'
check('class write: String', F.getLabel() == 'changed' and F.label == 'changed')
F.anything = 'a str'
check('class write: Object field takes a Python str', F.anything == 'a str')
F.anything = None
check('class write: None -> null', F.anything is None)

# through instances, as Java allows
obj = F()
check('instance read of a static field', obj.counter == 42)
obj.counter = 100
check('instance write of a static field', F.counter == 100)
check('instance fields still work', obj.instanceField == 3)

# inheritance
check('static field inherited through a subclass', Child.counter == 100)
Child.counter = 5
check('write through subclass reaches the declaring class', F.counter == 5)
check("subclass's own static field", Child.childCounter == 1)

# parity is kept where the C jpy already had behavior
check('static final stays a plain value (C jpy behavior)', F.CONSTANT == 99)
check('method keeps its name over a same-named static field', F.shared() == 2)
check('private static fields stay invisible',
      not hasattr(F, 'hidden') and F.getHidden() == 5)
try:
    F.counter = 'not an int'
    check('wrong type raises', False)
except (TypeError, ValueError, RuntimeError):
    check('wrong type raises and leaves the value alone', F.counter == 5)

# class-level access resolves an unresolved type (C jpy: AttributeError)
Lazy = jpy.get_type('java.util.zip.CRC32', resolve=False)
check('class-level access on an unresolved type resolves it', hasattr(Lazy, 'getValue'))

failed = [n for n, ok in checks if not ok]
print(f'\n{len(checks) - len(failed)}/{len(checks)} passed')
sys.exit(1 if failed else 0)
