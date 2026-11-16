// Public LP64 contracts shared by native Linux and Android ELF fixtures.
// Every real wait is at most two seconds; extreme budgets use the controlled
// host envelope, never a days-long wait in a runtime test.
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifndef SLEEP_NANOSLEEP
#define SLEEP_NANOSLEEP nanosleep
#define SLEEP_USLEEP usleep
#define SLEEP_SECONDS sleep
#define SLEEP_CLOCK_GETRES clock_getres
#endif

static int (*volatile sleep_nanosleep_call)(const struct timespec*, struct timespec*) = SLEEP_NANOSLEEP;
static int (*volatile sleep_getres_call)(clockid_t, struct timespec*) = SLEEP_CLOCK_GETRES;

static int64_t sleep_elapsed_ns(const struct timespec* a, const struct timespec* b) {
  return (int64_t)(b->tv_sec - a->tv_sec) * 1000000000 + b->tv_nsec - a->tv_nsec;
}

static void test_sleep_inputs(void) {
  const struct timespec invalid[] = {{-1, 0}, {0, -1}, {0, 1000000000}};
  for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
    struct timespec remain = {111, 222};
    errno = EDOM;
    check("nanosleep rejects invalid timespec without changing remainder",
          sleep_nanosleep_call(&invalid[i], &remain) == -1 && errno == EINVAL &&
          remain.tv_sec == 111 && remain.tv_nsec == 222);
  }
  struct timespec remain = {111, 222};
  errno = EDOM;
  check("nanosleep null request reports EFAULT without writing remainder",
        sleep_nanosleep_call(NULL, &remain) == -1 && errno == EFAULT &&
        remain.tv_sec == 111 && remain.tv_nsec == 222);
  const struct timespec zero = {0, 0};
  errno = EDOM;
  check("zero nanosleep preserves remainder and errno",
        sleep_nanosleep_call(&zero, &remain) == 0 && errno == EDOM &&
        remain.tv_sec == 111 && remain.tv_nsec == 222);
  errno = EDOM;
  check("zero usleep and sleep succeed", SLEEP_USLEEP(0) == 0 && SLEEP_SECONDS(0) == 0);
  const int invalid_clocks[] = {999, -1};
  for (size_t i = 0; i < sizeof(invalid_clocks) / sizeof(invalid_clocks[0]); ++i) {
    struct timespec resolution = {111, 222};
    errno = EDOM;
    check("clock_getres rejects unsupported clocks without changing output",
          sleep_getres_call(invalid_clocks[i], &resolution) == -1 && errno == EINVAL &&
          resolution.tv_sec == 111 && resolution.tv_nsec == 222);
    errno = EDOM;
    check("clock_getres validates clock with null output",
          sleep_getres_call(invalid_clocks[i], NULL) == -1 && errno == EINVAL);
  }
  check("clock_getres accepts optional resolution for valid clock",
        sleep_getres_call(CLOCK_MONOTONIC, NULL) == 0);
}

static void test_sleep_durations(void) {
  struct timespec start, finish, request = {0, 5000000}, remain = {111, 222};
  int before = clock_gettime(CLOCK_MONOTONIC, &start);
  errno = EDOM;
  int result = sleep_nanosleep_call(&request, &remain);
  int error = errno;
  int after = clock_gettime(CLOCK_MONOTONIC, &finish);
  check("nanosleep waits at least the requested interval",
        before == 0 && after == 0 && result == 0 && sleep_elapsed_ns(&start, &finish) >= 5000000);
  check("completed nanosleep leaves remainder and errno untouched",
        error == EDOM && remain.tv_sec == 111 && remain.tv_nsec == 222);
  before = clock_gettime(CLOCK_MONOTONIC, &start);
  result = SLEEP_USLEEP(5000);
  after = clock_gettime(CLOCK_MONOTONIC, &finish);
  check("usleep waits at least the requested interval",
        before == 0 && after == 0 && result == 0 && sleep_elapsed_ns(&start, &finish) >= 5000000);
}

#ifndef SLEEP_SKIP_PUBLIC_SIGNALS
static volatile sig_atomic_t sleep_signal_seen;
static void sleep_contract_handler(int signal_number) {
  if (signal_number == SIGUSR1) sleep_signal_seen = 1;
}
struct SleepContractSender { pthread_t target; int stop; };
static void* sleep_contract_sender(void* value) {
  struct SleepContractSender* sender = (struct SleepContractSender*)value;
  for (int i = 0; i < 20; ++i) {
    usleep(10000);
    if (__atomic_load_n(&sender->stop, __ATOMIC_ACQUIRE)) break;
    pthread_kill(sender->target, SIGUSR1);
  }
  return NULL;
}
static void test_zero_sleep_signals(void) {
  // A pending pthread_kill must reach a thread that repeatedly polls a zero
  // sleep. Capture delivery before join, which can itself deliver signals.
  for (int kind = 0; kind < 4; ++kind) {
    struct SleepContractSender sender = {pthread_self(), 0};
    pthread_t thread;
    sleep_signal_seen = 0;
    int created = pthread_create(&thread, NULL, sleep_contract_sender, &sender);
    check("zero-sleep signal fixture starts sender", created == 0);
    if (created != 0) continue;
    const struct timespec zero = {0, 0};
    struct timespec start, now;
    int clock_ok = clock_gettime(CLOCK_MONOTONIC, &start) == 0;
    int call_ok = 1;
    while (clock_ok && !sleep_signal_seen) {
      int result;
      if (kind == 0) result = sleep_nanosleep_call(&zero, NULL);
      else if (kind == 1) result = SLEEP_USLEEP(0);
      else if (kind == 2) result = (int)SLEEP_SECONDS(0);
      else result = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &zero, NULL);
      if (result != 0 && !(kind < 2 && result == -1 && errno == EINTR) &&
          !(kind == 3 && result == EINTR)) call_ok = 0;
      clock_ok = clock_gettime(CLOCK_MONOTONIC, &now) == 0;
      if (!clock_ok || sleep_elapsed_ns(&start, &now) >= 2000000000) break;
    }
    const int seen_before_join = sleep_signal_seen;
    __atomic_store_n(&sender.stop, 1, __ATOMIC_RELEASE);
    int joined = pthread_join(thread, NULL);
    const char* labels[] = {"zero nanosleep delivers signals while polling",
                           "zero usleep delivers signals while polling",
                           "zero sleep delivers signals while polling",
                           "expired clock_nanosleep delivers signals while polling"};
    check(labels[kind],
          joined == 0 && clock_ok && call_ok && seen_before_join);
  }
}
static void test_sleep_signals(void) {
  struct sigaction action, previous;
  memset(&action, 0, sizeof(action));
  action.sa_handler = sleep_contract_handler;
  sigemptyset(&action.sa_mask);
  int installed = sigaction(SIGUSR1, &action, &previous);
  check("sleep signal fixture installs handler", installed == 0);
  if (installed != 0) return;
  sigset_t selected, old_mask;
  sigemptyset(&selected);
  sigaddset(&selected, SIGUSR1);
  int unblocked = pthread_sigmask(SIG_UNBLOCK, &selected, &old_mask);
  check("sleep signal fixture unblocks signal", unblocked == 0);
  if (unblocked != 0) { sigaction(SIGUSR1, &previous, NULL); return; }
  for (int alias = 0; alias < 2; ++alias) {
    struct SleepContractSender sender = {pthread_self(), 0};
    struct timespec request = {2, 0}, remain = {111, 222};
    struct timespec* output = alias ? &request : &remain;
    pthread_t thread;
    sleep_signal_seen = 0;
    int created = pthread_create(&thread, NULL, sleep_contract_sender, &sender);
    check("sleep signal fixture starts sender", created == 0);
    if (created != 0) continue;
    errno = EDOM;
    int result = sleep_nanosleep_call(&request, output);
    int error = errno;
    __atomic_store_n(&sender.stop, 1, __ATOMIC_RELEASE);
    int joined = pthread_join(thread, NULL);
    check("nanosleep signal interruption reports EINTR",
          joined == 0 && sleep_signal_seen && result == -1 && error == EINTR);
    check("nanosleep interruption returns normalized remainder including aliased output",
          output->tv_sec >= 0 && output->tv_sec < 2 &&
          output->tv_nsec >= 0 && output->tv_nsec < 1000000000);
  }
  test_zero_sleep_signals();
  pthread_sigmask(SIG_SETMASK, &old_mask, NULL);
  sigaction(SIGUSR1, &previous, NULL);
}
#endif

static void test_sleep_contracts(void) {
  test_sleep_inputs();
  test_sleep_durations();
#ifndef SLEEP_SKIP_PUBLIC_SIGNALS
  test_sleep_signals();
#endif
  printf("sleep contracts: complete\n");
}
