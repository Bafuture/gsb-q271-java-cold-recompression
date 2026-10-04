package com.example.gsb.recompress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.CRC32;

/**
 * File-backed {@link BlobStore}. Layout under the root directory:
 * <pre>
 *   blobs/&lt;id&gt;.blob        live blob
 *   staging/&lt;id&gt;.stage     uncommitted replacement
 *   staging/&lt;id&gt;.ready     8-byte expected checksum; its atomic rename
 *                            from .committing is the commit marker
 *   staging/&lt;id&gt;.committing
 * </pre>
 * Commit protocol (crash-safe, single atomic switch):
 * <ol>
 *   <li>stage: write &lt;id&gt;.stage + fsync</li>
 *   <li>commit: fsync staged bytes, write &lt;id&gt;.committing(staged crc) +
 *       fsync, rename it to &lt;id&gt;.ready — the durable commit-intent marker</li>
 *   <li>rename .stage -&gt; blobs/&lt;id&gt;.blob (the atomic switch)</li>
 *   <li>delete .ready</li>
 * </ol>
 * On {@link #recover()} a leftover .ready means the commit point was reached:
 * if the live blob already matches the staged crc the rename happened and we
 * only clean up; otherwise the staged file (verified against the crc) is
 * renamed into place. A .stage without .ready is a discarded half-product.
 */
public final class FileBlobStore implements BlobStore {

    private static final String BLOB_SUFFIX = ".blob";
    private static final String STAGE_SUFFIX = ".stage";
    private static final String READY_SUFFIX = ".ready";
    private static final String COMMITTING_SUFFIX = ".committing";

    private final Path blobDir;
    private final Path stagingDir;
    private volatile FaultHook faultHook;

    public FileBlobStore(Path root) throws IOException {
        this.blobDir = root.resolve("blobs");
        this.stagingDir = root.resolve("staging");
        Files.createDirectories(blobDir);
        Files.createDirectories(stagingDir);
    }

    /** Test-only: simulate crashes inside the commit protocol. */
    public void setFaultHook(FaultHook hook) {
        this.faultHook = hook;
    }

    private void maybeCrash(FaultHook.Phase phase, String id) {
        FaultHook hook = faultHook;
        if (hook != null) {
            try {
                hook.onPhase(phase, id);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Path blobPath(String id) {
        return blobDir.resolve(id + BLOB_SUFFIX);
    }

    private Path stagePath(String id) {
        return stagingDir.resolve(id + STAGE_SUFFIX);
    }

    private Path readyPath(String id) {
        return stagingDir.resolve(id + READY_SUFFIX);
    }

    private Path committingPath(String id) {
        return stagingDir.resolve(id + COMMITTING_SUFFIX);
    }

    @Override
    public void put(String id, byte[] blob) throws IOException {
        Path target = blobPath(id);
        Path tmp = blobDir.resolve(id + ".tmp");
        writeDurable(tmp, blob);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("blob already exists: " + id, e);
        }
        fsyncDir(blobDir);
    }

    @Override
    public Optional<byte[]> read(String id) throws IOException {
        Path path = blobPath(id);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(Files.readAllBytes(path));
    }

    @Override
    public OptionalLong checksum(String id) throws IOException {
        Optional<byte[]> blob = read(id);
        if (blob.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(crc32(blob.get()));
    }

    @Override
    public long stageReplacement(String id, byte[] newBlob) throws IOException {
        if (!Files.exists(blobPath(id))) {
            throw new IOException("cannot stage replacement for missing blob: " + id);
        }
        long crc = crc32(newBlob);
        writeDurable(stagePath(id), newBlob);
        return crc;
    }

    @Override
    public boolean commitReplacement(String id, long expectedChecksum) throws IOException {
        OptionalLong current = checksum(id);
        if (current.isEmpty() || current.getAsLong() != expectedChecksum) {
            return false;
        }
        maybeCrash(FaultHook.Phase.BEFORE_COMMIT, id);
        Path stage = stagePath(id);
        if (!Files.exists(stage)) {
            throw new IOException("no staged replacement for " + id);
        }
        // Make the staged bytes durable before the commit-intent marker.
        fsyncFile(stage);
        long stagedCrc = crc32(Files.readAllBytes(stage));
        writeDurable(committingPath(id), ByteBuffer.allocate(Long.BYTES).putLong(stagedCrc).array());
        Files.move(committingPath(id), readyPath(id), StandardCopyOption.ATOMIC_MOVE);
        fsyncDir(stagingDir);
        Files.move(stage, blobPath(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        fsyncDir(blobDir);
        maybeCrash(FaultHook.Phase.AFTER_COMMIT, id);
        Files.deleteIfExists(readyPath(id));
        fsyncDir(stagingDir);
        return true;
    }

    @Override
    public void discardStaged(String id) throws IOException {
        Files.deleteIfExists(stagePath(id));
        Files.deleteIfExists(readyPath(id));
        Files.deleteIfExists(committingPath(id));
        fsyncDir(stagingDir);
    }

    @Override
    public void delete(String id) throws IOException {
        discardStaged(id);
        Files.deleteIfExists(blobPath(id));
        fsyncDir(blobDir);
    }

    @Override
    public List<String> listIds() throws IOException {
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(blobDir, "*" + BLOB_SUFFIX)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                ids.add(name.substring(0, name.length() - BLOB_SUFFIX.length()));
            }
        }
        ids.sort(String::compareTo);
        return ids;
    }

    @Override
    public long size() throws IOException {
        return listIds().size();
    }

    @Override
    public long totalBytes() throws IOException {
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(blobDir, "*" + BLOB_SUFFIX)) {
            for (Path path : stream) {
                total += Files.size(path);
            }
        }
        return total;
    }

    @Override
    public void recover() throws IOException {
        List<String> stagedIds = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.endsWith(STAGE_SUFFIX) || name.endsWith(READY_SUFFIX) || name.endsWith(COMMITTING_SUFFIX)) {
                    String id = name.substring(0, name.lastIndexOf('.'));
                    if (!stagedIds.contains(id)) {
                        stagedIds.add(id);
                    }
                }
            }
        }
        for (String id : stagedIds) {
            recoverOne(id);
        }
    }

    private void recoverOne(String id) throws IOException {
        Path ready = readyPath(id);
        Path stage = stagePath(id);
        if (Files.exists(ready)) {
            long stagedCrc = ByteBuffer.wrap(Files.readAllBytes(ready)).getLong();
            OptionalLong current = checksum(id);
            if (current.isPresent() && current.getAsLong() == stagedCrc) {
                // Rename already happened before the crash: just clean up.
                Files.deleteIfExists(stage);
                Files.deleteIfExists(ready);
            } else if (Files.exists(stage) && crc32(Files.readAllBytes(stage)) == stagedCrc) {
                // Commit point reached but rename not done: finish it now.
                fsyncFile(stage);
                Files.move(stage, blobPath(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                fsyncDir(blobDir);
                Files.deleteIfExists(ready);
            } else {
                // Staged bytes lost or corrupt: keep the old blob, drop staging.
                discardStaged(id);
            }
        } else {
            // No commit marker: the replacement never committed, discard it.
            discardStaged(id);
        }
        Files.deleteIfExists(committingPath(id));
        fsyncDir(stagingDir);
    }

    static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    private static void writeDurable(Path path, byte[] data) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(data));
            channel.force(true);
        }
    }

    private static void fsyncFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void fsyncDir(Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            // Some filesystems do not support directory fsync; best effort.
        }
    }
}
