#!/usr/bin/env python3
"""从固定源码构建 API 21 双 ARM HEIC 软件解码库。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

MODULE = Path(__file__).resolve().parents[1]
NDK_VERSION = "27.1.12297006"
ABIS = ("armeabi-v7a", "arm64-v8a")



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


def archive_source(source):
    directory = MODULE / "build/sources"
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / f'{source["name"]}-{source["version"]}.tar.gz'
    if not archive.exists():
        temporary = archive.with_suffix(".download")
        try:
            subprocess.run([
                "curl", "-fL", "--retry", "3", "--retry-all-errors", "--retry-max-time", "180",
                "--connect-timeout", "30", "--max-time", "90", source["url"], "-o", str(temporary),
            ], check=True)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != source["sha256"]:
                raise ValueError(f'{source["name"]} 源码校验失败')
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != source["sha256"]:
        raise ValueError(f"源码校验失败：{archive}")
    extracted = directory / f'{source["name"]}-{source["version"]}'
    if not extracted.exists():
        extract_archive(archive, directory)
    return extracted


def build(ndk, cmake):
    if f"Pkg.Revision = {NDK_VERSION}" not in (ndk / "source.properties").read_text():
        raise ValueError(f"请使用 Android NDK {NDK_VERSION}")
    sources = json.loads((MODULE / "sources.json").read_text())
    directories = {source["name"]: archive_source(source) for source in sources}
    fingerprint = Path(__file__).read_bytes() + (MODULE / "sources.json").read_bytes()
    for path in sorted((MODULE / "src/main/cpp").iterdir()):
        fingerprint += path.read_bytes()
    stamp = hashlib.sha256(fingerprint + NDK_VERSION.encode()).hexdigest()
    parallel = str(min(os.cpu_count() or 2, 4))
    for abi in ABIS:
        output = MODULE / "build/native-libs" / abi / "libheif_native.so"
        marker = output.with_suffix(".stamp")
        if output.exists() and marker.exists() and marker.read_text() == stamp:
            continue
        work = MODULE / "build/native-work" / abi
        if work.exists():
            shutil.rmtree(work)
        work.mkdir(parents=True)
        prefix = work / "prefix"
        common = [
            f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
            f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-21", "-DANDROID_STL=c++_static",
            "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
            "-DCMAKE_INSTALL_LIBDIR=lib", f"-DCMAKE_INSTALL_PREFIX={prefix}",
            "-DCMAKE_C_FLAGS_RELEASE=-O2 -DNDEBUG -ffunction-sections -fdata-sections",
            "-DCMAKE_CXX_FLAGS_RELEASE=-O2 -DNDEBUG -ffunction-sections -fdata-sections",
            "-DBUILD_SHARED_LIBS=OFF",
        ]

        def compile_library(name, source_dir, options, target="install"):
            build_dir = work / name
            subprocess.run([str(cmake), "-S", str(source_dir), "-B", str(build_dir), *common, *options], check=True)
            subprocess.run([str(cmake), "--build", str(build_dir), "--target", target, "--parallel", parallel], check=True)
            return build_dir

        compile_library("de265", directories["libde265"], [
            "-DENABLE_SDL=OFF", "-DENABLE_DECODER=OFF", "-DENABLE_ENCODER=OFF",
            "-DENABLE_SHERLOCK265=OFF", "-DENABLE_INTERNAL_DEVELOPMENT_TOOLS=OFF", "-DWITH_FUZZERS=OFF",
        ])
        disabled = [
            "X265", "X264", "KVAZAAR", "UVG266", "VVDEC", "VVENC", "OpenH264_DECODER",
            "DAV1D", "AOM_DECODER", "AOM_ENCODER", "SvtEnc", "RAV1E", "JPEG_DECODER", "JPEG_ENCODER",
            "OpenJPEG_DECODER", "OpenJPEG_ENCODER", "OPENJPH_ENCODER", "FFMPEG_DECODER", "WEBCODECS",
            "UNCOMPRESSED_CODEC", "HEADER_COMPRESSION", "LIBSHARPYUV",
        ]
        compile_library("heif", directories["libheif"], [
            f"-DCMAKE_PREFIX_PATH={prefix}", f"-DCMAKE_FIND_ROOT_PATH={prefix}",
            "-DWITH_LIBDE265=ON", "-DWITH_LIBDE265_PLUGIN=OFF", "-DENABLE_PLUGIN_LOADING=OFF",
            "-DENABLE_PARALLEL_TILE_DECODING=OFF", "-DWITH_EXAMPLES=OFF", "-DWITH_GDK_PIXBUF=OFF",
            "-DBUILD_TESTING=OFF", "-DBUILD_DOCUMENTATION=OFF", "-DBUILD_DEVELOPMENT_TOOLS=OFF",
            *[f"-DWITH_{name}=OFF" for name in disabled],
        ])
        jni_build = compile_library("jni", MODULE / "src/main/cpp", [f"-DHEIF_PREFIX={prefix}"], "heif_native")
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(jni_build / "libheif_native.so", output)
        # 只裁剪待分发副本；中间静态库保留，便于重新链接和定位问题。
        host = "darwin-x86_64" if os.uname().sysname == "Darwin" else "linux-x86_64"
        strip = ndk / "toolchains/llvm/prebuilt" / host / "bin/llvm-strip"
        subprocess.run([str(strip), str(output)], check=True)
        marker.write_text(stamp)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--cmake", required=True, type=Path)
    args = parser.parse_args()
    build(args.ndk.resolve(), args.cmake.resolve())
