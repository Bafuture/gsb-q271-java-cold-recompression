package com.example.gsb.recompress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only write-ahead log for recompression runs. One line per event:
 * <pre>
 *   STARTED &lt;id&gt; &lt;oldCrc&gt; &lt;oldBytes&gt; &lt;epochNanos&gt;
 *   STAGED  &lt;id&gt; &lt;newCrc&gt; &lt;newBytes&gt;
 *   DONE    &lt;id&gt; &lt;durationNanos&gt;
 * </pre>
 * After a crash the last event per id tells the recovery logic whether to
 * finish the switch, discard the staged half-product, or do nothing.
 */
public final class RecompressionJournal {

    public enum EventType {STARTED, STAGED, DONE}

    public record Event(EventType type, String id, long arg1, long arg2, long arg3) {
    }

    private final Path file;

    public RecompressionJournal(Path file) {
        this.file = file;
    }

    public synchronized void appendStarted(String id, long oldCrc, long oldBytes) throws IOException {
        append("STARTED " + id + " " + oldCrc + " " + oldBytes + " " + System.nanoTime());
    }

    public synchronized void appendStaged(String id, long newCrc, long newBytes) throws IOException {
        append("STAGED " + id + " " + newCrc + " " + newBytes);
    }

    public synchronized void appendDone(String id, long durationNanos) throws IOException {
        append("DONE " + id + " " + durationNanos);
    }

    private void append(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
    }

    /** All events in file order; missing file means no events. */
    public synchronized List<Event> readAll() throws IOException {
        List<Event> events = new ArrayList<>();
        if (!Files.exists(file)) {
            return events;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split(" ");
            EventType type = EventType.valueOf(parts[0]);
            String id = parts[1];
            long arg1 = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
            long arg2 = parts.length > 3 ? Long.parseLong(parts[3]) : 0;
            long arg3 = parts.length > 4 ? Long.parseLong(parts[4]) : 0;
            events.add(new Event(type, id, arg1, arg2, arg3));
        }
        return events;
    }

    /** Last event per id, preserving first-seen order. */
    public synchronized Map<String, Event> lastEventPerId() throws IOException {
        Map<String, Event> last = new LinkedHashMap<>();
        for (Event event : readAll()) {
            last.put(event.id(), event);
        }
        return last;
    }

    /** Compacts the log; safe because recovery has already been applied. */
    public synchronized void reset() throws IOException {
        Files.deleteIfExists(file);
    }
}
