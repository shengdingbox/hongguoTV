// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.heif;

import android.graphics.Bitmap;
import java.io.IOException;
import java.nio.ByteBuffer;

/** HEIC 图片软件解码入口；格式探测不加载原生库，实际解码串行执行。 */
public final class HeifDecoder {
    public static final int MAX_INPUT_BYTES = 8 * 1024 * 1024;
    private static final Object DECODE_LOCK = new Object();

    private HeifDecoder() {}

    private static final class NativeLibrary {
        static { System.loadLibrary("heif_native"); }
        static void ensureLoaded() {}
    }

    public static boolean handles(ByteBuffer source) {
        ByteBuffer data = source.asReadOnlyBuffer().order(java.nio.ByteOrder.BIG_ENDIAN);
        int start = data.position();
        if (data.remaining() < 16 || data.getInt(start + 4) != 0x66747970) return false;
        long boxSize = data.getInt(start) & 0xffffffffL;
        if (boxSize < 16) return false;
        int end = (int) Math.min(Math.min((long) start + boxSize, data.limit()), (long) start + 128);
        boolean heif = false;
        for (int offset = start + 8; offset + 4 <= end; offset += 4) {
            if (offset == start + 12) continue;
            int brand = data.getInt(offset);
            // AVIF 也可能带 mif1，必须先排除，避免误送入仅支持 HEVC 的解码器。
            if (brand == 0x61766966 || brand == 0x61766973) return false;
            if (brand == 0x68656963 || brand == 0x68656978 || brand == 0x68657663 ||
                brand == 0x68657678 || brand == 0x6865696d || brand == 0x68656973 ||
                brand == 0x6865766d || brand == 0x68657673 || brand == 0x6d696631 ||
                brand == 0x6d736631) heif = true;
        }
        return heif;
    }

    public static Bitmap decode(byte[] data, int width, int height) throws IOException {
        if (data.length == 0 || data.length > MAX_INPUT_BYTES) throw new IOException("HEIC 图片文件过大或为空");
        synchronized (DECODE_LOCK) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("HEIC 图片加载已取消");
            try {
                NativeLibrary.ensureLoaded();
                return nativeDecode(data, width, height);
            } catch (LinkageError error) {
                throw new IOException("HEIC 图片解码库无法加载", error);
            }
        }
    }

    private static native Bitmap nativeDecode(byte[] data, int width, int height) throws IOException;
}
