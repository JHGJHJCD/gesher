/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
/* Public NDK fixture for real gesher-exec on Windows, native or translated ELF.
 * Build with the pinned NDK's API 29 headers and -laaudio -ldl. Use GESHER_AUDIO=null for a silent,
 * paced backend. Input device/permission coverage belongs to recordapp/media3.
 */
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static int checks, failures;
static void check(int ok, const char* label) {
    ++checks;
    if (!ok) { ++failures; fprintf(stderr, "FAIL AAudio %s\n", label); }
}
typedef void (*setter)(AAudioStreamBuilder*, int32_t);
static void rejected(setter set, int32_t value, aaudio_result_t expected, const char* label) {
    AAudioStreamBuilder* builder = NULL;
    check(AAudio_createStreamBuilder(&builder) == AAUDIO_OK && builder != NULL, "builder creation");
    if (builder == NULL) return;
    set(builder, value);
    AAudioStream* stream = (AAudioStream*)(uintptr_t)1;
    aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
    check(result == expected, label);
    check(stream == NULL, "failed open clears output");
    if (result == AAUDIO_OK && stream != NULL && stream != (AAudioStream*)(uintptr_t)1) AAudioStream_close(stream);
    AAudioStreamBuilder_delete(builder);
}
static atomic_int callbacks, received_frames;
static aaudio_data_callback_result_t callback(AAudioStream* stream, void* user, void* data, int32_t frames) {
    (void)user;
    memset(data, 0, (size_t)frames * (size_t)AAudioStream_getChannelCount(stream) * sizeof(float));
    atomic_store(&received_frames, frames);
    atomic_fetch_add(&callbacks, 1);
    return AAUDIO_CALLBACK_RESULT_STOP;
}
static void pause_briefly(void) { struct timespec delay = {0, 10000000}; nanosleep(&delay, NULL); }

/* Exact Android 16r4 declarations: release (API30), masks (API32), device
 * array (API36). Typed dlsym keeps the API29 build while requiring the real
 * runtime symbols and their guest calling convention in both CI ABIs. */
typedef void (*set_mask_fn)(AAudioStreamBuilder*, uint32_t);
typedef uint32_t (*get_mask_fn)(AAudioStream*);
typedef aaudio_result_t (*release_fn)(AAudioStream*);
typedef aaudio_result_t (*get_ids_fn)(AAudioStream*, int32_t*, int32_t*);
static release_fn release_stream;
static atomic_int lifecycle_callbacks, lifecycle_callback_errors;
static aaudio_data_callback_result_t lifecycle_callback(AAudioStream* stream, void* user,
                                                        void* data, int32_t frames) {
    (void)user;
    memset(data, 0, (size_t)frames * (size_t)AAudioStream_getChannelCount(stream) * sizeof(float));
    if (AAudioStream_requestStart(stream) != AAUDIO_ERROR_INVALID_STATE ||
        AAudioStream_requestStop(stream) != AAUDIO_ERROR_INVALID_STATE ||
        AAudioStream_requestPause(stream) != AAUDIO_ERROR_INVALID_STATE ||
        AAudioStream_requestFlush(stream) != AAUDIO_ERROR_INVALID_STATE ||
        release_stream(stream) != AAUDIO_ERROR_INVALID_STATE ||
        AAudioStream_close(stream) != AAUDIO_ERROR_INVALID_STATE) {
        atomic_fetch_add(&lifecycle_callback_errors, 1);
    }
    atomic_fetch_add(&lifecycle_callbacks, 1);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
static void lifecycle_contracts(void) {
    const int before_failures = failures;
    void* library = dlopen("libaaudio.so", RTLD_NOW);
    check(library != NULL, "AAudio namespace opens for new API queries");
    if (library == NULL) return;
    set_mask_fn set_mask = (set_mask_fn)dlsym(library, "AAudioStreamBuilder_setChannelMask");
    get_mask_fn get_mask = (get_mask_fn)dlsym(library, "AAudioStream_getChannelMask");
    get_ids_fn get_ids = (get_ids_fn)dlsym(library, "AAudioStream_getDeviceIds");
    release_stream = (release_fn)dlsym(library, "AAudioStream_release");
    check(set_mask != NULL && get_mask != NULL && get_ids != NULL && release_stream != NULL,
          "release, mask and API36 device-array exports exist");
    if (set_mask == NULL || get_mask == NULL || get_ids == NULL || release_stream == NULL) {
        dlclose(library);
        return;
    }
    AAudioStreamBuilder* builder = NULL;
    check(AAudio_createStreamBuilder(&builder) == AAUDIO_OK && builder != NULL,
          "lifecycle/query builder");
    if (builder == NULL) { dlclose(library); return; }
    for (int selection = 0; selection < 4; ++selection) {
        AAudioStreamBuilder_setChannelCount(builder, 0);
        uint32_t expected_mask = 3; /* AAUDIO_CHANNEL_STEREO */
        int expected_channels = 2;
        if (selection == 1) {
            set_mask(builder, 1); /* AAUDIO_CHANNEL_MONO */
            expected_mask = 1; expected_channels = 1;
        } else if (selection == 2) {
            set_mask(builder, 3);
            AAudioStreamBuilder_setChannelCount(builder, 1);
            expected_mask = 0; expected_channels = 1; /* index layouts stay unspecified */
        } else if (selection == 3) {
            AAudioStreamBuilder_setChannelCount(builder, 1);
            set_mask(builder, 3);
        }
        AAudioStream* stream = NULL;
        check(AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK && stream != NULL,
              "lifecycle/query output opens");
        if (stream == NULL) continue;
        check(get_mask(stream) == expected_mask && AAudioStream_getChannelCount(stream) == expected_channels,
              "positional, indexed and negotiated masks retain setter order");
        int32_t ids[2] = {71, 72}, count = 0;
        check(get_ids(stream, ids, NULL) == AAUDIO_ERROR_ILLEGAL_ARGUMENT && ids[0] == 71,
              "device array null size leaves array unchanged");
        check(get_ids(stream, NULL, &count) == AAUDIO_ERROR_OUT_OF_RANGE && count == 1,
              "device array sizing precedes null array");
        count = 2;
        check(get_ids(stream, NULL, &count) == AAUDIO_ERROR_ILLEGAL_ARGUMENT && count == 2,
              "sufficient-capacity null array preserves size");
        check(get_ids(stream, ids, &count) == AAUDIO_OK && count == 1 &&
              ids[0] == AAudioStream_getDeviceId(stream) && ids[1] == 72,
              "device array agrees with scalar identity and preserves spare entries");
        check(AAudioStream_requestStart(stream) == AAUDIO_OK, "lifecycle output starts");
        check(AAudioStream_requestStart(stream) == AAUDIO_ERROR_INVALID_STATE,
              "duplicate start is rejected");
        check(AAudioStream_requestPause(stream) == AAUDIO_OK, "lifecycle output pauses");
        check(AAudioStream_requestFlush(stream) == AAUDIO_OK && AAudioStream_requestFlush(stream) == AAUDIO_OK,
              "repeated flush is allowed");
        check(AAudioStream_requestStart(stream) == AAUDIO_OK, "flushed stream starts");
        check(release_stream(stream) == AAUDIO_OK && AAudioStream_getState(stream) == AAUDIO_STREAM_STATE_CLOSING,
              "release publishes terminal closing state");
        check(AAudioStream_requestStart(stream) == AAUDIO_ERROR_INVALID_STATE &&
              AAudioStream_requestStop(stream) == AAUDIO_ERROR_INVALID_STATE &&
              AAudioStream_requestPause(stream) == AAUDIO_ERROR_INVALID_STATE &&
              AAudioStream_requestFlush(stream) == AAUDIO_ERROR_INVALID_STATE &&
              AAudioStream_getState(stream) == AAUDIO_STREAM_STATE_CLOSING,
              "lifecycle operations cannot revive released stream");
        count = 1;
        check(get_mask(stream) == expected_mask && AAudioStream_getSampleRate(stream) > 0 &&
              get_ids(stream, ids, &count) == AAUDIO_OK && ids[0] == AAudioStream_getDeviceId(stream),
              "queries remain valid until close");
        check(release_stream(stream) == AAUDIO_OK && AAudioStream_close(stream) == AAUDIO_OK,
              "repeated release followed by close");
    }
    set_mask(builder, 0x33); /* QUAD: the count-only mixer would confuse rear with center/LFE. */
    AAudioStream* stream = NULL;
    check(AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_ERROR_UNAVAILABLE && stream == NULL,
          "unmapped quad positional layout is explicitly unavailable");
    if (stream != NULL) AAudioStream_close(stream);

    set_mask(builder, 3);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setDataCallback(builder, lifecycle_callback, NULL);
    stream = NULL;
    check(AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK && stream != NULL,
          "lifecycle callback stream opens");
    AAudioStreamBuilder_delete(builder);
    if (stream != NULL) {
        check(AAudioStream_requestStart(stream) == AAUDIO_OK, "lifecycle callback starts");
        for (int i = 0; i < 200 && atomic_load(&lifecycle_callbacks) < 2; ++i) pause_briefly();
        check(atomic_load(&lifecycle_callbacks) >= 2, "real guest data callbacks run");
        check(release_stream(stream) == AAUDIO_OK, "release quiesces callback worker");
        const int callbacks_after_release = atomic_load(&lifecycle_callbacks);
        pause_briefly();
        check(atomic_load(&lifecycle_callback_errors) == 0 &&
              atomic_load(&lifecycle_callbacks) == callbacks_after_release,
              "callback-local lifecycle guards and no callbacks after release");
        check(AAudioStream_getState(stream) == AAUDIO_STREAM_STATE_CLOSING &&
              AAudioStream_requestStart(stream) == AAUDIO_ERROR_INVALID_STATE,
              "released callback stream stays closing");
        check(AAudioStream_close(stream) == AAUDIO_OK, "callback stream closes after release");
    }
    dlclose(library);
    if (failures == before_failures) puts("AAudio lifecycle guest contracts: all passed");
}
int main(void) {
    rejected(AAudioStreamBuilder_setDirection, 2, AAUDIO_ERROR_ILLEGAL_ARGUMENT, "invalid direction");
    rejected(AAudioStreamBuilder_setChannelCount, -1, AAUDIO_ERROR_OUT_OF_RANGE, "negative channels");
    rejected(AAudioStreamBuilder_setChannelCount, 13, AAUDIO_ERROR_OUT_OF_RANGE, "Android channel limit");
    rejected(AAudioStreamBuilder_setSampleRate, -1, AAUDIO_ERROR_INVALID_RATE, "negative rate");
    rejected(AAudioStreamBuilder_setSampleRate, 7999, AAUDIO_ERROR_INVALID_RATE, "Android minimum rate");
    rejected(AAudioStreamBuilder_setBufferCapacityInFrames, -1, AAUDIO_ERROR_OUT_OF_RANGE, "negative capacity");
    rejected(AAudioStreamBuilder_setBufferCapacityInFrames, INT_MAX, AAUDIO_ERROR_NO_MEMORY, "bounded ring allocation");
    rejected(AAudioStreamBuilder_setFramesPerDataCallback, -1, AAUDIO_ERROR_OUT_OF_RANGE, "negative callback size");
    rejected(AAudioStreamBuilder_setFramesPerDataCallback, INT_MAX, AAUDIO_ERROR_OUT_OF_RANGE, "callback maximum");
    rejected(AAudioStreamBuilder_setSharingMode, 2, AAUDIO_ERROR_ILLEGAL_ARGUMENT, "sharing enum");
    rejected(AAudioStreamBuilder_setPerformanceMode, 9, AAUDIO_ERROR_ILLEGAL_ARGUMENT, "performance enum");
    rejected(AAudioStreamBuilder_setPerformanceMode, 13, AAUDIO_ERROR_UNAVAILABLE, "offloaded mode unavailable");
    rejected(AAudioStreamBuilder_setFormat, 999, AAUDIO_ERROR_INVALID_FORMAT, "format enum");
    rejected(AAudioStreamBuilder_setDeviceId, -1, AAUDIO_ERROR_OUT_OF_RANGE, "device id range");
    rejected(AAudioStreamBuilder_setUsage, 7, AAUDIO_ERROR_ILLEGAL_ARGUMENT, "usage enum");
    rejected(AAudioStreamBuilder_setContentType, 5, AAUDIO_ERROR_ILLEGAL_ARGUMENT, "content enum");
    rejected(AAudioStreamBuilder_setInputPreset, 2, AAUDIO_ERROR_ILLEGAL_ARGUMENT, "preset enum");

    AAudioStreamBuilder* builder = NULL;
    AAudioStream* stream = NULL;
    check(AAudio_createStreamBuilder(&builder) == 0, "output builder");
    if (builder == NULL) return 1;
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
    check(result == AAUDIO_OK && stream != NULL, "shared output negotiated");
    if (stream == NULL) { AAudioStreamBuilder_delete(builder); return 1; }
    check(AAudioStream_getPerformanceMode(stream) == AAUDIO_PERFORMANCE_MODE_NONE, "actual performance mode");
    check(AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_SHARED, "actual sharing mode");
    check(AAudioStream_getSampleRate(stream) == 48000 && AAudioStream_getChannelCount(stream) == 2, "actual PCM rate/channels");
    int32_t burst = AAudioStream_getFramesPerBurst(stream);
    check(burst > 0 && AAudioStream_getBufferCapacityInFrames(stream) >= burst, "actual burst and capacity");
    int32_t size = AAudioStream_getBufferSizeInFrames(stream);
    check(AAudioStream_setBufferSizeInFrames(stream, -1) == AAUDIO_ERROR_OUT_OF_RANGE &&
          AAudioStream_getBufferSizeInFrames(stream) == size, "negative resize preserves buffer");
    int64_t frame = 91, timestamp = 92;
    check(AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &frame, &timestamp) == AAUDIO_ERROR_INVALID_STATE &&
          frame == 91 && timestamp == 92, "stopped timestamp leaves outputs");
    check(AAudioStream_getTimestamp(stream, CLOCK_REALTIME, &frame, &timestamp) == AAUDIO_ERROR_ILLEGAL_ARGUMENT &&
          frame == 91 && timestamp == 92, "realtime clock rejected without outputs");
    check(AAudioStream_getTimestamp(stream, CLOCK_BOOTTIME, &frame, &timestamp) == AAUDIO_ERROR_UNIMPLEMENTED &&
          frame == 91 && timestamp == 92, "unqualified suspend clock explicitly unsupported");
    check(AAudioStream_requestStart(stream) == 0, "output starts");
    float* samples = calloc((size_t)burst * 2, sizeof(float));
    if (samples == NULL) { AAudioStream_close(stream); AAudioStreamBuilder_delete(builder); return 1; }
    check(AAudioStream_write(stream, samples, burst, 1000000000) == burst, "actual silent PCM written");
    free(samples);
    for (int i = 0; i < 100; ++i) {
        result = AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &frame, &timestamp);
        if (result == AAUDIO_OK) break;
        pause_briefly();
    }
    check(result == AAUDIO_OK && frame >= 0 && timestamp > 0, "monotonic device timestamp available");
    AAudioStream_close(stream);

    AAudioStreamBuilder_setFramesPerDataCallback(builder, 123);
    AAudioStreamBuilder_setDataCallback(builder, callback, NULL);
    stream = NULL;
    check(AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK && stream != NULL, "callback output opens");
    AAudioStreamBuilder_delete(builder);
    if (stream != NULL) {
        check(AAudioStream_getFramesPerBurst(stream) == burst && AAudioStream_getFramesPerDataCallback(stream) == 123,
              "callback frame count distinct from device burst");
        check(AAudioStream_requestStart(stream) == AAUDIO_OK, "callback output starts");
        for (int i = 0; i < 100 && atomic_load(&callbacks) == 0; ++i) pause_briefly();
        check(atomic_load(&callbacks) == 1 && atomic_load(&received_frames) == 123, "actual callback frame count");
        AAudioStream_close(stream);
    }
    lifecycle_contracts();
    printf("AAudio builder guest contracts: %d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
