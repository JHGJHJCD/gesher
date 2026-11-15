/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
/* Public NDK fixture for real gesher-exec on Windows, native or translated ELF.
 * Build with the pinned NDK's API 29 headers and -laaudio. Use GESHER_AUDIO=null for a silent,
 * paced backend. Input device/permission coverage belongs to recordapp/media3.
 */
#include <aaudio/AAudio.h>
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
    printf("AAudio builder guest contracts: %d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
