package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbCursor;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.IdbRecord;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsValue;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// The [IdbCursor] of both backends, over the records a backend reads in key order.
public final class StoredCursor implements IdbCursor {
    private final Records records;
    private final IdbObjectStore store;
    private StoredRecord current;
    private boolean done;

    /// Creates a cursor over `records`, the records of `store`.
    public StoredCursor(Records records, IdbObjectStore store) {
        this.records = records;
        this.store = store;
    }

    @Override
    public boolean next() throws IOException {
        if (done) {
            return false;
        }
        current = records.next();
        if (current == null) {
            done = true;
        }
        return current != null;
    }

    @Override
    public IdbKey key() {
        return current().key();
    }

    @Override
    public JsValue value() throws IOException {
        return current().value(store);
    }

    @Override
    public byte[] serializedValue() throws IOException {
        return current().serializedValue();
    }

    @Override
    public List<JsBlob> blobs() {
        return current().valueBlobs();
    }

    /// {@return the current record}
    StoredRecord current() {
        if (current == null) {
            throw new IllegalStateException("The cursor is not at a record");
        }
        return current;
    }

    @Override
    public void close() throws IOException {
        done = true;
        current = null;
        records.close();
    }

    /// {@return the records of `cursor` as a stream of decoded records, which closes the cursor when it is closed}
    static Stream<IdbRecord> stream(StoredCursor cursor) {
        var iterator = new Iterator<IdbRecord>() {
            private IdbRecord next;

            @Override
            public boolean hasNext() {
                if (next == null) {
                    try {
                        if (cursor.next()) {
                            next = new IdbRecord(cursor.key(), cursor.value());
                        }
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }
                return next != null;
            }

            @Override
            public IdbRecord next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                var result = next;
                next = null;
                return result;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(() -> {
                    try {
                        cursor.close();
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
    }

    /// {@return a [DatabaseSource.RecordCursor] over `cursor`, giving each value as stored}
    static DatabaseSource.RecordCursor stored(StoredCursor cursor) {
        return new DatabaseSource.RecordCursor() {
            @Override
            public boolean next() throws IOException {
                return cursor.next();
            }

            @Override
            public IdbKey key() {
                return cursor.key();
            }

            @Override
            public JsValue value() throws IOException {
                return cursor.current().storedValue();
            }

            @Override
            public void close() throws IOException {
                cursor.close();
            }
        };
    }

    /// The records of a store as a backend reads them, in key order.
    public interface Records extends Closeable {
        /// {@return the next record, or `null` past the last}
        ///
        /// @throws IOException if it cannot be read
        StoredRecord next() throws IOException;
    }
}
