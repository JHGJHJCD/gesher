// Gesher: libmedia_jni's classes other than MediaPlayer (docs/media.md):
// MediaMetadataRetriever over the extractors (the container's metadata and
// the tracks'), MediaExtractor (media_extractor_jni.cc), and stand-ins for the codec, muxer, recorder and DRM classes
// until their stages (roadmap phase 8).

#include <jni.h>

#include <algorithm>
#include <map>
#include <limits>
#include <memory>
#include <mutex>
#include <string>

#include "../audio/log.h"
#include "../player/data_source.h"
#include "../player/decoder.h"
#include "../player/extractor.h"
#include "../video/video_decoder.h"
#include "jni_util.h"
#include "java_data_source.h"
#include "receiver_lease.h"
#include "media_jni.h"

namespace gesher::media {
namespace {

// --- MediaMetadataRetriever ----------------------------------------------------------------

jfieldID gRetrieverContext;  // long mNativeContext
std::mutex gRetrieverContextLock;

struct RetrieverSnapshot {
  std::shared_ptr<DataSource> source;  // for frames (getFrameAtTime)
  std::map<int, std::string> metadata;
  std::vector<uint8_t> album_art;
};

struct Retriever : ReceiverState {
  RetrieverSnapshot snapshot;
  bool opening = false;
};

ReceiverLease<Retriever> GetRetriever(JNIEnv* env, jobject thiz) {
  return ReceiverLease<Retriever>::Acquire(env, thiz, gRetrieverContext, gRetrieverContextLock);
}

// MediaMetadataRetriever's keys, from the container's AMediaFormat keys.
const struct {
  int key;
  const char* name;
} kStringKeys[] = {
    {0, "cdtracknum"}, {1, "album"},    {2, "artist"},      {3, "author"},  {4, "composer"},
    {5, "date"},       {6, "genre"},    {7, "title"},       {8, "year"},    {11, "lyricist"},
    {13, "albumartist"}, {14, "discnum"}, {15, "compilation"}, {23, "location"},
};

bool Fill(RetrieverSnapshot* r, std::shared_ptr<DataSource> source, std::string* error) {
  int64_t size = source->Size();
  r->source = source;
  auto ex = Extractor::Open(source, error);
  if (ex == nullptr) return false;
  r->metadata.clear();
  Format file = ex->FileFormat();
  for (const auto& k : kStringKeys) {
    std::string v;
    if (file.GetString(k.name, &v) && !v.empty()) r->metadata[k.key] = v;
  }
  std::string mime = file.Mime();
  if (!mime.empty()) r->metadata[12] = mime;
  file.GetBuffer("albumart", &r->album_art);
  int64_t duration = -1;
  file.GetInt64("durationUs", &duration);
  bool audio = false, video = false;
  size_t tracks = ex->TrackCount();
  for (size_t i = 0; i < tracks; i++) {
    Format f = ex->TrackFormat(i);
    std::string m = f.Mime();
    int64_t d = -1;
    if (f.GetInt64("durationUs", &d) && d > duration) duration = d;
    if (m.rfind("audio/", 0) == 0 && !audio) {
      audio = true;
      int32_t v;
      if (f.GetInt32("sample-rate", &v)) r->metadata[38] = std::to_string(v);
      if (f.GetInt32("bits-per-sample", &v)) r->metadata[39] = std::to_string(v);
      if (mime.empty()) r->metadata[12] = m;
    } else if (m.rfind("video/", 0) == 0 && !video) {
      video = true;
      int32_t w, h, rot;
      if (f.GetInt32("width", &w)) r->metadata[18] = std::to_string(w);
      if (f.GetInt32("height", &h)) r->metadata[19] = std::to_string(h);
      r->metadata[24] = std::to_string(f.GetInt32("rotation-degrees", &rot) ? rot : 0);
      r->metadata[40] = m;
    }
  }
  r->metadata[10] = std::to_string(tracks);
  if (audio) r->metadata[16] = "yes";
  if (video) r->metadata[17] = "yes";
  if (duration >= 0) {
    r->metadata[9] = std::to_string(duration / 1000);
    int32_t bitrate;
    if (file.GetInt32("bitrate", &bitrate)) {
      r->metadata[20] = std::to_string(bitrate);
    } else if (size > 0 && duration > 0) {
      // Stagefright uses a floating-point average. Avoid an overflowing
      // integer numerator while retaining large sources with a valid result.
      const double average = static_cast<double>(size) * 8E6 / static_cast<double>(duration);
      if (average < 0x1p63) r->metadata[20] = std::to_string(static_cast<int64_t>(average));
    }
  }
  return true;
}

void SetFromSource(JNIEnv* env, jobject thiz, std::unique_ptr<DataSource> source,
                   const std::string& error) {
  auto r = GetRetriever(env, thiz);
  if (r == nullptr) {
    jniThrow(env, "java/lang/IllegalStateException", "No retriever available");
    return;
  }
  if (r->opening) {
    jniThrow(env, "java/lang/IllegalStateException", "setDataSource is in progress");
    return;
  }
  std::string e = error;
  RetrieverSnapshot next;
  r->opening = true;
  const bool opened = source != nullptr && Fill(&next, std::move(source), &e);
  r->opening = false;
  if (!opened) {
    // MetadataRetrieverClient creates a fresh Stagefright retriever and only
    // publishes it on success. The public receiver retains its old snapshot
    // when opening a replacement fails, including its callback ownership.
    MEDIA_LOGE("MediaMetadataRetriever: %s", e.c_str());
    jniThrow(env, "java/lang/RuntimeException", "setDataSource failed");
    return;
  }
  // Retire the prior source only after all new metadata/artwork is published,
  // while the receiver lease still rejects nested operations from close().
  RetrieverSnapshot retired = std::move(r->snapshot);
  r->snapshot = std::move(next);
}

void JNICALL MMR_setDataSourcePath(JNIEnv* env, jobject thiz, jobject, jstring uri, jobjectArray keys,
                                   jobjectArray values) {
  if (uri == nullptr) {
    jniThrow(env, "java/lang/IllegalArgumentException", "Null pointer");
    return;
  }
  std::string p = Utf8(env, uri);
  std::map<std::string, std::string> headers;
  if (keys != nullptr && values != nullptr) {
    jsize n = std::min(env->GetArrayLength(keys), env->GetArrayLength(values));
    for (jsize i = 0; i < n; i++) {
      headers[Utf8(env, static_cast<jstring>(env->GetObjectArrayElement(keys, i)))] =
          Utf8(env, static_cast<jstring>(env->GetObjectArrayElement(values, i)));
    }
  }
  std::string error;
  std::unique_ptr<DataSource> source;
  if (p.rfind("http://", 0) == 0 || p.rfind("https://", 0) == 0) {
    source = OpenHttpSource(p, headers, &error);
  } else {
    source = OpenFileSource(p, &error);
  }
  SetFromSource(env, thiz, std::move(source), error);
}

void JNICALL MMR_setDataSourceFD(JNIEnv* env, jobject thiz, jobject fileDescriptor, jlong offset,
                                 jlong length) {
  if (GetRetriever(env, thiz) == nullptr) {
    jniThrow(env, "java/lang/IllegalStateException", "No retriever available");
    return;
  }
  if (fileDescriptor == nullptr || offset < 0 || length < 0) {
    jniThrow(env, "java/lang/IllegalArgumentException", nullptr);
    return;
  }
  jclass fdc = env->FindClass("java/io/FileDescriptor");
  jint fd = env->GetIntField(fileDescriptor, env->GetFieldID(fdc, "descriptor", "I"));
  if (fd < 0) {
    jniThrow(env, "java/lang/IllegalArgumentException", nullptr);
    return;
  }
  std::string error;
  auto source = OpenFdSource(fd, offset, length >= 0x7fffffffffffffLL ? -1 : length, &error);
  SetFromSource(env, thiz, std::move(source), error);
}

void JNICALL MMR_setDataSourceCallback(JNIEnv* env, jobject thiz, jobject data_source) {
  if (GetRetriever(env, thiz) == nullptr) {
    jniThrow(env, "java/lang/IllegalStateException", "No retriever available");
    return;
  }
  auto source = JavaDataSource::Create(env, data_source);
  if (source == nullptr) return;
  SetFromSource(env, thiz, std::move(source), "MediaDataSource");
}

jstring JNICALL MMR_extractMetadata(JNIEnv* env, jobject thiz, jint key) {
  auto r = GetRetriever(env, thiz);
  if (r == nullptr) {
    jniThrow(env, "java/lang/IllegalStateException", "No retriever available");
    return nullptr;
  }
  auto it = r->snapshot.metadata.find(key);
  return it != r->snapshot.metadata.end() ? env->NewStringUTF(it->second.c_str()) : nullptr;
}

jbyteArray JNICALL MMR_getEmbeddedPicture(JNIEnv* env, jobject thiz, jint) {
  auto r = GetRetriever(env, thiz);
  if (r == nullptr) {
    jniThrow(env, "java/lang/IllegalStateException", "No retriever available");
    return nullptr;
  }
  if (r->snapshot.album_art.empty() ||
      r->snapshot.album_art.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
  jbyteArray a = env->NewByteArray(static_cast<jsize>(r->snapshot.album_art.size()));
  if (a == nullptr) return nullptr;
  env->SetByteArrayRegion(a, 0, static_cast<jsize>(r->snapshot.album_art.size()),
                          reinterpret_cast<const jbyte*>(r->snapshot.album_art.data()));
  return a;
}

// FrameDecoder keeps the extractor's first-sample target time for SEEK_CLOSEST
// and applies the same output predicate before and after decoder EOS. A delayed
// decoder may produce the entire tail only when drained.
std::unique_ptr<VideoFrame> DecodeRetrieverFrame(TrackReader* reader, VideoDecoder* decoder,
                                               int64_t time_us, int option) {
  const bool closest = option == 3;
  const SeekMode mode = closest ? SeekMode::kClosest
      : static_cast<SeekMode>(std::clamp(option, 0, 2));
  Sample sample;
  if (reader->Read(&sample, std::max<int64_t>(time_us, 0), mode) != 0) return nullptr;
  const int64_t target_time = closest
      ? (sample.target_time_us >= 0 ? sample.target_time_us : std::max<int64_t>(time_us, 0))
      : -1;
  auto select = [target_time](std::vector<VideoFrame>& frames) -> std::unique_ptr<VideoFrame> {
    for (VideoFrame& frame : frames) {
      if (target_time < 0 || frame.time_us >= target_time) {
        return std::make_unique<VideoFrame>(std::move(frame));
      }
    }
    return nullptr;
  };
  std::vector<VideoFrame> frames;
  for (int n = 0; n < 600; ++n) {
    if (!decoder->Decode(sample, &frames)) return nullptr;
    if (auto chosen = select(frames)) return chosen;
    frames.clear();
    const int status = reader->Read(&sample);
    if (status < 0) return nullptr;
    if (status > 0) {
      decoder->Drain(&frames);
      return select(frames);
    }
  }
  return nullptr;
}

// _getFrameAtTime(timeUs, option, width, height, params): the frame at (or the
// sync frame around) timeUs as an ARGB_8888 Bitmap, scaled to fit width x height
// (keeping its aspect) when both are positive; null without video.
jobject JNICALL MMR_getFrameAtTime(JNIEnv* env, jobject thiz, jlong time_us, jint option, jint dst_w,
                                   jint dst_h, jobject) {
  auto r = GetRetriever(env, thiz);
  if (r == nullptr || r->snapshot.source == nullptr) {
    jniThrow(env, "java/lang/IllegalStateException", "No retriever available");
    return nullptr;
  }
  std::string error;
  auto ex = Extractor::Open(r->snapshot.source, &error);
  if (ex == nullptr) return nullptr;
  std::unique_ptr<VideoDecoder> decoder;
  std::unique_ptr<TrackReader> reader;
  for (size_t i = 0; i < ex->TrackCount() && decoder == nullptr; i++) {
    Format f = ex->TrackFormat(i);
    if (f.Mime().rfind("video/", 0) != 0) continue;
    decoder = VideoDecoder::Create(f, &error);
    if (decoder != nullptr) reader = ex->OpenTrack(i);
  }
  if (decoder == nullptr || reader == nullptr) {
    MEDIA_LOGW("MediaMetadataRetriever: no video frame: %s", error.c_str());
    return nullptr;
  }
  auto chosen = DecodeRetrieverFrame(reader.get(), decoder.get(), time_us, option);
  if (chosen == nullptr) return nullptr;
  int w = chosen->visible_width(), h = chosen->visible_height();
  if (dst_w > 0 && dst_h > 0 && (dst_w != w || dst_h != h)) {
    double scale = std::min(static_cast<double>(dst_w) / w, static_cast<double>(dst_h) / h);
    w = std::max(1, static_cast<int>(w * scale + 0.5));
    h = std::max(1, static_cast<int>(h * scale + 0.5));
  }
  std::vector<uint32_t> rgba(static_cast<size_t>(w) * h);
  I420ToRgba(*chosen, rgba.data(), w, w, h);
  std::vector<jint> argb(rgba.size());
  for (size_t i = 0; i < rgba.size(); i++) {
    uint32_t p = rgba[i];
    argb[i] = static_cast<jint>(0xff000000u | ((p & 0xffu) << 16) | (p & 0xff00u) | ((p >> 16) & 0xffu));
  }
  jintArray colors = env->NewIntArray(static_cast<jsize>(argb.size()));
  env->SetIntArrayRegion(colors, 0, static_cast<jsize>(argb.size()), argb.data());
  jclass config = env->FindClass("android/graphics/Bitmap$Config");
  jobject argb8888 = env->GetStaticObjectField(
      config, env->GetStaticFieldID(config, "ARGB_8888", "Landroid/graphics/Bitmap$Config;"));
  jclass bitmap = env->FindClass("android/graphics/Bitmap");
  jmethodID create = env->GetStaticMethodID(
      bitmap, "createBitmap", "([IIILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
  jobject b = env->CallStaticObjectMethod(bitmap, create, colors, w, h, argb8888);
  if (CheckException(env, "MediaMetadataRetriever.getFrameAtTime")) return nullptr;
  return b;
}

void JNICALL MMR_init(JNIEnv* env, jclass) {
  jclass cls = env->FindClass("android/media/MediaMetadataRetriever");
  gRetrieverContext = env->GetFieldID(cls, "mNativeContext", "J");
}

void JNICALL MMR_setup(JNIEnv* env, jobject thiz) {
  ReceiverLease<Retriever>::Setup(env, thiz, gRetrieverContext, gRetrieverContextLock);
}

void JNICALL MMR_release(JNIEnv* env, jobject thiz) {
  ReceiverLease<Retriever>::Release(env, thiz, gRetrieverContext, gRetrieverContextLock);
}

const JNINativeMethod kRetrieverMethods[] = {
    {"_setDataSource",
     "(Landroid/os/IBinder;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V",
     GESHER_NATIVE(MMR_setDataSourcePath)},
    {"_setDataSource", "(Ljava/io/FileDescriptor;JJ)V", GESHER_NATIVE(MMR_setDataSourceFD)},
    {"_setDataSource", "(Landroid/media/MediaDataSource;)V", GESHER_NATIVE(MMR_setDataSourceCallback)},
    {"nativeExtractMetadata", "(I)Ljava/lang/String;", GESHER_NATIVE(MMR_extractMetadata)},
    {"getEmbeddedPicture", "(I)[B", GESHER_NATIVE(MMR_getEmbeddedPicture)},
    {"_getFrameAtTime", "(JIIILandroid/media/MediaMetadataRetriever$BitmapParams;)Landroid/graphics/Bitmap;",
     GESHER_NATIVE(MMR_getFrameAtTime)},
    {"release", "()V", GESHER_NATIVE(MMR_release)},
    {"native_finalize", "()V", GESHER_NATIVE(MMR_release)},
    {"native_setup", "()V", GESHER_NATIVE(MMR_setup)},
    {"native_init", "()V", GESHER_NATIVE(MMR_init)},
};

}  // namespace

int RegisterMediaJniOthers(JNIEnv* env) {
  if (RegisterStandins(env, "android/media/MediaMetadataRetriever")) {
    RegisterMethods(env, "android/media/MediaMetadataRetriever", kRetrieverMethods);
  }
  // Until their stages (phase 8, stages 2 and 3): every native answers zero,
  // false or null.
  RegisterMediaExtractor(env);
  RegisterMediaCodec(env);
  // Recording (media3, ADR 0059): MediaMuxer, MediaRecorder.
  RegisterMediaMuxer(env);
  RegisterMediaRecorder(env);
  for (const char* cls : {"android/media/MediaSync",
                          "android/media/MediaHTTPConnection", "android/media/MediaCrypto",
                          "android/media/MediaDrm", "android/media/MediaDescrambler",
                          "android/media/MediaProfiles", "android/media/JetPlayer",
                          "android/media/ResampleInputStream", "android/media/MediaCodec$LinearBlock",
                          "android/media/MediaCodecInfo$CodecCapabilities"}) {
    RegisterStandins(env, cls);
  }
  return 0;
}

}  // namespace gesher::media
