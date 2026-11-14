// Gesher: Android MediaDataSource callbacks shared by media JNI consumers.
#pragma once

#include <jni.h>

#include <memory>

#include "../player/data_source.h"

namespace gesher::media {

// On success the source owns global references and closes the Java callback
// when its last native owner retires. Construction failures preserve the Java
// exception and do not take ownership of the callback. A close exception stays
// pending unless cleanup is preserving an earlier exception; failure mappers
// must consume that cleanup exception before throwing their setup error.
std::unique_ptr<DataSource> OpenJavaDataSource(JNIEnv* env, jobject source);

}  // namespace gesher::media
