import re

src = open('/Users/jianfengmao/.sdkman/candidates/java/25.0.3-tem/include/jni.h').read()
body = re.search(r'struct JNINativeInterface_ \{(.*?)\n\};', src, re.S).group(1)
idx = 0
wanted = {'GetMethodID', 'CallObjectMethodA', 'NewStringUTF', 'FindClass', 'GetStaticMethodID', 'CallStaticObjectMethodA', 'NewGlobalRef', 'DeleteGlobalRef', 'DeleteLocalRef', 'GetPrimitiveArrayCritical', 'ReleasePrimitiveArrayCritical', 'ExceptionCheck', 'ExceptionDescribe',
          }
for line in body.splitlines():
    line = line.strip()
    if line.startswith('void *reserved'):
        idx += 1
    else:
        m = re.search(r'\(JNICALL \*(\w+)\)', line)
        if m:
            if m.group(1) in wanted:
                print(idx, m.group(1))
            idx += 1
