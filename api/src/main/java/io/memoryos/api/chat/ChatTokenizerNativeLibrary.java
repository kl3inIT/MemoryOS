package io.memoryos.api.chat;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Verified CPU-only JNI installation, before DJL can consult a cache or select a CUDA flavor. */
final class ChatTokenizerNativeLibrary {
    private static final Path IMAGE_LIBRARY = Path.of("/application/native/tokenizers/libtokenizers.so");
    private static final NativeFile LINUX = new NativeFile("libtokenizers.so", 15486960,
            "edf58c92155e0fe4fae5231e782a1f02deeb5881de7bbcd1195f6e25009da90a");
    private static final List<NativeFile> WINDOWS = List.of(
            new NativeFile("libgcc_s_seh-1.dll", 511408, "32ce10abc802edc83c36d62e0e9f9389bab0c4bfeacf01cc98726da81094b80a"),
            new NativeFile("libstdc++-6.dll", 2011975, "a0b8f623f0da655a30873ef7d6b6be0e6942c34535abcaecce142fa0ab1cef4f"),
            new NativeFile("libwinpthread-1.dll", 56978, "567ca7ae7ecdfb8799b28c41ac3d1a2cc46f4ee2e72c511f027c44250498e893"),
            new NativeFile("tokenizers.dll", 11232256, "3e0084560d5b8f509634c582c9b7bdb268e93d570c85c825198a8deb772ff0ae"));
    private static Path installed;

    private ChatTokenizerNativeLibrary() {}

    static synchronized void prepare() {
        if (installed != null) return;
        try {
            String override = System.getenv("RUST_LIBRARY_PATH");
            String property = System.getProperty("RUST_LIBRARY_PATH");
            if (override != null || property != null) {
                if ((override != null && !override.equals(IMAGE_LIBRARY.toString()))
                        || (property != null && !property.equals(IMAGE_LIBRARY.toString())))
                    throw new IllegalStateException("External tokenizer native libraries are not supported");
                verify(IMAGE_LIBRARY, LINUX);
                installed = IMAGE_LIBRARY;
            } else {
                String architecture = System.getProperty("os.arch");
                if (!architecture.equals("amd64") && !architecture.equals("x86_64"))
                    throw new IllegalStateException("The installed tokenizer requires x86-64");
                boolean windows = System.getProperty("os.name").startsWith("Windows");
                if (!windows && !System.getProperty("os.name").equals("Linux"))
                    throw new IllegalStateException("The installed tokenizer supports Windows and Linux only");
                String classifier = windows ? "win-x86_64" : "linux-x86_64";
                Path directory = Path.of(System.getProperty("java.io.tmpdir"), "memoryos-tokenizers", "0.38.0-cpu-" + classifier);
                Files.createDirectories(directory);
                if (Files.isSymbolicLink(directory)) throw new IOException("Tokenizer native directory must not be a symbolic link");
                if (!windows) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
                for (var file : windows ? WINDOWS : List.of(LINUX)) extract(directory, classifier, file);
                installed = directory.resolve(windows ? "tokenizers.dll" : LINUX.name()).toAbsolutePath();
            }
            System.setProperty("RUST_LIBRARY_PATH", installed.toString());
        } catch (IOException failure) { throw new IllegalStateException("Cannot install verified tokenizer native libraries", failure); }
    }

    private static void extract(Path directory, String classifier, NativeFile file) throws IOException {
        Path destination = directory.resolve(file.name());
        if (!Files.exists(destination)) {
            Path temporary = Files.createTempFile(directory, "native-", ".tmp");
            try (InputStream input = HuggingFaceTokenizer.class.getResourceAsStream("/native/lib/" + classifier + "/cpu/" + file.name())) {
                if (input == null) throw new IOException("Missing packaged CPU tokenizer library");
                try (var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    long copied = 0;
                    for (int count; (count = input.read(buffer)) >= 0;) {
                        copied += count;
                        if (copied > file.size()) throw new IOException("Oversized packaged tokenizer library");
                        output.write(buffer, 0, count);
                    }
                }
                verify(temporary, file);
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
                catch (FileAlreadyExistsException concurrentInstall) { verify(destination, file); }
            } finally { Files.deleteIfExists(temporary); }
        }
        verify(destination, file);
    }

    private static void verify(Path path, NativeFile file) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) != file.size())
            throw new IOException("Invalid installed tokenizer native library");
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            if (!HexFormat.of().formatHex(digest.digest()).equals(file.sha256()))
                throw new IOException("Invalid tokenizer native library checksum");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record NativeFile(String name, long size, String sha256) {}
}
