"""
What Python introspection shows about jpy's types, objects and methods, one fact per line.
Run against the C jpy to record a reference file (see make_c_reference.sh); ffm_introspection_test.py
runs it against the FFM jpy and compares. Addresses are replaced by 0x?, since they differ by run.
Usage: python introspection_probe.py <classpath entries...>
"""
import inspect
import re
import weakref

TYPES = ['java.lang.Object', 'java.lang.String', 'java.util.ArrayList', 'java.util.List',
         'java.lang.Integer', 'int', '[I', '[Ljava.lang.String;', 'java.lang.Thread$State']

OVERLOADS = [('java.lang.String', 'length'), ('java.lang.String', 'valueOf'),
             ('java.lang.String', 'format'), ('java.lang.String', '__jinit__'),
             ('java.util.ArrayList', 'remove'), ('java.util.ArrayList', 'add'),
             ('java.util.ArrayList', 'hashCode'), ('java.lang.Math', 'max'),
             ('java.util.Arrays', 'asList'), ('java.lang.Object', 'wait')]


def facts(jpy):
    out = []

    def show(label, f):
        try:
            v = f()
        except Exception as e:
            v = 'EXC %s: %s' % (type(e).__name__, e)
        out.append('%s = %s' % (label, re.sub(r'0x[0-9a-f]+', '0x?', str(v))))

    def tname(t):
        return getattr(t, '__name__', repr(t))

    for name in TYPES:
        T = jpy.get_type(name)
        p = name
        show(p + ' repr', lambda: repr(T))
        show(p + ' str', lambda: str(T))
        show(p + ' __name__', lambda: T.__name__)
        show(p + ' __qualname__', lambda: T.__qualname__)
        show(p + ' __module__', lambda: T.__module__)
        show(p + ' __doc__', lambda: T.__doc__)
        show(p + ' __mro__', lambda: [c.__name__ for c in T.__mro__])
        show(p + ' __bases__', lambda: [c.__name__ for c in T.__bases__])
        show(p + ' type(T).__name__', lambda: type(T).__name__)
        show(p + ' heaptype', lambda: bool(T.__flags__ & (1 << 9)))
        show(p + ' basetype', lambda: bool(T.__flags__ & (1 << 10)))
        show(p + ' immutable', lambda: bool(T.__flags__ & (1 << 8)))
        show(p + ' __basicsize__', lambda: T.__basicsize__)
        show(p + ' __itemsize__', lambda: T.__itemsize__)
        show(p + ' __dictoffset__', lambda: T.__dictoffset__)
        show(p + ' __weakrefoffset__', lambda: T.__weakrefoffset__)
        show(p + ' dunder keys', lambda: sorted(k for k in T.__dict__ if k.startswith('__')))
        show(p + ' member keys', lambda: sorted(k for k in T.__dict__ if not k.startswith('__')))
        show(p + ' value types', lambda: sorted({type(v).__name__ for k, v in T.__dict__.items() if not k.startswith('__')}))
        show(p + ' dir', lambda: sorted(dir(T)))
        show(p + ' jclass', lambda: (type(T.jclass).__name__, str(T.jclass)))
        show(p + ' setattr new', lambda: setattr(T, 'zz_probe', 1) or T.__dict__.get('zz_probe'))
        show(p + ' subclass', lambda: type('Sub', (T,), {}).__mro__[1].__name__)

    String = jpy.get_type('java.lang.String')
    s = String('abc')
    show('inst repr', lambda: repr(s))
    show('inst str', lambda: str(s))
    show('inst type', lambda: type(s).__name__)
    show('inst __dict__', lambda: s.__dict__)
    show('inst setattr', lambda: setattr(s, 'zz', 1))
    show('inst weakref', lambda: weakref.ref(s) is not None)
    show('inst hash==hashCode', lambda: hash(s) == s.hashCode())
    show('inst dir has length', lambda: 'length' in dir(s))

    f = jpy.get_type('java.awt.Point').__dict__.get('x')
    show('field type', lambda: type(f).__name__)
    show('field repr', lambda: repr(f))
    show('field str', lambda: str(f))
    show('field doc', lambda: f.__doc__)
    show('field dir', lambda: sorted(d for d in dir(f) if not d.startswith('__')))
    show('field attrs', lambda: (f.name, f.is_static, f.is_final))

    for cls, name in OVERLOADS:
        T = jpy.get_type(cls)
        om = T.__dict__.get(name)
        p = '%s.%s' % (cls, name)
        if om is None:
            show(p + ' in dict', lambda: False)
            om = getattr(T, name)
        show(p + ' type', lambda: type(om).__name__)
        show(p + ' repr', lambda: repr(om))
        show(p + ' str', lambda: str(om))
        show(p + ' doc', lambda: om.__doc__)
        show(p + ' dir', lambda: sorted(d for d in dir(om) if not d.startswith('__')))
        show(p + ' dunder dir', lambda: sorted(d for d in dir(om) if d.startswith('__')))
        show(p + ' name', lambda: om.name)
        show(p + ' decl_class', lambda: tname(om.decl_class))
        show(p + ' count', lambda: len(om.methods))
        for i, m in enumerate(om.methods):
            q = '%s[%d]' % (p, i)
            show(q + ' type', lambda: type(m).__name__)
            show(q + ' repr', lambda: repr(m))
            show(q + ' str', lambda: str(m))
            show(q + ' doc', lambda: m.__doc__)
            show(q + ' dir', lambda: sorted(d for d in dir(m) if not d.startswith('__')))
            show(q + ' name', lambda: m.name)
            show(q + ' param_count', lambda: m.param_count)
            show(q + ' is_static', lambda: m.is_static)
            show(q + ' return_type', lambda: tname(m.return_type))
            show(q + ' params', lambda: [tname(m.get_param_type(k)) for k in range(m.param_count)])
            show(q + ' param flags', lambda: [(m.is_param_mutable(k), m.is_param_output(k), m.is_param_return(k))
                                             for k in range(m.param_count)])
            show(q + ' bad index', lambda: m.get_param_type(m.param_count))

    show('bound type', lambda: type(s.length).__name__)
    show('bound repr', lambda: repr(s.length))
    show('bound __self__', lambda: s.length.__self__ is s)
    show('bound __func__', lambda: type(s.length.__func__).__name__)
    show('static via instance', lambda: type(s.valueOf).__name__)
    show('class attr type', lambda: type(String.length).__name__)
    show('class call unbound', lambda: String.length(s))
    show('class call no self', lambda: String.length())
    show('wrong arg count', lambda: s.charAt())
    show('wrong arg type', lambda: s.charAt('x'))
    show('no match msg', lambda: jpy.get_type('java.lang.Math').max('a', 'b'))
    show('kwargs', lambda: s.charAt(index=0))
    show('getattr missing', lambda: s.noSuchMethod)
    show('inspect.isroutine', lambda: inspect.isroutine(String.length))
    show('signature', lambda: str(inspect.signature(String.length)))
    for t in ['JType', 'JMethod', 'JOverloadedMethod', 'JField']:
        show(t + ' names', lambda: (getattr(jpy, t).__name__, getattr(jpy, t).__module__))
        show(t + ' doc', lambda: getattr(jpy, t).__doc__)
        show(t + ' immutable', lambda: bool(getattr(jpy, t).__flags__ & (1 << 8)))
        show(t + ' instantiate', lambda: getattr(jpy, t)())
    show('diag type', lambda: (type(jpy.diag).__name__, type(jpy.diag).__module__))
    show('VerboseExceptions type', lambda: (type(jpy.VerboseExceptions).__name__, type(jpy.VerboseExceptions).__module__))
    show('module names', lambda: sorted(n for n in dir(jpy) if not n.startswith('_')))
    return out


if __name__ == '__main__':
    import sys
    import jpyutil
    jpyutil.init_jvm(jvm_maxmem='512M', jvm_classpath=sys.argv[1:])
    import jpy
    print('\n'.join(facts(jpy)))
