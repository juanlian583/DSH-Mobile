package com.dsh.mobile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;

/**
 * Minimal but robust tar reader: supports ustar (name/prefix split), GNU
 * longname/longlink ('L'/'K') and pax ('x'/'g') headers, files, dirs, symlinks
 * and hardlinks. Used to unpack the Ubuntu runtime rootfs on the phone.
 */
public final class TarExtractor {

    public interface Progress {
        /** @param extractedBytes bytes of file payload copied so far */
        void onProgress(long extractedBytes, String entry);
    }

    private TarExtractor() {}

    public static void extract(File tarGz, File destDir, Progress progress) throws IOException {
        extractGz(new FileInputStream(tarGz), destDir, progress);
    }

    /** 从任意原始(gzip)流解压——用于直接从 APK 资源内置运行时解压。 */
    public static void extractGz(InputStream rawIn, File destDir, Progress progress) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(rawIn, 1 << 16))) {
            extractStream(in, destDir, progress);
        }
    }

    private static void extractStream(InputStream in, File destDir, Progress progress) throws IOException {
        byte[] header = new byte[512];
        long total = 0;
        while (true) {
            int read = readFully(in, header, 512);
            if (read == 0 || isZeroBlock(header)) break; // EOF / end-of-archive marker

            String name = parseString(header, 0, 100);
            String linkName = parseString(header, 157, 100);
            char type = (char) header[156];
            long size = parseOctal(header, 124, 12);
            int mode = (int) parseOctal(header, 100, 8);

            // Extension headers precede the real header.
            if (type == 'L') {
                name = readStringBlock(in, size);
                readFully(in, header, 512);
                if (isZeroBlock(header)) break;
                linkName = parseString(header, 157, 100);
                type = (char) header[156];
                size = parseOctal(header, 124, 12);
                mode = (int) parseOctal(header, 100, 8);
            } else if (type == 'K') {
                readStringBlock(in, size);
                readFully(in, header, 512);
                if (isZeroBlock(header)) break;
                name = parseString(header, 0, 100);
                linkName = parseString(header, 157, 100);
                type = (char) header[156];
                size = parseOctal(header, 124, 12);
                mode = (int) parseOctal(header, 100, 8);
            } else if (type == 'x' || type == 'g') {
                String pax = readStringBlock(in, size);
                String p = paxValue(pax, "path");
                String lp = paxValue(pax, "linkpath");
                if (p != null) name = p;
                if (lp != null) linkName = lp;
                readFully(in, header, 512);
                if (isZeroBlock(header)) break;
                type = (char) header[156];
                size = parseOctal(header, 124, 12);
                mode = (int) parseOctal(header, 100, 8);
            }

            String prefix = parseString(header, 345, 155);
            String full = prefix.isEmpty() ? name : prefix + "/" + name;
            full = full.replaceFirst("^\\./", "").replaceFirst("^/+", "");
            if (full.isEmpty() || full.equals(".")) {
                skip(in, size + pad(size));
                continue;
            }

            Path target = destDir.toPath().resolve(full).normalize();
            if (!target.startsWith(destDir.toPath())) {
                throw new IOException("path traversal blocked: " + full);
            }

            switch (type) {
                case '5': // directory
                    Files.createDirectories(target);
                    break;
                case '2': { // symlink
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(target);
                    try {
                        Files.createSymbolicLink(target, Paths.get(linkName));
                    } catch (Throwable t) {
                        // Some Android versions restrict symlink creation; not fatal.
                    }
                    break;
                }
                case '1': { // hardlink -> copy content of target
                    Path src = destDir.toPath().resolve(linkName.replaceFirst("^\\./", "")).normalize();
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(target);
                    if (Files.isRegularFile(src)) {
                        Files.copy(src, target);
                        applyMode(target, mode);
                    }
                    break;
                }
                default: { // regular file
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target.toFile()), 1 << 16)) {
                        copyN(in, out, size);
                    }
                    applyMode(target, mode);
                }
            }

            skip(in, pad(size));
            total += size;
            if (progress != null) progress.onProgress(total, full);
        }
    }

    private static long pad(long size) {
        return (512 - (size % 512)) % 512;
    }

    private static void applyMode(Path p, int mode) {
        File f = p.toFile();
        boolean anyExec = (mode & 0111) != 0;
        boolean anyWrite = (mode & 0222) != 0;
        boolean anyRead = (mode & 0444) != 0;
        try {
            f.setReadable(anyRead, false);
            // 可执行文件不可写（Android W^X 兼容；运行期也不需要改写这些二进制）
            f.setWritable(anyWrite && !anyExec, false);
            f.setExecutable(anyExec, false);
            if (f.isDirectory()) f.setExecutable(true, false);
        } catch (Throwable ignored) {}
    }

    private static String paxValue(String pax, String key) {
        String needle = key + "=";
        int i = 0;
        while (i < pax.length()) {
            int sp = pax.indexOf(' ', i);
            if (sp < 0) break;
            int len = 0;
            try { len = Integer.parseInt(pax.substring(i, sp)); } catch (NumberFormatException e) { break; }
            int segEnd = Math.min(i + len, pax.length());
            String rec = pax.substring(sp + 1, segEnd);
            if (rec.startsWith(needle)) {
                String v = rec.substring(needle.length());
                if (v.endsWith("\n")) v = v.substring(0, v.length() - 1);
                return v;
            }
            i = segEnd;
        }
        return null;
    }

    private static String readStringBlock(InputStream in, long size) throws IOException {
        byte[] buf = new byte[(int) Math.min(size, 1 << 20)];
        readFully(in, buf, buf.length);
        skip(in, pad(size));
        int end = buf.length;
        while (end > 0 && (buf[end - 1] == 0 || buf[end - 1] == '\n' || buf[end - 1] == ' ')) end--;
        return new String(buf, 0, end, StandardCharsets.UTF_8);
    }

    private static String parseString(byte[] h, int off, int len) {
        int end = off + len;
        int i = off;
        while (i < end && h[i] != 0) i++;
        return new String(h, off, i - off, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] h, int off, int len) {
        long v = 0;
        int end = Math.min(off + len, h.length);
        for (int i = off; i < end; i++) {
            byte c = h[i];
            if (c == 0 || c == ' ') continue;
            if (c == 0x80) { // base-256 (GNU) encoding
                v = 0;
                for (int j = i + 1; j < end; j++) v = (v << 8) | (h[j] & 0xff);
                return v;
            }
            if (c < '0' || c > '7') break;
            v = (v << 3) | (c - '0');
        }
        return v;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[1 << 16];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new IOException("unexpected EOF in tar payload");
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) return;
                left--;
            } else {
                left -= s;
            }
        }
    }
}
