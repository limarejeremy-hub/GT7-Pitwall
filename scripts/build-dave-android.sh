#!/usr/bin/env bash
# Compile libdave-jvm (chiffrement de bout en bout DAVE, obligatoire pour la voix Discord) pour Android arm64-v8a,
# à partir des sources officielles : https://github.com/KyokoBot/libdave-jvm (Apache 2.0), qui embarque libdave
# (Discord), mlspp et BoringSSL. Aucun binaire tiers précompilé n'entre dans l'APK.
#
# Usage : scripts/build-dave-android.sh <dossier de sortie>
# Produit <sortie>/arm64-v8a/libdave-jvm.so. Nécessite le NDK Android (ANDROID_NDK_HOME), CMake et Ninja.
set -euo pipefail

REPO="https://github.com/KyokoBot/libdave-jvm"
TAG="0.2.0"
# Empreinte du commit du tag 0.2.0 : doit correspondre aux artefacts Maven impl-jni / adapter-jda 0.2.0.
COMMIT="913380d1467e2393f2ca45d926677939a3625f82"

OUT="${1:?dossier de sortie}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
WORK="${DAVE_WORK:-$(pwd)/build/dave-src}"

if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "NDK Android introuvable (ANDROID_NDK_HOME)." >&2
  exit 1
fi

rm -rf "$WORK"
git clone --quiet --depth 1 --branch "$TAG" "$REPO" "$WORK"
ACTUAL="$(git -C "$WORK" rev-parse HEAD)"
if [ "$ACTUAL" != "$COMMIT" ]; then
  echo "Commit inattendu pour $TAG : $ACTUAL (attendu $COMMIT)" >&2
  exit 1
fi

# Adaptations Android (2) : le jni.h d'Android déclare AttachCurrentThread(JNIEnv**, …) et non (void**, …).
JNI_UTILS="$WORK/natives/src/main/cpp/jni_utils.h"
ORIG='jvm_->AttachCurrentThread(reinterpret_cast<void **>(&env_), nullptr)'
if [ "$(grep -cF "$ORIG" "$JNI_UTILS")" != "1" ]; then
  echo "Correctif JNI Android : ligne attendue introuvable dans jni_utils.h" >&2
  exit 1
fi
sed -i 's|jvm_->AttachCurrentThread(reinterpret_cast<void \*\*>(&env_), nullptr)|jvm_->AttachCurrentThread(\&env_, nullptr)|' "$JNI_UTILS"
grep -qF 'jvm_->AttachCurrentThread(&env_, nullptr)' "$JNI_UTILS"

# Sur Android, libdave définit KeyPairContextType comme JNIEnv* (clés persistantes Android). Les clés persistantes
# sont désactivées (PERSISTENT_KEYS=OFF) : libdave compile persisted_key_pair_null.cpp, qui ignore ce contexte.
BIND_SESSION="$WORK/natives/src/main/cpp/bind_session.cpp"
CTX='static_cast<mls::KeyPairContextType>(sessionContextStorage())'
if [ "$(grep -cF "$CTX" "$BIND_SESSION")" != "1" ]; then
  echo "Correctif Android : ligne attendue introuvable dans bind_session.cpp" >&2
  exit 1
fi
sed -i 's|static_cast<mls::KeyPairContextType>(sessionContextStorage())|mls::KeyPairContextType{}|' "$BIND_SESSION"
grep -qF 'mls::KeyPairContextType{}' "$BIND_SESSION"

cmake -S "$WORK/natives" -B "$WORK/build-arm64" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-31 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DBORINGSSL_INCLUDE_DIR="$WORK/natives/boringssl/include"
# BORINGSSL_INCLUDE_DIR : la chaîne Android limite find_path au sysroot du NDK ; mlspp ne trouverait pas la
# BoringSSL embarquée (« BoringSSL required but not found »).
cmake --build "$WORK/build-arm64" --target dave-jvm --parallel

mkdir -p "$OUT/arm64-v8a"
cp "$WORK/build-arm64/libdave-jvm.so" "$OUT/arm64-v8a/libdave-jvm.so"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$OUT/arm64-v8a/libdave-jvm.so"
ls -l "$OUT/arm64-v8a/libdave-jvm.so"
