"""Session 1 smoke checks beyond jpy_gettype_test.py: identity, calls, fields, arrays, errors."""
import sys

import jpyutil

jpyutil.init_jvm(jvm_classpath=['target/test-classes'])
import jpy

checks = []


def check(name, ok):
    checks.append((name, ok))
    print(('PASS ' if ok else 'FAIL ') + name)


check('FFM jpy module (pure Python, no C extension)', jpy.__file__.endswith('ffm/python/jpy.py'))
check('no compiled jpy extension imported',
      not any(str(getattr(m, '__file__', '')).endswith(('.so', '.pyd')) for n, m in list(sys.modules.items())
              if n.startswith(('jpy', 'jdl'))))

System = jpy.get_type('java.lang.System')
cp = System.getProperty('java.class.path')
check('C jpy jar not on the JVM class path', 'jpy-2.' not in cp)

String = jpy.get_type('java.lang.String')
s = String('hello')
check('construct String via __jinit__', str(s) == 'hello')
check('instance method returning String -> str', s.toUpperCase() == 'HELLO')
check('overload by arg type: indexOf(str) and indexOf(int)', s.indexOf('l') == 2 and s.indexOf(ord('l')) == 2)
check('static method with int arg: String.valueOf(42)', String.valueOf(42) == '42')
check('equals via ==', String('a') == String('a'))
check('hash is Java hashCode', hash(String('a')) == 97)
check('jclass and jclassname', String.jclassname == 'java.lang.String' and str(String.jclass) == 'class java.lang.String')
check('type is registered in jpy.types', jpy.types['java.lang.String'] is String)
check('java.lang.Object derives from jpy.JType', issubclass(jpy.get_type('java.lang.Object'), jpy.JType))

ArrayList = jpy.get_type('java.util.ArrayList')
lst = ArrayList()
lst.add('x')
lst.add(7)
check('ArrayList.add mixes str and int (int boxed as smallest box)',
      lst.size() == 2 and str(lst.get(1)) == '7' and lst.get(1) == 7)

Point = jpy.get_type('java.awt.Point')
p = Point(3, 4)
check('instance field read', p.x == 3 and p.y == 4)
p.x = 10
check('instance field write', p.x == 10 and p.getX() == 10.0)

IntArray = jpy.get_type('[I')
Arrays = jpy.get_type('java.util.Arrays')
arr = Arrays.copyOf(jpy.get_type('java.util.Arrays').asList('a', 'b').toArray(), 2)
check('object array length and items', len(arr) == 2 and arr[0] == 'a')

Integer = jpy.get_type('java.lang.Integer')
try:
    Integer.parseInt('nope')
    check('Java exception -> RuntimeError', False)
except RuntimeError as e:
    check('Java exception -> RuntimeError with Java text', 'NumberFormatException' in str(e))

try:
    String.valueOf()
    check('no matching overload raises', False)
except RuntimeError as e:
    check('no matching overload raises', str(e) == 'no matching Java method overloads found')

check('static final field snapshot', Integer.MAX_VALUE == 2147483647)

failed = [n for n, ok in checks if not ok]
print(f'\n{len(checks) - len(failed)}/{len(checks)} passed')
sys.exit(1 if failed else 0)
