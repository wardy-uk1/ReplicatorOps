#!/usr/bin/env bash
# Builds the CuraEngine command-line slicer without Conan.
#   build.sh host            -> x86_64 Linux binary (CI self-test)
#   build.sh android <ndk>   -> arm64-v8a binary for the app (packaged as libcuraengine.so)
# Output: $OUT/CuraEngine and $OUT/definitions/*.def.json
set -euo pipefail
TARGET=${1:-host}
NDK=${2:-${ANDROID_NDK_HOME:-}}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$HERE/work}
OUT=${OUT:-$WORK/out-$TARGET}
CURA_VERSION=5.10.3
mkdir -p "$WORK" "$OUT"
cd "$WORK"

clone() { # repo ref dir
  [ -d "$3" ] || git -c advice.detachedHead=false clone -q --depth 1 -b "$2" "https://github.com/$1.git" "$3"
}
clone Ultimaker/CuraEngine "$CURA_VERSION" CuraEngine
clone Ultimaker/Cura "$CURA_VERSION" Cura          # printer definitions (fdmprinter.def.json)
clone Ultimaker/Scripta_public main scripta
clone mapbox/wagyu 0.5.0 wagyu
clone mapbox/geometry.hpp v1.1.0 geometry
clone Tencent/rapidjson master rapidjson
clone nothings/stb master stb
clone gabime/spdlog v1.15.1 spdlog
clone fmtlib/fmt 11.1.3 fmt
clone ericniebler/range-v3 0.12.0 range-v3
clone Ultimaker/CuraEngine 4.13.1 ce4                # bundled Clipper 6.4.2 (polyclipping)

if [ ! -d boost-inc ]; then
  [ -f boost.tar.xz ] || curl -sSLf -o boost.tar.xz https://github.com/boostorg/boost/releases/download/boost-1.86.0/boost-1.86.0-cmake.tar.xz
  tar xf boost.tar.xz
  mkdir -p boost-inc
  for d in boost-1.86.0/libs/*/include boost-1.86.0/libs/*/*/include; do [ -d "$d/boost" ] && cp -r "$d/boost" boost-inc/; done
  rm -rf boost-1.86.0
fi
# wagyu: Ultimaker's patch (execinfo only when available)
sed -i 's/^#ifdef DEBUG$/#if defined(DEBUG) \&\& __has_include(<execinfo.h>)/' wagyu/include/mapbox/geometry/wagyu/ring.hpp wagyu/include/mapbox/geometry/wagyu/ring_util.hpp
# Command-line progress (CuraEngine's CLI ignores -p): print "#PROGRESS n" to stderr for the app.
python3 - CuraEngine/src/communication/CommandLine.cpp <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = """    if (last_shown_progress_ == rounded_amount) // No need to send another tiny update step.
    {
        return;
    }
}"""
new = """    if (last_shown_progress_ == rounded_amount) // No need to send another tiny update step.
    {
        return;
    }
    const_cast<CommandLine*>(this)->last_shown_progress_ = rounded_amount;
    fprintf(stderr, "#PROGRESS %u\\n", rounded_amount);
    fflush(stderr);
}"""
if new not in s:
    assert old in s, "CommandLine::sendProgress changed"
    s = s.replace(old, new)
    if "#include <cstdio>" not in s: s = s.replace('#include <cerrno>', '#include <cstdio>\n#include <cerrno>', 1)
    open(p, 'w').write(s)
PY
mkdir -p clipper/include/polyclipping
cp ce4/libs/clipper/clipper.hpp clipper/include/polyclipping/
cp ce4/libs/clipper/clipper.cpp clipper/

# Package shims so CuraEngine's own CMakeLists finds everything.
S=$WORK/shims; mkdir -p "$S"
iface() { # file target includes [defs]
  cat > "$S/$1" <<EOF
if(NOT TARGET $2)
  add_library($2 INTERFACE IMPORTED)
  set_target_properties($2 PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "$3" INTERFACE_COMPILE_DEFINITIONS "${4:-}")
endif()
EOF
}
cp "$HERE/StandardProjectSettings.cmake" "$S/StandardProjectSettings.cmake"
echo 'include(${CMAKE_CURRENT_LIST_DIR}/StandardProjectSettings.cmake)' > "$S/standardprojectsettings-config.cmake"
iface mapbox-wagyu-config.cmake mapbox-wagyu::mapbox-wagyu "$WORK/wagyu/include;$WORK/geometry/include"
iface RapidJSONConfig.cmake rapidjson "$WORK/rapidjson/include"
iface stb-config.cmake stb::stb "$WORK/stb"
iface BoostConfig.cmake boost::boost "$WORK/boost-inc"
iface fmt-config.cmake fmt::fmt "$WORK/fmt/include" "FMT_HEADER_ONLY"
iface spdlog-config.cmake spdlog::spdlog "$WORK/spdlog/include;$WORK/fmt/include" "SPDLOG_HEADER_ONLY;SPDLOG_FMT_EXTERNAL;FMT_HEADER_ONLY"
iface range-v3-config.cmake range-v3::range-v3 "$WORK/range-v3/include"
iface scripta-config.cmake scripta::scripta "$WORK/scripta/include"
cat > "$S/clipper-config.cmake" <<EOF
if(NOT TARGET clipper::clipper)
  add_library(clipper_static STATIC "$WORK/clipper/clipper.cpp")
  target_include_directories(clipper_static PUBLIC "$WORK/clipper/include" "$WORK/clipper/include/polyclipping")
  set_target_properties(clipper_static PROPERTIES POSITION_INDEPENDENT_CODE ON)
  add_library(clipper::clipper ALIAS clipper_static)
endif()
EOF
for f in "$S"/*-config.cmake "$S"/*Config.cmake; do :; done

ARGS=(-G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=20 -DCMAKE_CXX_STANDARD_REQUIRED=ON -DCMAKE_PREFIX_PATH="$S" -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
      -Dstandardprojectsettings_DIR="$S" -Dmapbox-wagyu_DIR="$S" -Dclipper_DIR="$S" -DRapidJSON_DIR="$S" -Dstb_DIR="$S"
      -DBoost_DIR="$S" -Dspdlog_DIR="$S" -Dfmt_DIR="$S" -Drange-v3_DIR="$S" -Dscripta_DIR="$S"
      -DENABLE_ARCUS=OFF -DENABLE_PLUGINS=OFF -DENABLE_TESTING=OFF -DENABLE_BENCHMARKS=OFF -DEXTENSIVE_WARNINGS=OFF
      -DENABLE_SENTRY=OFF -DENABLE_MORE_COMPILER_OPTIMIZATION_FLAGS=OFF -DCURA_ENGINE_VERSION=$CURA_VERSION -DCURA_ENGINE_HASH=release)
if [ "$TARGET" = android ]; then
  ARGS+=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_static)
fi
cmake -S CuraEngine -B "build-$TARGET" "${ARGS[@]}"
cmake --build "build-$TARGET" --target CuraEngine -j"$(nproc)"
cp "build-$TARGET/CuraEngine" "$OUT/CuraEngine"
[ "$TARGET" = android ] && "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip "$OUT/CuraEngine"
mkdir -p "$OUT/definitions"
cp Cura/resources/definitions/fdmprinter.def.json Cura/resources/definitions/fdmextruder.def.json "$OUT/definitions/"
ls -la "$OUT" "$OUT/definitions"
