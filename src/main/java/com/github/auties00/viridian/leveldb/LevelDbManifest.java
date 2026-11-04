package com.github.auties00.viridian.leveldb;

import com.github.auties00.viridian.io.ByteCursor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/// Reads a LevelDB descriptor: the version edits that, replayed in order, give the live table files and logs.
///
/// An edit is a sequence of tagged fields: 1 comparator name, 2 log number, 3 next file number, 4 last sequence,
/// 5 compaction pointer, 6 deleted file, 7 new file, 9 previous log number.
final class LevelDbManifest {
    private LevelDbManifest() {
    }

    /// {@return the state the descriptor at `path` describes}
    ///
    /// @throws IOException if the descriptor cannot be read, or an edit does not decode
    static LevelDb.ManifestState read(Path path) throws IOException {
        var live = new LinkedHashSet<Long>();
        var logNumber = new long[]{0};
        var previousLogNumber = new long[]{-1};
        LevelDbLog.readRecords(path, true, edit -> {
            var input = new ByteCursor(edit);
            while (input.hasRemaining()) {
                var tag = input.varintLength();
                switch (tag) {
                    case 1 -> input.lengthPrefixed();
                    case 2 -> logNumber[0] = input.varint64();
                    case 3, 4 -> input.varint64();
                    case 5 -> {
                        input.varint64();
                        input.lengthPrefixed();
                    }
                    case 6 -> {
                        input.varint64();
                        live.remove(input.varint64());
                    }
                    case 7 -> {
                        input.varint64();
                        live.add(input.varint64());
                        // The file size and its smallest and largest keys
                        input.varint64();
                        input.lengthPrefixed();
                        input.lengthPrefixed();
                    }
                    case 9 -> previousLogNumber[0] = input.varint64();
                    default -> throw new IOException("The descriptor has a version edit field of unknown tag " + tag);
                }
            }
        });
        return new LevelDb.ManifestState(new ArrayList<>(live), logNumber[0], previousLogNumber[0]);
    }
}
