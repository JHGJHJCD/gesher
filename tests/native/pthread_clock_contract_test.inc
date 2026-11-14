// Public Android pthread clock/deadline contracts, shared by the host envelope
// and both native ELF ABI suites. Resolve exports exactly as an app would.
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

typedef int (*pc_one_fn)(void*);
typedef int (*pc_init_fn)(void*, const void*);
typedef int (*pc_attr_fn)(void*, int);
typedef int (*pc_timed_fn)(void*, const struct timespec*);
typedef int (*pc_clock_fn)(void*, int, const struct timespec*);
typedef int (*pc_cond_fn)(void*, void*, const struct timespec*);
typedef int (*pc_cond_clock_fn)(void*, void*, int, const struct timespec*);
typedef int (*pc_sem_init_fn)(void*, int, unsigned);
typedef int (*pc_sem_value_fn)(void*, int*);

struct pc_api {
  pc_init_fn mutex_init, cond_init, rw_init;
  pc_one_fn mutex_lock, mutex_try, mutex_unlock, mutex_attr_init;
  pc_attr_fn mutex_attr_type, cond_attr_clock;
  pc_timed_fn mutex_timed, mutex_mono, rd_timed, wr_timed, rd_mono, wr_mono;
  pc_clock_fn mutex_clock, rd_clock, wr_clock, sem_clock;
  pc_cond_fn cond_timed, cond_mono;
  pc_cond_clock_fn cond_clock;
  pc_one_fn cond_signal, rw_read, rw_write, rw_tryread, rw_trywrite, rw_unlock, rw_destroy;
  pc_sem_init_fn sem_init;
  pc_sem_value_fn sem_value;
};

static struct pc_api pc_load(void) {
  struct pc_api a;
#define PC_LOAD(field, type, name) a.field = (type)dlsym(RTLD_DEFAULT, name)
  PC_LOAD(mutex_init, pc_init_fn, "pthread_mutex_init");
  PC_LOAD(cond_init, pc_init_fn, "pthread_cond_init");
  PC_LOAD(rw_init, pc_init_fn, "pthread_rwlock_init");
  PC_LOAD(mutex_lock, pc_one_fn, "pthread_mutex_lock");
  PC_LOAD(mutex_try, pc_one_fn, "pthread_mutex_trylock");
  PC_LOAD(mutex_unlock, pc_one_fn, "pthread_mutex_unlock");
  PC_LOAD(mutex_attr_init, pc_one_fn, "pthread_mutexattr_init");
  PC_LOAD(mutex_attr_type, pc_attr_fn, "pthread_mutexattr_settype");
  PC_LOAD(cond_attr_clock, pc_attr_fn, "pthread_condattr_setclock");
  PC_LOAD(mutex_timed, pc_timed_fn, "pthread_mutex_timedlock");
  PC_LOAD(mutex_mono, pc_timed_fn, "pthread_mutex_timedlock_monotonic_np");
  PC_LOAD(rd_timed, pc_timed_fn, "pthread_rwlock_timedrdlock");
  PC_LOAD(wr_timed, pc_timed_fn, "pthread_rwlock_timedwrlock");
  PC_LOAD(rd_mono, pc_timed_fn, "pthread_rwlock_timedrdlock_monotonic_np");
  PC_LOAD(wr_mono, pc_timed_fn, "pthread_rwlock_timedwrlock_monotonic_np");
  PC_LOAD(mutex_clock, pc_clock_fn, "pthread_mutex_clocklock");
  PC_LOAD(rd_clock, pc_clock_fn, "pthread_rwlock_clockrdlock");
  PC_LOAD(wr_clock, pc_clock_fn, "pthread_rwlock_clockwrlock");
  PC_LOAD(sem_clock, pc_clock_fn, "sem_clockwait");
  PC_LOAD(cond_timed, pc_cond_fn, "pthread_cond_timedwait");
  PC_LOAD(cond_mono, pc_cond_fn, "pthread_cond_timedwait_monotonic_np");
  PC_LOAD(cond_clock, pc_cond_clock_fn, "pthread_cond_clockwait");
  PC_LOAD(cond_signal, pc_one_fn, "pthread_cond_signal");
  PC_LOAD(rw_read, pc_one_fn, "pthread_rwlock_rdlock");
  PC_LOAD(rw_write, pc_one_fn, "pthread_rwlock_wrlock");
  PC_LOAD(rw_tryread, pc_one_fn, "pthread_rwlock_tryrdlock");
  PC_LOAD(rw_trywrite, pc_one_fn, "pthread_rwlock_trywrlock");
  PC_LOAD(rw_unlock, pc_one_fn, "pthread_rwlock_unlock");
  PC_LOAD(rw_destroy, pc_one_fn, "pthread_rwlock_destroy");
  PC_LOAD(sem_init, pc_sem_init_fn, "sem_init");
  PC_LOAD(sem_value, pc_sem_value_fn, "sem_getvalue");
#undef PC_LOAD
  return a;
}

static int pc_present(const struct pc_api* a) {
  return a->mutex_init && a->cond_init && a->rw_init && a->mutex_lock && a->mutex_try &&
      a->mutex_unlock && a->mutex_attr_init && a->mutex_attr_type && a->cond_attr_clock &&
      a->mutex_timed && a->mutex_mono && a->rd_timed && a->wr_timed && a->rd_mono && a->wr_mono &&
      a->mutex_clock && a->rd_clock && a->wr_clock && a->sem_clock && a->cond_timed &&
      a->cond_mono && a->cond_clock && a->cond_signal && a->rw_read && a->rw_write &&
      a->rw_tryread && a->rw_trywrite && a->rw_unlock && a->rw_destroy && a->sem_init && a->sem_value;
}

static void test_pthread_clock_public(void) {
  struct pc_api a = pc_load();
  check("pthread clocks: every selected public export exists", pc_present(&a));
  if (!pc_present(&a)) return;
  const int invalid[] = {-1, 2, 3, 7, INT_MAX};
  struct timespec expired = {0, 0};
  for (unsigned i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
    pthread_mutex_t m;
    pthread_cond_t c;
    pthread_rwlock_t rw;
    uint64_t sem[2] = {0, 0};
    int64_t attr = 1;
    a.mutex_init(&m, NULL);
    a.cond_init(&c, NULL);
    a.rw_init(&rw, NULL);
    errno = 321;
    int result = a.mutex_clock(&m, invalid[i], &expired);
    check("invalid mutex clock: EINVAL, unchanged errno", result == EINVAL && errno == 321);
    int acquired = a.mutex_try(&m);
    check("invalid mutex clock did not acquire", acquired == 0);
    if (acquired == 0 || result == 0) a.mutex_unlock(&m);
    a.mutex_lock(&m);
    errno = 321;
    result = a.cond_clock(&c, &m, invalid[i], &expired);
    check("invalid cond clock: EINVAL, unchanged errno", result == EINVAL && errno == 321);
    check("invalid cond clock retains mutex", a.mutex_try(&m) == EBUSY);
    a.mutex_unlock(&m);
    check("invalid cond attribute preserves prior clock",
          a.cond_attr_clock(&attr, invalid[i]) == EINVAL && attr == 1);
    const pc_clock_fn locks[] = {a.rd_clock, a.wr_clock};
    for (unsigned j = 0; j < 2; ++j) {
      a.rw_init(&rw, NULL);
      errno = 321;
      result = locks[j](&rw, invalid[i], &expired);
      check("invalid rwlock clock: EINVAL, unchanged errno", result == EINVAL && errno == 321);
      acquired = a.rw_trywrite(&rw);
      check("invalid rwlock clock did not acquire", acquired == 0);
      if (acquired == 0 || result == 0) a.rw_unlock(&rw);
    }
    for (unsigned value = 0; value <= 1; ++value) {
      a.sem_init(sem, 0, value);
      errno = 321;
      result = a.sem_clock(sem, invalid[i], &expired);
      check("Android invalid semaphore clock: positive EINVAL, unchanged errno",
            result == EINVAL && errno == 321);
      int remaining = -1;
      a.sem_value(sem, &remaining);
      check("invalid semaphore clock leaves count unchanged", remaining == (int)value);
    }
  }
}

static void test_pthread_deadline_validation(void) {
  struct pc_api a = pc_load();
  if (!pc_present(&a)) { check("deadline public APIs available", 0); return; }
  const struct timespec invalid[] = {{100, -1}, {100, 1000000000}, {-1, -1}, {-1, 0}};
  for (unsigned i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
    int error = invalid[i].tv_nsec < 0 || invalid[i].tv_nsec >= 1000000000 ? EINVAL : ETIMEDOUT;
    const pc_timed_fn mutexes[] = {a.mutex_timed, a.mutex_mono};
    for (unsigned j = 0; j < 2; ++j) {
      pthread_mutex_t m;
      a.mutex_init(&m, NULL);
      errno = 321;
      int acquired = mutexes[j](&m, &invalid[i]);
      check("free timed mutex acquires before checking deadline", acquired == 0 && errno == 321);
      if (acquired == 0) a.mutex_unlock(&m);
      a.mutex_lock(&m);
      errno = 321;
      check("contended timed mutex validates Android timespec order",
            mutexes[j](&m, &invalid[i]) == error && errno == 321);
      a.mutex_unlock(&m);
    }
    const pc_timed_fn rwlocks[] = {a.rd_timed, a.wr_timed, a.rd_mono, a.wr_mono};
    for (unsigned j = 0; j < 4; ++j) {
      pthread_rwlock_t rw;
      a.rw_init(&rw, NULL);
      int acquired = rwlocks[j](&rw, &invalid[i]);
      check("free timed rwlock acquires before checking deadline", acquired == 0);
      if (acquired == 0) a.rw_unlock(&rw);
    }
    pthread_rwlock_t rw;
    a.rw_init(&rw, NULL);
    a.rw_read(&rw);
    check("reader-held timed writer validates deadline", a.wr_timed(&rw, &invalid[i]) == error);
    a.rw_unlock(&rw);
    pthread_mutex_t m;
    pthread_cond_t c;
    a.mutex_init(&m, NULL);
    a.cond_init(&c, NULL);
    a.mutex_lock(&m);
    check("condition rejects invalid deadline", a.cond_timed(&c, &m, &invalid[i]) == error);
    check("condition validation retains held mutex", a.mutex_try(&m) == EBUSY);
    a.mutex_unlock(&m);
  }
  // ERRORCHECK/RECURSIVE owner semantics take precedence over a bad deadline,
  // but an invalid explicit clock must always be rejected first.
  for (int type = 1; type <= 2; ++type) {
    int64_t attr = 0;
    a.mutex_attr_init(&attr);
    a.mutex_attr_type(&attr, type);
    pthread_mutex_t m;
    a.mutex_init(&m, &attr);
    a.mutex_lock(&m);
    int expected = type == 1 ? 0 : EDEADLK;
    const pc_timed_fn mutexes[] = {a.mutex_timed, a.mutex_mono};
    for (unsigned j = 0; j < 2; ++j) {
      int result = mutexes[j](&m, &invalid[1]);
      check("timed mutex retains recursive/errorcheck owner contract",
            result == expected);
      if (type == 1 && result == 0) a.mutex_unlock(&m);
    }
    for (int clock = 0; clock <= 1; ++clock) {
      int result = a.mutex_clock(&m, clock, &invalid[1]);
      check("clock mutex retains recursive/errorcheck owner contract",
            result == expected);
      if (type == 1 && result == 0) a.mutex_unlock(&m);
    }
    check("invalid clock precedes recursive/errorcheck owner handling",
          a.mutex_clock(&m, -1, &invalid[1]) == EINVAL);
    a.mutex_unlock(&m);
  }
}

struct pc_wrong_owner {
  pc_one_fn unlock;
  pthread_rwlock_t* rw;
  int result;
};
static void* pc_unlock_other(void* raw) {
  struct pc_wrong_owner* work = (struct pc_wrong_owner*)raw;
  work->result = work->unlock(work->rw);
  return NULL;
}
static void test_pthread_writer_ownership(void) {
  struct pc_api a = pc_load();
  if (!pc_present(&a)) { check("writer public APIs available", 0); return; }
  pthread_rwlock_t rw;
  a.rw_init(&rw, NULL);
  a.rw_write(&rw);
  check("owned writer try-read and try-write remain EBUSY",
        a.rw_tryread(&rw) == EBUSY && a.rw_trywrite(&rw) == EBUSY);
  check("destroying a held rwlock is EBUSY", a.rw_destroy(&rw) == EBUSY);
  struct timespec expired = {0, 0};
  const pc_timed_fn timed[] = {a.rd_timed, a.wr_timed, a.rd_mono, a.wr_mono};
  for (unsigned i = 0; i < 4; ++i) {
    check("writer self timed acquisition is EDEADLK", timed[i](&rw, &expired) == EDEADLK);
  }
  for (int clock = 0; clock <= 1; ++clock) {
    check("writer self clock read is EDEADLK", a.rd_clock(&rw, clock, &expired) == EDEADLK);
    check("writer self clock write is EDEADLK", a.wr_clock(&rw, clock, &expired) == EDEADLK);
  }
  struct pc_wrong_owner work = {a.rw_unlock, &rw, -1};
  pthread_t other;
  int created = pthread_create(&other, NULL, pc_unlock_other, &work);
  check("writer ownership: create another thread", created == 0);
  if (created == 0) {
    check("writer ownership: join another thread", pthread_join(other, NULL) == 0);
    check("nonowner cannot unlock writer", work.result == EPERM);
  }
  int acquired = a.rw_tryread(&rw);
  check("rejected nonowner unlock leaves writer held", acquired == EBUSY);
  // Both the rejected (candidate) and accepted (baseline) path are safely
  // cleaned up; no later assertion blocks on a damaged test fixture.
  a.rw_unlock(&rw);
  a.rw_init(&rw, NULL);
  check("recursive read ownership remains supported",
        a.rw_read(&rw) == 0 && a.rw_read(&rw) == 0 && a.rw_trywrite(&rw) == EBUSY);
  check("both reader releases restore writer availability",
        a.rw_unlock(&rw) == 0 && a.rw_unlock(&rw) == 0 && a.rw_trywrite(&rw) == 0);
  a.rw_unlock(&rw);
  check("destroy unlocked rwlock succeeds", a.rw_destroy(&rw) == 0);
}

struct pc_signal_work {
  struct pc_api* api;
  pthread_mutex_t* mutex;
  pthread_cond_t* condition;
};
static void* pc_signal_after_deadline(void* raw) {
  struct pc_signal_work* work = (struct pc_signal_work*)raw;
  const struct timespec pause = {0, 50000000};
  nanosleep(&pause, NULL);
  // Holding the mutex in the caller until cond_wait releases it prevents
  // the control signal from being lost even on a slow translated guest.
  work->api->mutex_lock(work->mutex);
  work->api->cond_signal(work->condition);
  work->api->mutex_unlock(work->mutex);
  return NULL;
}
static void test_pthread_clock_runtime(void) {
  struct pc_api a = pc_load();
  if (!pc_present(&a)) { check("clock runtime APIs available", 0); return; }
  for (int attr = 0; attr <= 1; ++attr) {
    for (int clock = 0; clock <= 1; ++clock) {
      pthread_mutex_t m;
      pthread_cond_t c;
      int64_t attribute = 0;
      a.cond_attr_clock(&attribute, attr);
      a.mutex_init(&m, NULL);
      a.cond_init(&c, &attribute);
      a.mutex_lock(&m);
      struct timespec deadline;
      clock_gettime(clock, &deadline);
      // A valid already-expired absolute deadline makes the result
      // independent of machine load. The delayed signal bounds the old
      // incorrect realtime-as-monotonic wait without relying on a crash.
      if (deadline.tv_sec > 0) --deadline.tv_sec;
      struct pc_signal_work work = {&a, &m, &c};
      pthread_t sender;
      int created = pthread_create(&sender, NULL, pc_signal_after_deadline, &work);
      check("explicit clock runtime: start signal control", created == 0);
      if (created != 0) { a.mutex_unlock(&m); continue; }
      int result = a.cond_clock(&c, &m, clock, &deadline);
      check("explicit clock overrides condvar attribute on actual runtime", result == ETIMEDOUT);
      a.mutex_unlock(&m);
      check("explicit clock runtime: join signal control", pthread_join(sender, NULL) == 0);
    }
  }
}

struct pc_nullable_work {
  struct pc_api* api;
  pthread_mutex_t* mutex;
  int route, entered, completed, result;
};
static void* pc_nullable_waiter(void* raw) {
  struct pc_nullable_work* work = (struct pc_nullable_work*)raw;
  __atomic_store_n(&work->entered, 1, __ATOMIC_RELEASE);
  work->result = work->route == 0 ? work->api->mutex_timed(work->mutex, NULL)
      : work->route == 1 ? work->api->mutex_mono(work->mutex, NULL)
      : work->api->mutex_clock(work->mutex, work->route - 2, NULL);
  __atomic_store_n(&work->completed, 1, __ATOMIC_RELEASE);
  if (work->result == 0) work->api->mutex_unlock(work->mutex);
  return NULL;
}
static void test_pthread_nullable_runtime(void) {
  struct pc_api a = pc_load();
  if (!pc_present(&a)) { check("nullable runtime APIs available", 0); return; }
  for (int route = 0; route < 4; ++route) {
    pthread_mutex_t m;
    a.mutex_init(&m, NULL);
    a.mutex_lock(&m);
    struct pc_nullable_work work = {&a, &m, route, 0, 0, -1};
    pthread_t waiter;
    int created = pthread_create(&waiter, NULL, pc_nullable_waiter, &work);
    check("nullable runtime: create waiter", created == 0);
    if (created != 0) { a.mutex_unlock(&m); continue; }
    const struct timespec pause = {0, 1000000};
    for (int i = 0; i < 2000 && !__atomic_load_n(&work.entered, __ATOMIC_ACQUIRE); ++i) {
      nanosleep(&pause, NULL);
    }
    check("nullable runtime: waiter entered", __atomic_load_n(&work.entered, __ATOMIC_ACQUIRE));
    const struct timespec contention = {0, 10000000};
    nanosleep(&contention, NULL);
    check("nullable runtime: held mutex prevents completion",
          !__atomic_load_n(&work.completed, __ATOMIC_ACQUIRE));
    a.mutex_unlock(&m);
    check("nullable runtime: join waiter", pthread_join(waiter, NULL) == 0);
    check("nullable runtime: acquire after release", work.result == 0);
  }
}

static void test_pthread_clock_contracts(void) {
  test_pthread_clock_public();
  test_pthread_deadline_validation();
  test_pthread_writer_ownership();
  test_pthread_clock_runtime();
  test_pthread_nullable_runtime();
}
