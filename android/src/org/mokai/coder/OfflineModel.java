package org.mokai.coder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OfflineModel {
    private static final long MAX_EXPANDED_BYTES = 1_500L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2_500;

    static File importZip(InputStream input, File destination) throws IOException {
        if (!destination.mkdir()) throw new IOException("Could not create model directory");
        String base = destination.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int entries = 0;
        try (ZipInputStream archive = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES || Thread.currentThread().isInterrupted()) {
                    throw new IOException("Model import was interrupted or exceeded the entry limit");
                }
                File target = new File(destination, entry.getName()).getCanonicalFile();
                if (!target.getPath().startsWith(base)) throw new IOException("Invalid archive entry");
                if (entry.isDirectory()) {
                    ensureDirectory(target);
                    if (archive.read() != -1) throw new IOException("Unexpected directory contents");
                } else {
                    ensureDirectory(target.getParentFile());
                    if (!target.createNewFile()) throw new IOException("Duplicate archive entry");
                    try (FileOutputStream output = new FileOutputStream(target)) {
                        int count;
                        while ((count = archive.read(buffer)) != -1) {
                            total += count;
                            if (total > MAX_EXPANDED_BYTES || Thread.currentThread().isInterrupted()) {
                                throw new IOException("Model exceeds the supported size or import was cancelled");
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                }
                archive.closeEntry();
            }
        }
        File root = findModel(destination, 2);
        if (root == null) throw new IOException("No Vosk model found in this ZIP");
        return root;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create model directories");
        }
    }

    private static File findModel(File directory, int depth) {
        if (new File(directory, "am/final.mdl").isFile()
                && new File(directory, "conf/model.conf").isFile()
                && new File(directory, "graph").isDirectory()) return directory;
        File[] children = depth == 0 ? null : directory.listFiles(File::isDirectory);
        if (children == null) return null;
        for (File child : children) {
            File found = findModel(child, depth - 1);
            if (found != null) return found;
        }
        return null;
    }

    static void removeImportedFiles(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) removeImportedFiles(file);
        directory.delete();
    }
}
