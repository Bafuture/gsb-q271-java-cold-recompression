package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class FileBlobStoreTest {

    @TempDir
    Path dir;

    private FileBlobStore newStore() throws Exception {
        return new FileBlobStore(dir);
    }

    @Test
    void putReadDelete() throws Exception {
        FileBlobStore store = newStore();
        store.put("a", new byte[]{1, 2, 3});
        assertThat(store.read("a")).contains(new byte[]{1, 2, 3});
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.totalBytes()).isEqualTo(3);
        store.delete("a");
        assertThat(store.read("a")).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void commitReplacementIsAtomicAndChecksumGuarded() throws Exception {
        FileBlobStore store = newStore();
        store.put("a", new byte[]{1, 1, 1});
        long oldCrc = store.checksum("a").orElseThrow();

        store.stageReplacement("a", new byte[]{2, 2, 2, 2});
        // staged bytes are not visible to readers before commit
        assertThat(store.read("a")).contains(new byte[]{1, 1, 1});

        // wrong expected checksum -> no commit
        assertThat(store.commitReplacement("a", oldCrc + 1)).isFalse();
        assertThat(store.read("a")).contains(new byte[]{1, 1, 1});

        assertThat(store.commitReplacement("a", oldCrc)).isTrue();
        assertThat(store.read("a")).contains(new byte[]{2, 2, 2, 2});
    }

    @Test
    void crashBeforeCommitDiscardsStagedHalfProduct() throws Exception {
        FileBlobStore store = newStore();
        store.put("a", new byte[]{1, 1, 1});
        long oldCrc = store.checksum("a").orElseThrow();
        store.stageReplacement("a", new byte[]{2, 2, 2});
        store.setFaultHook((phase, id) -> {
            if (phase == FaultHook.Phase.BEFORE_COMMIT) {
                throw new RuntimeException("simulated crash");
            }
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.commitReplacement("a", oldCrc))
                .hasMessageContaining("simulated crash");

        // restart
        FileBlobStore recovered = newStore();
        recovered.recover();
        assertThat(recovered.read("a")).contains(new byte[]{1, 1, 1});
        assertThat(Files.list(dir.resolve("staging")).count()).isZero();
    }

    @Test
    void crashAfterCommitCompletesSwitchOnRecovery() throws Exception {
        FileBlobStore store = newStore();
        store.put("a", new byte[]{1, 1, 1});
        long oldCrc = store.checksum("a").orElseThrow();
        store.stageReplacement("a", new byte[]{2, 2, 2});
        AtomicBoolean crashed = new AtomicBoolean();
        store.setFaultHook((phase, id) -> {
            if (phase == FaultHook.Phase.AFTER_COMMIT) {
                crashed.set(true);
                throw new RuntimeException("simulated crash");
            }
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.commitReplacement("a", oldCrc));
        assertThat(crashed).isTrue();

        FileBlobStore recovered = newStore();
        recovered.recover();
        assertThat(recovered.read("a")).contains(new byte[]{2, 2, 2});
        assertThat(Files.list(dir.resolve("staging")).count()).isZero();
    }

    @Test
    void corruptStagedFileIsDiscardedOnRecovery() throws Exception {
        FileBlobStore store = newStore();
        store.put("a", new byte[]{1, 1, 1});
        store.stageReplacement("a", new byte[]{2, 2, 2});
        // corrupt the staged bytes before any commit marker exists
        Files.write(dir.resolve("staging").resolve("a.stage"), new byte[]{9, 9});

        FileBlobStore recovered = newStore();
        recovered.recover();
        assertThat(recovered.read("a")).contains(new byte[]{1, 1, 1});
    }

    @Test
    void listIdsExcludesStagingFiles() throws Exception {
        FileBlobStore store = newStore();
        store.put("a", new byte[]{1});
        store.put("b", new byte[]{2});
        store.stageReplacement("a", new byte[]{3});
        assertThat(store.listIds()).containsExactly("a", "b");
    }
}
