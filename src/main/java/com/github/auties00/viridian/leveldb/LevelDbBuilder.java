package com.github.auties00.viridian.leveldb;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/// Writes a fresh LevelDB database: a descriptor naming the database's comparator and a write-ahead log holding
/// every entry, which LevelDB replays into its tables when it first opens the database and then keeps using.
///
/// Holding the entries in a log rather than in tables means the builder never orders keys, so it writes a database
/// of any comparator, such as the one Chromium's IndexedDB backend sorts its keys with, without implementing it.
/// The files are those LevelDB itself leaves after creating a database and writing to it before any flush:
///
/// ```text
/// CURRENT            "MANIFEST-000001\n"
/// MANIFEST-000001    one version edit: comparator, log number 3, next file number 4, last sequence number
/// 000003.log         write batches of the entries, numbered from 1
/// ```
///
/// A builder writes the log as entries are put, holding at most one batch in memory; the database exists once
/// [#finish()] writes the descriptor and `CURRENT`, the last file, after forcing the others to the device. A builder
/// closed before then, or whose `finish()` failed, deletes what it wrote, since LevelDB would replay a log it found
/// beside a database it creates.
///
/// @spec https://github.com/google/leveldb/blob/main/doc/log_format.md LevelDB log format
/// @spec https://github.com/google/leveldb/blob/main/doc/impl.md LevelDB implementation notes
public final class LevelDbBuilder implements Closeable {
    /// The name of LevelDB's default comparator, which orders keys by their unsigned bytes.
    public static final String BYTEWISE_COMPARATOR = "leveldb.BytewiseComparator";

    private static final long MANIFEST_NUMBER = 1;
    private static final long LOG_NUMBER = 3;
    private static final int TYPE_VALUE = 1;
    // Version edit tags
    private static final int COMPARATOR = 1;
    private static final int LOG_NUMBER_TAG = 2;
    private static final int NEXT_FILE_NUMBER = 3;
    private static final int LAST_SEQUENCE = 4;
    // A batch is written to the log once it holds this many bytes, the batch size Chromium's IndexedDB backend
    // writes at most
    private static final int BATCH_LIMIT = 1024 * 1024;

    private final Path directory;
    private final String comparatorName;
    private final FileChannel logChannel;
    private final OutputStream logOutput;
    private final LevelDbLogWriter log;
    private final ByteArrayOutputStream batch = new ByteArrayOutputStream();
    private int batchCount;
    private long sequence;
    // Whether the log is closed, and whether the database is complete
    private boolean closed;
    private boolean finished;

    private LevelDbBuilder(Path directory, String comparatorName, FileChannel logChannel) {
        this.directory = directory;
        this.comparatorName = comparatorName;
        this.logChannel = logChannel;
        this.logOutput = new BufferedOutputStream(Channels.newOutputStream(logChannel), 64 * 1024);
        this.log = new LevelDbLogWriter(logOutput);
    }

    /// {@return a builder of a fresh database in `directory`, which it creates if needed}
    ///
    /// @param comparatorName the name of the comparator the database's keys are ordered by, which LevelDB checks
    ///                       against the one it opens the database with: [#BYTEWISE_COMPARATOR] for the default
    /// @throws DirectoryNotEmptyException if `directory` exists and holds any file
    /// @throws IOException                if the directory or the log cannot be created
    public static LevelDbBuilder create(Path directory, String comparatorName) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(comparatorName, "comparatorName");
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            if (files.findAny().isPresent()) {
                throw new DirectoryNotEmptyException(directory.toString());
            }
        }
        var channel = FileChannel.open(directory.resolve(logName()), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new LevelDbBuilder(directory, comparatorName, channel);
    }

    /// Writes a fresh database holding `entries` in `directory`, which it creates if needed.
    ///
    /// Two entries with the same key leave the later one's value.
    ///
    /// @param comparatorName the name of the comparator the database's keys are ordered by, which LevelDB checks
    ///                       against the one it opens the database with: [#BYTEWISE_COMPARATOR] for the default
    /// @throws DirectoryNotEmptyException if `directory` exists and holds any file
    /// @throws IOException                if a file cannot be written
    public static void write(Path directory, String comparatorName, List<LevelDb.Entry> entries) throws IOException {
        try (var builder = create(directory, comparatorName)) {
            for (var entry : entries) {
                builder.put(entry.key(), entry.value());
            }
            builder.finish();
        }
    }

    /// Puts `value` under `key`; a later put of the same key replaces it.
    ///
    /// The builder keeps neither array, so the caller may reuse them.
    ///
    /// @throws IllegalStateException if the builder is finished or closed
    /// @throws IOException           if the log cannot be written
    public void put(byte[] key, byte[] value) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        ensureOpen();
        batch.write(TYPE_VALUE);
        writeVarint(batch, key.length);
        batch.write(key, 0, key.length);
        writeVarint(batch, value.length);
        batch.write(value, 0, value.length);
        batchCount++;
        if (batch.size() >= BATCH_LIMIT) {
            flushBatch();
        }
    }

    /// Writes the descriptor and `CURRENT`, after forcing the log to the device, and closes the builder: the
    /// directory then holds the database.
    ///
    /// @throws IllegalStateException if the builder is finished or closed
    /// @throws IOException           if a file cannot be written; [#close()] then deletes what the builder wrote
    public void finish() throws IOException {
        ensureOpen();
        flushBatch();
        logOutput.flush();
        logChannel.force(true);
        closed = true;
        logChannel.close();

        var edit = new ByteArrayOutputStream();
        writeVarint(edit, COMPARATOR);
        var comparator = comparatorName.getBytes(StandardCharsets.UTF_8);
        writeVarint(edit, comparator.length);
        edit.writeBytes(comparator);
        writeVarint(edit, LOG_NUMBER_TAG);
        writeVarint(edit, LOG_NUMBER);
        writeVarint(edit, NEXT_FILE_NUMBER);
        writeVarint(edit, LOG_NUMBER + 1);
        writeVarint(edit, LAST_SEQUENCE);
        writeVarint(edit, sequence);
        var descriptor = new ByteArrayOutputStream();
        var descriptorLog = new LevelDbLogWriter(descriptor);
        var record = edit.toByteArray();
        descriptorLog.addRecord(record, 0, record.length);
        var manifestName = String.format("MANIFEST-%06d", MANIFEST_NUMBER);
        writeForced(directory.resolve(manifestName), descriptor.toByteArray());

        // LevelDB points CURRENT at a descriptor by renaming a temporary file over it, which it names this way
        var temporary = directory.resolve(String.format("%06d.dbtmp", LOG_NUMBER + 1));
        writeForced(temporary, (manifestName + "\n").getBytes(StandardCharsets.US_ASCII));
        Files.move(temporary, directory.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE);
        finished = true;
    }

    /// Abandons the database if [#finish()] has not written it, deleting the files the builder wrote; does nothing
    /// otherwise.
    @Override
    public void close() throws IOException {
        if (finished) {
            return;
        }
        closed = true;
        logChannel.close();
        Files.deleteIfExists(directory.resolve(String.format("%06d.dbtmp", LOG_NUMBER + 1)));
        Files.deleteIfExists(directory.resolve(String.format("MANIFEST-%06d", MANIFEST_NUMBER)));
        Files.deleteIfExists(directory.resolve(logName()));
    }

    private void flushBatch() throws IOException {
        if (batchCount == 0) {
            return;
        }
        // The batch header: the sequence number of its first operation and how many operations it holds
        var operations = batch.toByteArray();
        var record = new byte[12 + operations.length];
        var first = sequence + 1;
        for (var i = 0; i < 8; i++) {
            record[i] = (byte) (first >>> (8 * i));
        }
        for (var i = 0; i < 4; i++) {
            record[8 + i] = (byte) (batchCount >>> (8 * i));
        }
        System.arraycopy(operations, 0, record, 12, operations.length);
        log.addRecord(record, 0, record.length);
        sequence += batchCount;
        batch.reset();
        batchCount = 0;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("The builder is finished or closed");
        }
    }

    private static String logName() {
        return String.format("%06d.log", LOG_NUMBER);
    }

    private static void writeForced(Path file, byte[] data) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        while ((value & ~0x7FL) != 0) {
            output.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write((int) value);
    }
}
