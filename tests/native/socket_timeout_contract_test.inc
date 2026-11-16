// Shared guest/host contracts for the LP64 socket-timeout boundary. The explicit
// layout also lets the host harness model Windows' narrower intermediate ABI.
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/socket.h>
#include <unistd.h>

struct socket_timeout_value { int64_t seconds, microseconds; };
typedef int (*socket_timeout_set_fn)(int, int, int, const void*, uint32_t);
typedef int (*socket_timeout_get_fn)(int, int, int, void*, uint32_t*);

static int socket_timeout_read(socket_timeout_get_fn get, int fd, int option,
                               struct socket_timeout_value* result) {
  uint32_t length = sizeof(*result);
  return get(fd, SOL_SOCKET, option, result, &length) == 0 && length == sizeof(*result);
}

static void test_socket_timeout_contracts(void) {
  socket_timeout_set_fn set = (socket_timeout_set_fn)dlsym(RTLD_DEFAULT, "setsockopt");
  socket_timeout_get_fn get = (socket_timeout_get_fn)dlsym(RTLD_DEFAULT, "getsockopt");
  check("socket timeout: public exports", set != NULL && get != NULL);
  if (set == NULL || get == NULL) return;
  int fd = socket(AF_INET, SOCK_STREAM, 0);
  check("socket timeout: opens local socket", fd >= 0);
  if (fd < 0) return;

  const int options[] = {SO_RCVTIMEO, SO_SNDTIMEO};
  const int64_t invalid_usec[] = {
    -1, 1000000, INT64_C(1) << 32, (INT64_C(1) << 32) + 1,
    -(INT64_C(1) << 32), INT64_MIN, INT64_MAX
  };
  for (unsigned option_index = 0; option_index < sizeof(options) / sizeof(options[0]); ++option_index) {
    int option = options[option_index];
    struct socket_timeout_value seed = {0, 100000};
    for (unsigned i = 0; i < sizeof(invalid_usec) / sizeof(invalid_usec[0]); ++i) {
      struct socket_timeout_value before = {0, 0}, after = {0, 0};
      check("socket timeout: seed a finite timeout", set(fd, SOL_SOCKET, option, &seed, sizeof(seed)) == 0 &&
            socket_timeout_read(get, fd, option, &before) && before.seconds == 0 &&
            before.microseconds >= seed.microseconds && before.microseconds < 200000);
      struct socket_timeout_value invalid = {0, invalid_usec[i]};
      errno = 0;
      int result = set(fd, SOL_SOCKET, option, &invalid, sizeof(invalid));
      check("socket timeout: rejects full-width invalid microseconds", result == -1 && errno == EDOM);
      check("socket timeout: rejection preserves previous timeout", socket_timeout_read(get, fd, option, &after) &&
            before.seconds == after.seconds && before.microseconds == after.microseconds);
      errno = 0;
      result = set(-1, SOL_SOCKET, option, &invalid, sizeof(invalid));
      check("socket timeout: invalid descriptor retains EBADF precedence", result == -1 && errno == EBADF);
    }
    struct socket_timeout_value one = {0, 1}, out = {0, 0};
    check("socket timeout: one microsecond stays finite after rounding", set(fd, SOL_SOCKET, option, &one, sizeof(one)) == 0 &&
          socket_timeout_read(get, fd, option, &out) && out.seconds == 0 &&
          out.microseconds > 0 && out.microseconds < 100000);
    struct socket_timeout_value large = {INT64_C(1) << 32, 0};
    check("socket timeout: large positive seconds do not wrap to zero", set(fd, SOL_SOCKET, option, &large, sizeof(large)) == 0 &&
          socket_timeout_read(get, fd, option, &out) && out.seconds > 0);
    struct socket_timeout_value zero = {0, 0};
    check("socket timeout: zero explicitly disables the timeout", set(fd, SOL_SOCKET, option, &zero, sizeof(zero)) == 0 &&
          socket_timeout_read(get, fd, option, &out) && out.seconds == 0 && out.microseconds == 0);
  }
  int ordinary_fd = open("/dev/null", O_RDONLY);
  check("socket timeout: opens nonsocket control", ordinary_fd >= 0);
  if (ordinary_fd >= 0) {
    struct socket_timeout_value invalid = {0, INT64_C(1) << 32};
    errno = 0;
    int result = set(ordinary_fd, SOL_SOCKET, SO_RCVTIMEO, &invalid, sizeof(invalid));
    check("socket timeout: nonsocket retains ENOTSOCK precedence", result == -1 && errno == ENOTSOCK);
    close(ordinary_fd);
  }
  int enabled = 1, observed = 0;
  uint32_t length = sizeof(observed);
  check("socket timeout: ordinary integer socket options still pass through",
        set(fd, SOL_SOCKET, SO_KEEPALIVE, &enabled, sizeof(enabled)) == 0 &&
        get(fd, SOL_SOCKET, SO_KEEPALIVE, &observed, &length) == 0 && observed == 1);
  close(fd);
}
