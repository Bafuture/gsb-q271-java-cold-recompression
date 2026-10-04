package com.example.gsb.recompress;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Content-addressed-ish blob storage with transactional replacement.
 * A replacement is staged to a separate file and committed with a single
 * atomic rename, so readers always see either the complete old blob or the
 * complete new blob, never a half-written one.
 */
public interface BlobStore {

    /** Writes a new blob. Fails if the id already exists. */
    void put(String id, byte[] blob) throws IOException;

    /** Reads the current blob, or empty if the id is unknown. */
    Optional<byte[]> read(String id) throws IOException;

    /** CRC32 of the current blob, or empty if the id is unknown. */
    OptionalLong checksum(String id) throws IOException;

    /**
     * Atomically replaces the blob of {@code id} with the previously staged
     * content, but only if the current blob still matches {@code expectedChecksum}.
     *
     * @return true if the replacement was committed, false if the current
     *         content no longer matches (concurrent modification)
     */
    boolean commitReplacement(String id, long expectedChecksum) throws IOException;

    /** Stages bytes for a later {@link #commitReplacement}. Returns the staged CRC32. */
    long stageReplacement(String id, byte[] newBlob) throws IOException;

    /** Removes any staged (uncommitted) replacement for {@code id}. */
    void discardStaged(String id) throws IOException;

    /** Deletes the blob and any staged replacement. */
    void delete(String id) throws IOException;

    /** All live blob ids (staged files excluded). */
    List<String> listIds() throws IOException;

    /** Number of live blobs. */
    long size() throws IOException;

    /** Total bytes of all live blobs. */
    long totalBytes() throws IOException;

    /**
     * Crash recovery: completes replacements whose commit rename already
     * happened, discards staged leftovers of interrupted ones. Must be called
     * once after startup before serving traffic.
     */
    void recover() throws IOException;
}
