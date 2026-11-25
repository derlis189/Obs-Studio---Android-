#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${IOS_PROBE_BUILD_DIR:-${project_root}/out/ios-probe}"

if [[ -z "${QT_IOS_ROOT:-}" ]]; then
	cat >&2 <<'EOF'
Set QT_IOS_ROOT to the iOS Qt kit directory, for example:
  export QT_IOS_ROOT="$HOME/Qt/6.9.3/ios"
EOF
	exit 2
fi

qt_cmake="${QT_IOS_ROOT}/bin/qt-cmake"
if [[ ! -x "${qt_cmake}" ]]; then
	echo "Qt iOS CMake wrapper not found: ${qt_cmake}" >&2
	exit 2
fi

"${qt_cmake}" -S "${project_root}/ios" -B "${build_dir}" -G Xcode \
	-DCMAKE_BUILD_TYPE=Debug
cmake --build "${build_dir}" --config Debug --target tcstudio-ios-probe

echo "Built Xcode project: ${build_dir}/TCStudioIOSProbe.xcodeproj"
echo "Open it in Xcode to select an iPhone/iOS Simulator and run the probe."
