#!/usr/bin/env python3
"""从固定 FFmpeg 源码构建 Android 5 使用的双 ARM AAC 音频库。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile

VERSION = "6.1.6"
SHA256 = "d4fcb164028dd3beee5d92c0ac72e46aac6973c75ea12dc14de07bf8f407370a"
NDK_VERSION = "27.1.12297006"
MODULE = Path(__file__).resolve().parents[1]
ABIS = {
    "armeabi-v7a": ("arm", "armv7-a", "armv7a-linux-androideabi"),
    "arm64-v8a": ("aarch64", "armv8-a", "aarch64-linux-android"),
}



def extract_archive(archive, directory):
    """校验归档成员路径后解压，兼容 Python 3.11 与 3.12 的 tarfile 接口。"""
    with tarfile.open(archive) as tar:
        root = directory.resolve()
        for member in tar.getmembers():
            target = (directory / member.name).resolve()
            if target != root and root not in target.parents:
                raise ValueError(f"归档成员路径不安全：{member.name}")
        if "filter" in tarfile.TarFile.extractall.__code__.co_varnames:
            tar.extractall(directory, filter="data")
        else:
            tar.extractall(directory)


def source_archive():
    archive = MODULE / "build" / f"ffmpeg-{VERSION}.tar.xz"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        temporary = archive.with_suffix(".download")
        try:
            subprocess.run([
                "curl", "-fL", "--retry", "3", "--retry-all-errors",
                "--retry-max-time", "300", "--connect-timeout", "30", "--max-time", "120",
                f"https://ffmpeg.org/releases/ffmpeg-{VERSION}.tar.xz", "-o", str(temporary),
            ], check=True)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != SHA256:
                raise ValueError("FFmpeg 源码校验失败")
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError(f"FFmpeg 源码校验失败：{archive}")
    return archive


def build(ndk):
    ndk_metadata = (ndk / "source.properties").read_bytes()
    if f"Pkg.Revision = {NDK_VERSION}".encode() not in ndk_metadata:
        raise ValueError(f"请使用 Android NDK {NDK_VERSION}")
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}.get(platform.system())
    if host is None:
        raise ValueError("原生库构建需要 Linux 或 macOS；Windows 请使用 WSL")
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    archive = source_archive()
    source = MODULE / "build" / f"ffmpeg-{VERSION}"
    if not source.exists():
        extract_archive(archive, archive.parent)
    jni = MODULE / "src/main/jni/ffmpeg_jni.cc"
    # 与 Gradle 缓存输入保持一致，环境或工具链变化时也使本地指纹失效。
    environment = json.dumps({
        "host": [platform.system(), platform.machine()],
        "flags": {name: os.environ.get(name, "") for name in (
            "CFLAGS", "CPPFLAGS", "CXXFLAGS", "LDFLAGS", "SOURCE_DATE_EPOCH",
        )},
    }, sort_keys=True).encode()
    fingerprint = Path(__file__).read_bytes() + ndk_metadata + environment
    for path in sorted((MODULE / "src/main/jni").rglob("*")):
        if path.is_file():
            fingerprint += path.relative_to(MODULE).as_posix().encode() + b"\0" + path.read_bytes()
    stamp = hashlib.sha256(fingerprint + SHA256.encode() + NDK_VERSION.encode()).hexdigest()
    for abi, (arch, cpu, triple) in ABIS.items():
        output = MODULE / "build/native-libs" / abi / "libffmpegJNI.so"
        marker = output.with_suffix(".stamp")
        if output.exists() and marker.exists() and marker.read_text() == stamp:
            continue
        work = MODULE / "build/native-work" / abi
        # 源码或配置变化后重新生成中间文件，避免复用不匹配的目标文件。
        if work.exists():
            shutil.rmtree(work)
        work.mkdir(parents=True)
        output.parent.mkdir(parents=True, exist_ok=True)
        cc = tools / f"{triple}21-clang"
        cxx = tools / f"{triple}21-clang++"
        subprocess.run([
            str(source / "configure"), "--target-os=android", f"--arch={arch}", f"--cpu={cpu}",
            f"--cc={cc}", f"--cxx={cxx}", f'--ar={tools / "llvm-ar"}',
            f'--nm={tools / "llvm-nm"}', f'--ranlib={tools / "llvm-ranlib"}',
            f'--strip={tools / "llvm-strip"}', "--enable-cross-compile", "--enable-pic",
            "--enable-static", "--disable-shared", "--disable-doc", "--disable-programs",
            "--disable-everything", "--disable-network", "--disable-avdevice", "--disable-avformat",
            "--disable-swscale", "--disable-postproc", "--disable-avfilter", "--disable-symver",
            "--disable-autodetect", "--enable-swresample", "--enable-decoder=aac",
        ], cwd=work, check=True)
        subprocess.run(["make", f"-j{min(os.cpu_count() or 2, 8)}"], cwd=work, check=True)
        # 延后查找源码头文件，避免 VERSION 文件遮蔽 C++ 的标准头文件。
        subprocess.run([
            str(cxx), "-shared", "-fPIC", "-std=c++11", "-O2", "-idirafter", str(source),
            "-iquote", ".", str(jni), "-Wl,--start-group", "libswresample/libswresample.a",
            "libavcodec/libavcodec.a", "libavutil/libavutil.a", "-Wl,--end-group", "-llog",
            "-landroid", "-lm", "-Wl,-Bsymbolic", "-Wl,-z,max-page-size=16384",
            "-static-libstdc++", "-o", str(output),
        ], cwd=work, check=True)
        subprocess.run([str(tools / "llvm-strip"), str(output)], check=True)
        marker.write_text(stamp)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path, help="Android NDK 安装目录")
    options = parser.parse_args()
    build(options.ndk.resolve())
