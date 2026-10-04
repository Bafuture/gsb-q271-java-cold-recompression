package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecompressionJournalTest {

    @TempDir
    Path dir;

    @Test
    void eventsSurviveReopen() throws Exception {
        Path file = dir.resolve("journal.log");
        RecompressionJournal journal = new RecompressionJournal(file);
        journal.appendStarted("a", 111L, 1000L);
        journal.appendStaged("a", 222L, 700L);
        journal.appendStarted("b", 333L, 2000L);

        RecompressionJournal reopened = new RecompressionJournal(file);
        Map<String, RecompressionJournal.Event> last = reopened.lastEventPerId();
        assertThat(last.get("a").type()).isEqualTo(RecompressionJournal.EventType.STAGED);
        assertThat(last.get("a").arg1()).isEqualTo(222L);
        assertThat(last.get("b").type()).isEqualTo(RecompressionJournal.EventType.STARTED);
        assertThat(last.get("b").arg2()).isEqualTo(2000L);
    }

    @Test
    void doneIsTerminalEvent() throws Exception {
        Path file = dir.resolve("journal.log");
        RecompressionJournal journal = new RecompressionJournal(file);
        journal.appendStarted("a", 1L, 10L);
        journal.appendStaged("a", 2L, 8L);
        journal.appendDone("a", 123456L);
        assertThat(journal.lastEventPerId().get("a").type())
                .isEqualTo(RecompressionJournal.EventType.DONE);
    }

    @Test
    void missingFileMeansNoEvents() throws Exception {
        RecompressionJournal journal = new RecompressionJournal(dir.resolve("nope.log"));
        assertThat(journal.readAll()).isEmpty();
    }
}
