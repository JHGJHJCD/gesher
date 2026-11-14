// Shared actual-VM regression: 18 arguments exceed JNI Args' inline capacity
// and spill both GP and FP registers on x86-64 and translated ARM64.
#pragma once

#include <jni.h>
#include <stdarg.h>

static jdouble jni_argument_call_v(JNIEnv* env, jclass cls, jmethodID method, ...) {
  va_list ap;
  va_start(ap, method);
  jdouble result = (*env)->CallStaticDoubleMethodV(env, cls, method, ap);
  va_end(ap);
  return result;
}

static int jni_argument_wide_callback(JNIEnv* env, jclass cls) {
  jmethodID method = (*env)->GetStaticMethodID(env, cls, "wideCallback", "(IDIDIDIDIDIDIDIDID)D");
  if (!method) return 0;
  const jdouble expected = 28571.25;
  jdouble direct = (*env)->CallStaticDoubleMethod(env, cls, method,
      1, 0.25, 2, 0.5, 3, 0.75, 4, 1.0, 5, 1.25, 6, 1.5, 7, 1.75, 8, 2.0, 9, 2.25);
  if ((*env)->ExceptionCheck(env)) return 0;
  jdouble list = jni_argument_call_v(env, cls, method,
      1, 0.25, 2, 0.5, 3, 0.75, 4, 1.0, 5, 1.25, 6, 1.5, 7, 1.75, 8, 2.0, 9, 2.25);
  if ((*env)->ExceptionCheck(env)) return 0;
  jvalue values[18] = {{0}};
  for (int i = 0; i < 9; ++i) {
    values[i * 2].i = i + 1;
    values[i * 2 + 1].d = (i + 1) * 0.25;
  }
  jdouble array = (*env)->CallStaticDoubleMethodA(env, cls, method, values);
  return !(*env)->ExceptionCheck(env) && direct == expected && list == expected && array == expected;
}
