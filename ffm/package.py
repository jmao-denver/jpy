"""
Builds the FFM jpy as a pure-Python wheel: jpy.py, jpyutil.py, jdl.py and jpy-ffm.jar at the top of
site-packages, where the C jpy's jpy and jdl extension modules and jpyutil.py go today. Also writes the
jar on its own, for a Java build to use in place of org.jpyconsortium:jpy.

Usage: python ffm/package.py [version]      (run ffm/ci.py build first; output in ffm/build/dist)
"""
import base64
import hashlib
import os
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FFM = os.path.join(ROOT, 'ffm')
DIST = os.path.join(FFM, 'build', 'dist')
VERSION = sys.argv[1] if len(sys.argv) > 1 else '3.0.0.dev0'

# The C jpy installs a 'jdl' extension module next to 'jpy', and Deephaven requires the two to be
# siblings (io.deephaven.jpy.JpyConfig). The FFM jpy needs no jdl: org.jpy.DL calls dlopen through FFM.
JDL_PY = '''"""Placeholder for the C jpy's 'jdl' module. The FFM jpy's org.jpy.DL uses FFM, so nothing loads this."""
'''


def jar():
    java_home = os.environ['JAVA_HOME']
    tool = os.path.join(java_home, 'bin', 'jar' + ('.exe' if os.name == 'nt' else ''))
    path = os.path.join(DIST, 'jpy-ffm-%s.jar' % VERSION)
    if os.path.exists(path):
        os.remove(path)
    subprocess.run([tool, '--create', '--file', path, '-C', os.path.join(FFM, 'build', 'classes'), '.'], check=True)
    return path


def record_line(name, data):
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b'=').decode()
    return '%s,sha256=%s,%d' % (name, digest, len(data))


def wheel(jar_path):
    files = {
        'jpy.py': open(os.path.join(FFM, 'python', 'jpy.py'), 'rb').read(),
        'jpyutil.py': open(os.path.join(FFM, 'python', 'jpyutil.py'), 'rb').read(),
        'jdl.py': JDL_PY.encode(),
        'jpy-ffm.jar': open(jar_path, 'rb').read(),
    }
    info = 'jpy-%s.dist-info' % VERSION
    files[info + '/METADATA'] = ('Metadata-Version: 2.1\nName: jpy\nVersion: %s\n'
                                 'Summary: Bi-directional Python-Java bridge on the Java FFM API (JDK 25+)\n'
                                 'Requires-Python: >=3.12\n' % VERSION).encode()
    files[info + '/WHEEL'] = b'Wheel-Version: 1.0\nGenerator: ffm/package.py\nRoot-Is-Purelib: true\nTag: py3-none-any\n'
    files[info + '/top_level.txt'] = b'jdl\njpy\njpyutil\n'
    record = [record_line(n, d) for n, d in files.items()] + [info + '/RECORD,,']
    files[info + '/RECORD'] = ('\n'.join(record) + '\n').encode()
    path = os.path.join(DIST, 'jpy-%s-py3-none-any.whl' % VERSION)
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, data)
    return path


def main():
    os.makedirs(DIST, exist_ok=True)
    j = jar()
    w = wheel(j)
    print('built', os.path.relpath(j, ROOT))
    print('built', os.path.relpath(w, ROOT))


if __name__ == '__main__':
    main()
