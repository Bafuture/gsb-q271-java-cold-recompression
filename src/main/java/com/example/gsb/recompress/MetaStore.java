package com.example.gsb.recompress;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists {@link RecordMeta} as one small properties-like file per record,
 * written atomically (tmp file + rename). Format:
 * <pre>
 *   id=&lt;id&gt;
 *   tier=HOT|COLD
 *   createdAt=&lt;epochMilli&gt;
 *   lastAccessAt=&lt;epochMilli&gt;
 *   accesses=&lt;comma separated epochMillis&gt;
 * </pre>
 */
public final class MetaStore {

    private static final String SUFFIX = ".meta";

    private final Path dir;

    public MetaStore(Path root) throws IOException {
        this.dir = root.resolve("meta");
        Files.createDirectories(dir);
    }

    private Path path(String id) {
        return dir.resolve(id + SUFFIX);
    }

    public synchronized void save(RecordMeta meta) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(meta.id()).append('\n');
        sb.append("tier=").append(meta.tier()).append('\n');
        sb.append("createdAt=").append(meta.createdAt().toEpochMilli()).append('\n');
        sb.append("lastAccessAt=").append(meta.lastAccessAt().toEpochMilli()).append('\n');
        StringBuilder accesses = new StringBuilder();
        for (Instant access : meta.accessSnapshot()) {
            if (accesses.length() > 0) {
                accesses.append(',');
            }
            accesses.append(access.toEpochMilli());
        }
        sb.append("accesses=").append(accesses).append('\n');

        Path tmp = dir.resolve(meta.id() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.READ)) {
            channel.force(true);
        }
        Files.move(tmp, path(meta.id()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized Optional<RecordMeta> load(String id) throws IOException {
        Path path = path(id);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Map<String, String> props = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        RecordMeta meta = new RecordMeta(
                props.get("id"),
                Tier.valueOf(props.get("tier")),
                Instant.ofEpochMilli(Long.parseLong(props.get("createdAt"))));
        List<Instant> accesses = new ArrayList<>();
        String accessList = props.getOrDefault("accesses", "");
        if (!accessList.isEmpty()) {
            for (String part : accessList.split(",")) {
                accesses.add(Instant.ofEpochMilli(Long.parseLong(part)));
            }
        }
        meta.restoreAccesses(accesses);
        meta.restoreLastAccess(Instant.ofEpochMilli(Long.parseLong(props.get("lastAccessAt"))));
        return Optional.of(meta);
    }

    public synchronized List<RecordMeta> loadAll() throws IOException {
        List<RecordMeta> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                load(name.substring(0, name.length() - SUFFIX.length())).ifPresent(result::add);
            }
        }
        return result;
    }

    public synchronized void delete(String id) throws IOException {
        Files.deleteIfExists(path(id));
    }
}
