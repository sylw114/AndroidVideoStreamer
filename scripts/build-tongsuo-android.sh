#!/usr/bin/env bash
set -euo pipefail

ROOT_POSIX="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if command -v cygpath >/dev/null 2>&1; then
    ROOT_DIR="$(cygpath -m "${ROOT_POSIX}")"
    ROOT_SHELL="$(cygpath -u "${ROOT_DIR}")"
else
    ROOT_DIR="${ROOT_POSIX}"
    ROOT_SHELL="${ROOT_POSIX}"
fi
TONGSUO_SOURCE="${ROOT_DIR}/third_party/tongsuo"
NDK_VERSION="${ANDROID_NDK_VERSION:-27.2.12479018}"
ANDROID_API="${ANDROID_API:-29}"
ANDROID_ABIS="${ANDROID_ABIS:-arm64-v8a}"

if [[ ! -f "${TONGSUO_SOURCE}/Configure" ]]; then
    echo "缺少 Tongsuo 子模块，请先执行 git submodule update --init --recursive" >&2
    exit 1
fi

normalize_path() {
    local value="$1"
    value="${value//\\:/:}"
    value="${value//\\//}"
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "${value}"
    else
        printf '%s\n' "${value}"
    fi
}

find_android_sdk() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        normalize_path "${ANDROID_SDK_ROOT}"
        return
    fi
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        normalize_path "${ANDROID_HOME}"
        return
    fi
    if [[ -f "${ROOT_DIR}/local.properties" ]]; then
        local configured
        configured="$(sed -n 's/^sdk\.dir=//p' "${ROOT_DIR}/local.properties" | tail -n 1)"
        if [[ -n "${configured}" ]]; then
            normalize_path "${configured}"
            return
        fi
    fi
    return 1
}

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    NDK_ROOT="$(normalize_path "${ANDROID_NDK_HOME}")"
elif [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
    NDK_ROOT="$(normalize_path "${ANDROID_NDK_ROOT}")"
else
    SDK_ROOT="$(find_android_sdk || true)"
    NDK_ROOT="${SDK_ROOT}/ndk/${NDK_VERSION}"
fi

if [[ ! -d "${NDK_ROOT}/toolchains/llvm/prebuilt" ]]; then
    echo "找不到 Android NDK ${NDK_VERSION}：${NDK_ROOT}" >&2
    echo "请设置 ANDROID_NDK_HOME，或安装与 app/build.gradle.kts 一致的 NDK。" >&2
    exit 1
fi

case "$(uname -s)" in
    MINGW*|MSYS*) HOST_TAG="windows-x86_64" ;;
    Darwin*) HOST_TAG="darwin-x86_64" ;;
    Linux*) HOST_TAG="linux-x86_64" ;;
    *) echo "不支持的构建主机：$(uname -s)" >&2; exit 1 ;;
esac

if command -v cygpath >/dev/null 2>&1; then
    NDK_ROOT_SHELL="$(cygpath -u "${NDK_ROOT}")"
else
    NDK_ROOT_SHELL="${NDK_ROOT}"
fi
TOOLCHAIN_BIN="${NDK_ROOT_SHELL}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
if [[ ! -d "${TOOLCHAIN_BIN}" ]]; then
    echo "找不到 NDK LLVM 工具链：${TOOLCHAIN_BIN}" >&2
    exit 1
fi

if ! command -v perl >/dev/null 2>&1; then
    echo "找不到 perl；Windows 可使用 Git for Windows 自带的 usr/bin/perl。" >&2
    exit 1
fi
if ! command -v make >/dev/null 2>&1; then
    echo "找不到 make。" >&2
    exit 1
fi

export ANDROID_NDK_ROOT="${NDK_ROOT_SHELL}"
export ANDROID_NDK_HOME="${NDK_ROOT_SHELL}"
export PATH="${TOOLCHAIN_BIN}:${PATH}"
export MSYS2_ENV_CONV_EXCL="PERL5LIB${MSYS2_ENV_CONV_EXCL:+;${MSYS2_ENV_CONV_EXCL}}"
export PERL5LIB="${ROOT_SHELL}/scripts/perl${PERL5LIB:+:${PERL5LIB}}"

case "${NUMBER_OF_PROCESSORS:-}" in
    ''|*[!0-9]*) BUILD_JOBS=4 ;;
    *) BUILD_JOBS="${NUMBER_OF_PROCESSORS}" ;;
esac

for ABI in ${ANDROID_ABIS//,/ }; do
    case "${ABI}" in
        arm64-v8a) CONFIGURE_TARGET="android-arm64" ;;
        armeabi-v7a) CONFIGURE_TARGET="android-arm" ;;
        x86) CONFIGURE_TARGET="android-x86" ;;
        x86_64) CONFIGURE_TARGET="android-x86_64" ;;
        *) echo "不支持的 Android ABI：${ABI}" >&2; exit 1 ;;
    esac

    BUILD_DIR="${ROOT_DIR}/third_party/build/tongsuo-work/${ABI}"
    INSTALL_DIR="${ROOT_DIR}/third_party/build/tongsuo/${ABI}"
    mkdir -p "${BUILD_DIR}" "${INSTALL_DIR}"

    echo "正在构建 Tongsuo 8.3-stable：ABI=${ABI}, API=${ANDROID_API}"
    pushd "${BUILD_DIR}" >/dev/null
    if [[ -f Makefile ]]; then
        make clean >/dev/null 2>&1 || true
    fi
    perl "${TONGSUO_SOURCE}/Configure" \
        "${CONFIGURE_TARGET}" \
        "-D__ANDROID_API__=${ANDROID_API}" \
        "-Wno-macro-redefined" \
        no-shared \
        no-tests \
        no-unit-test \
        --prefix="${INSTALL_DIR}" \
        --libdir=lib
    make -j"${BUILD_JOBS}" build_libs
    make install_dev
    popd >/dev/null

    test -f "${INSTALL_DIR}/lib/libssl.a"
    test -f "${INSTALL_DIR}/lib/libcrypto.a"
done

echo "Tongsuo Android 静态库构建完成。"
