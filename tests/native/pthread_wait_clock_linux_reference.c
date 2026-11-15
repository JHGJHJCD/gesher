// Independent Linux checks for the POSIX/glibc intersection of the repaired
// contracts. Android-only nullable deadlines, sem_clockwait's +EINVAL, and
// wrong-writer unlock behavior and free-rwlock invalid-timespec precedence
// are intentionally not inferred from glibc.
#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <time.h>

static int checks, failures;
static void check(int ok, const char* label) {
  ++checks;
  if (!ok) ++failures;
  printf("%s %s\n", ok ? "PASS" : "FAIL", label);
  fflush(stdout);
}
int main(void) {
  pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
  struct timespec invalid = {1, 1000000000}, expired = {0, 0};
  check(pthread_mutex_timedlock(&mutex, &invalid) == 0, "free mutex ignores invalid deadline");
  check(pthread_mutex_timedlock(&mutex, &invalid) == EINVAL, "contended mutex rejects invalid deadline");
  pthread_mutex_unlock(&mutex);
  errno = 321;
  check(pthread_mutex_clocklock(&mutex, CLOCK_PROCESS_CPUTIME_ID, &expired) == EINVAL &&
        errno == 321, "invalid clock is a pthread error, not errno");
  check(pthread_mutex_trylock(&mutex) == 0, "invalid clock did not acquire");
  pthread_mutex_unlock(&mutex);

  pthread_rwlock_t rw = PTHREAD_RWLOCK_INITIALIZER;
  int acquired = pthread_rwlock_timedrdlock(&rw, &expired);
  check(acquired == 0, "free timed reader acquires before a valid expired deadline");
  if (acquired == 0) pthread_rwlock_unlock(&rw);
  pthread_rwlock_wrlock(&rw);
  check(pthread_rwlock_tryrdlock(&rw) == EBUSY && pthread_rwlock_trywrlock(&rw) == EBUSY,
        "writer try operations stay busy");
  check(pthread_rwlock_timedrdlock(&rw, &expired) == EDEADLK, "writer timed read detects self");
  check(pthread_rwlock_timedwrlock(&rw, &expired) == EDEADLK, "writer timed write detects self");
  pthread_rwlock_unlock(&rw);

  for (int attr_clock = 0; attr_clock <= 1; ++attr_clock) {
    for (int explicit_clock = 0; explicit_clock <= 1; ++explicit_clock) {
      pthread_condattr_t attr;
      pthread_condattr_init(&attr);
      check(pthread_condattr_setclock(&attr, attr_clock) == 0, "set supported condvar clock");
      pthread_cond_t condition;
      int initialized = pthread_cond_init(&condition, &attr);
      check(initialized == 0, "initialize reference condition variable");
      pthread_condattr_destroy(&attr);
      if (initialized != 0) continue;
      struct timespec deadline;
      clock_gettime(explicit_clock, &deadline);
      --deadline.tv_sec;
      pthread_mutex_lock(&mutex);
      // No signal competes with the expired deadline: a real signal could
      // legally win if this thread were descheduled. The runner provides the
      // external timeout for this independent libc reference.
      check(pthread_cond_clockwait(&condition, &mutex, explicit_clock, &deadline) == ETIMEDOUT,
            "explicit clock overrides condvar attribute");
      pthread_mutex_unlock(&mutex);
      pthread_cond_destroy(&condition);
    }
  }
  pthread_rwlock_destroy(&rw);
  pthread_mutex_destroy(&mutex);
  printf("Linux pthread clock reference: %d checks, %d failures\n", checks, failures);
  return failures != 0;
}
