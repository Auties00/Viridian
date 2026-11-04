package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.util.BitSet;

/// A position in a table or index b-tree, moved by seeking and stepping, with the pages from the root to it on a
/// stack.
///
/// A b-tree page starts with a header, 100 bytes in on page 1, followed by an array of 2-byte cell offsets:
///
/// ```text
/// 0  page type: 2 index interior, 5 table interior, 10 index leaf, 13 table leaf
/// 1  first freeblock          5  start of the cell content area
/// 3  number of cells          7  fragmented free bytes
/// 8  rightmost child page, interior pages only
/// ```
///
/// A table b-tree keeps its rows in its leaves, keyed by rowid; its interior cells are a child page and the largest
/// rowid in that child. An index b-tree, which also holds a `WITHOUT ROWID` table, keeps an entry in every cell,
/// interior ones included, so an in-order walk visits each interior cell between its left child and the next child:
///
/// ```text
/// table leaf      payload size varint, rowid varint, payload, overflow page if the payload overflows
/// table interior  left child page, rowid varint
/// index leaf      payload size varint, payload, overflow page if the payload overflows
/// index interior  left child page, payload size varint, payload, overflow page if the payload overflows
/// ```
///
/// A payload larger than its page allows keeps a prefix on the page and the rest in a chain of overflow pages, each a
/// 4-byte next-page number followed by `U - 4` bytes, `U` being the usable page size. With `P` the payload size, the
/// prefix is all of `P` up to `X`, which is `U - 35` for a table leaf and `((U - 12) * 64 / 255) - 23` for an index
/// cell; above it, with `M = ((U - 12) * 32 / 255) - 23` and `K = M + ((P - M) % (U - 4))`, it is `K` if `K <= X`,
/// else `M`.
///
/// Every page a traversal enters is checked: its type must belong to the tree, its cells must lie in its content
/// area, and it must not have been entered before by the same traversal, so a tree that loops fails instead of
/// running forever; the depth is capped at SQLite's own limit. Overflow chains are bounded by the payload size and
/// checked for repeated pages the same way. Overflow payloads are assembled only when a row is read, or when a seek
/// must compare more of an index entry than its page holds.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
final class SqliteBTreeCursor {
    /// The deepest b-tree SQLite reads, counting the root.
    static final int MAX_DEPTH = 20;

    /// The type byte of an index b-tree interior page.
    private static final int INDEX_INTERIOR = 2;

    /// The type byte of a table b-tree interior page.
    private static final int TABLE_INTERIOR = 5;

    /// The type byte of an index b-tree leaf page.
    private static final int INDEX_LEAF = 10;

    /// The type byte of a table b-tree leaf page.
    private static final int TABLE_LEAF = 13;

    /// A page on the cursor's stack.
    private static final class Frame {
        /// The page number.
        private int page;

        /// The page's content.
        private byte[] data;

        /// The page type.
        private int type;

        /// The number of cells.
        private int cells;

        /// The offset of the cell pointer array.
        private int pointers;

        /// The rightmost child, for an interior page.
        private long rightChild;

        /// On a leaf, the current cell; on an interior page, the child the cursor is in, from 0 to [#cells], where
        /// [#cells] is the rightmost child.
        private int index;

        /// Whether the cursor is on the interior cell [#index] itself rather than in the child before it.
        private boolean onCell;

        /// {@return whether the page is a leaf}
        private boolean leaf() {
            return type == TABLE_LEAF || type == INDEX_LEAF;
        }
    }

    /// The pages of the database.
    private final SqlitePager pager;

    /// The text encoding, which seek keys are compared in.
    private final SqliteTextEncoding encoding;

    /// The root page.
    private final int root;

    /// Whether the tree is a table b-tree rather than an index b-tree.
    private final boolean table;

    /// The usable size of a page.
    private final int usable;

    /// The largest payload a cell may declare: no more than the database holds, and small enough for an array.
    private final long maxPayload;

    /// The pages from the root to the current position.
    private final Frame[] stack = new Frame[MAX_DEPTH];

    /// The pages this traversal has entered, or `null` for a cursor that only descends once, which checks its stack.
    private final BitSet visited;

    /// The number of pages on the stack.
    private int depth;

    /// Whether the cursor is on an entry.
    private boolean valid;

    /// The page holding the last parsed cell.
    private byte[] cellData;

    /// The page number of the last parsed cell.
    private int cellPage;

    /// The index of the last parsed cell on its page.
    private int cellIndex;

    /// The left child of the last parsed interior cell.
    private long cellChild;

    /// The rowid of the last parsed table cell.
    private long cellRowid;

    /// The payload size of the last parsed cell.
    private long payloadSize;

    /// The offset of the payload's on-page part.
    private int payloadOffset;

    /// The size of the payload's on-page part.
    private int localSize;

    /// The first overflow page of the payload, or 0.
    private long overflowPage;

    /// Creates a cursor that is on no entry yet.
    ///
    /// @param traversal whether the cursor walks the tree, tracking every page it enters, rather than only descending
    ///                  to look a rowid up
    /// @throws IOException if `root` is not a page of the database
    SqliteBTreeCursor(SqlitePager pager, long root, boolean table, boolean traversal) throws IOException {
        if (root < 1 || root > pager.pageCount()) {
            throw new IOException("The b-tree root page " + root + " is out of range: the database has " + pager.pageCount() + " pages");
        }
        this.pager = pager;
        this.encoding = pager.textEncoding();
        this.root = (int) root;
        this.table = table;
        this.usable = pager.usableSize();
        this.maxPayload = Math.min(Integer.MAX_VALUE - 16L, (long) pager.pageCount() * usable);
        this.visited = traversal ? new BitSet() : null;
    }

    /// Moves to the first entry.
    ///
    /// @return whether the tree has one
    boolean first() throws IOException {
        depth = 0;
        descendLeftmost(root);
        return settle();
    }

    /// Moves to the first row whose rowid is at least `rowid`, in a table b-tree.
    ///
    /// @return whether there is one
    boolean seekRowid(long rowid) throws IOException {
        depth = 0;
        var page = (long) root;
        while (true) {
            var frame = push(page);
            frame.index = lowerBound(frame, rowid);
            if (frame.leaf()) {
                return settle();
            }
            page = child(frame, frame.index);
        }
    }

    /// Moves to the first entry whose first `key.size()` fields compare at least equal to `key`, in an index b-tree.
    ///
    /// @return whether there is one
    boolean seek(SqliteKey key) throws IOException {
        depth = 0;
        var page = (long) root;
        while (true) {
            var frame = push(page);
            frame.index = lowerBound(frame, key);
            if (frame.leaf()) {
                return settle();
            }
            page = child(frame, frame.index);
        }
    }

    /// Moves to the row with `rowid`, in a table b-tree, by one descent.
    ///
    /// If the row exists it is in the leaf the descent reaches, since an interior cell's rowid bounds every rowid in
    /// its child.
    ///
    /// @return whether the row exists
    boolean find(long rowid) throws IOException {
        depth = 0;
        valid = false;
        var page = (long) root;
        while (true) {
            var frame = push(page);
            var index = lowerBound(frame, rowid);
            if (frame.leaf()) {
                if (index < frame.cells) {
                    cell(frame, index);
                    if (cellRowid == rowid) {
                        frame.index = index;
                        valid = true;
                    }
                }
                return valid;
            }
            frame.index = index;
            page = child(frame, index);
        }
    }

    /// Moves to the next entry in b-tree order.
    ///
    /// @return whether there is one
    boolean next() throws IOException {
        if (!valid) {
            return false;
        }
        var frame = stack[depth - 1];
        frame.index++;
        if (!frame.leaf()) {
            frame.onCell = false;
            descendLeftmost(child(frame, frame.index));
        }
        return settle();
    }

    /// {@return the rowid of the current row of a table b-tree}
    long rowid() throws IOException {
        var frame = stack[depth - 1];
        cell(frame, frame.index);
        return cellRowid;
    }

    /// {@return the record of the current entry, with its overflow pages assembled}
    SqliteRecord record() throws IOException {
        var frame = stack[depth - 1];
        cell(frame, frame.index);
        var data = cellData;
        var start = payloadOffset;
        if (localSize < payloadSize) {
            data = payload(payloadSize);
            start = 0;
        }
        try {
            return SqliteRecord.parse(data, start, (int) payloadSize);
        } catch (IOException malformed) {
            throw new IOException("Cell " + cellIndex + " of page " + cellPage + " is malformed: " + malformed.getMessage(), malformed);
        }
    }

    /// {@return the number of pages from the root to the current entry}
    int depth() {
        return depth;
    }

    /// Pushes the pages from `page` down to the leftmost leaf below it, each at its first child or cell.
    private void descendLeftmost(long page) throws IOException {
        while (true) {
            var frame = push(page);
            frame.index = 0;
            if (frame.leaf()) {
                return;
            }
            page = child(frame, 0);
        }
    }

    /// Moves up from exhausted pages until the cursor is on an entry or past the last one.
    ///
    /// An interior page at the top of the stack has just had its child [Frame#index] exhausted. In an index b-tree
    /// the next entry is that page's cell at the same index; in a table b-tree it is in the next child.
    ///
    /// @return whether the cursor is on an entry
    private boolean settle() throws IOException {
        while (depth > 0) {
            var frame = stack[depth - 1];
            if (frame.leaf()) {
                if (frame.index < frame.cells) {
                    return valid = true;
                }
                depth--;
                continue;
            }
            if (frame.index < frame.cells) {
                if (!table) {
                    frame.onCell = true;
                    return valid = true;
                }
                frame.index++;
                descendLeftmost(child(frame, frame.index));
                continue;
            }
            depth--;
        }
        return valid = false;
    }

    /// Reads page `number` onto the top of the stack.
    ///
    /// @throws IOException if the page is out of range, already entered, too deep, of the wrong kind or malformed
    private Frame push(long number) throws IOException {
        if (depth == MAX_DEPTH) {
            throw new IOException("The b-tree rooted at page " + root + " is more than " + MAX_DEPTH + " levels deep, so it loops or is corrupt");
        }
        if (number < 1 || number > pager.pageCount()) {
            throw new IOException("The b-tree rooted at page " + root + " points to page " + number + ", but the database has "
                    + pager.pageCount() + " pages");
        }
        var page = (int) number;
        if (visited != null) {
            if (visited.get(page)) {
                throw new IOException("The b-tree rooted at page " + root + " reaches page " + page + " twice");
            }
            visited.set(page);
        } else {
            for (var i = 0; i < depth; i++) {
                if (stack[i].page == page) {
                    throw new IOException("The b-tree rooted at page " + root + " loops through page " + page);
                }
            }
        }
        var frame = stack[depth];
        if (frame == null) {
            frame = stack[depth] = new Frame();
        }
        var data = pager.page(page);
        var header = page == 1 ? 100 : 0;
        var type = SqliteBytes.u8(data, header);
        var tablePage = switch (type) {
            case TABLE_INTERIOR, TABLE_LEAF -> true;
            case INDEX_INTERIOR, INDEX_LEAF -> false;
            default -> throw new IOException("Page " + page + " of the b-tree rooted at page " + root + " is not a b-tree page: its type is " + type);
        };
        if (tablePage != table) {
            throw new IOException("Page " + page + " is " + (tablePage ? "a table" : "an index") + " b-tree page inside the "
                    + (table ? "table" : "index") + " b-tree rooted at page " + root);
        }
        frame.page = page;
        frame.data = data;
        frame.type = type;
        frame.cells = SqliteBytes.u16(data, header + 3);
        frame.pointers = header + (frame.leaf() ? 8 : 12);
        if (frame.pointers + 2L * frame.cells > usable) {
            throw new IOException("Page " + page + " declares " + frame.cells + " cells, more than fit in it");
        }
        frame.rightChild = frame.leaf() ? 0 : SqliteBytes.u32(data, header + 8);
        frame.index = 0;
        frame.onCell = false;
        depth++;
        return frame;
    }

    /// {@return the child page of `frame` at `index`: the left child of cell `index`, or the rightmost child}
    private long child(Frame frame, int index) throws IOException {
        if (index == frame.cells) {
            return frame.rightChild;
        }
        return SqliteBytes.u32(frame.data, cellOffset(frame, index));
    }

    /// {@return the index of the first cell of a table b-tree page whose rowid is at least `rowid`, or the number of
    /// cells}
    private int lowerBound(Frame frame, long rowid) throws IOException {
        var low = 0;
        var high = frame.cells;
        while (low < high) {
            var middle = (low + high) >>> 1;
            cell(frame, middle);
            if (cellRowid < rowid) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /// {@return the index of the first cell of an index b-tree page that compares at least equal to `key`, or the
    /// number of cells}
    private int lowerBound(Frame frame, SqliteKey key) throws IOException {
        var low = 0;
        var high = frame.cells;
        while (low < high) {
            var middle = (low + high) >>> 1;
            cell(frame, middle);
            if (compare(key) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /// {@return the sign of comparing the last parsed cell's entry with `key`}
    ///
    /// The comparison reads the entry from its page when the compared fields lie there, and otherwise assembles only
    /// as much of the overflow chain as they need.
    private int compare(SqliteKey key) throws IOException {
        var data = cellData;
        var start = payloadOffset;
        var available = localSize;
        while (true) {
            long needed;
            try {
                needed = SqliteRecord.prefixLength(data, start, available, payloadSize, key.size());
            } catch (IOException malformed) {
                throw new IOException("Cell " + cellIndex + " of page " + cellPage + " is malformed: " + malformed.getMessage(), malformed);
            }
            if (needed <= available) {
                return SqliteRecord.comparePrefix(data, start, key);
            }
            if (available == payloadSize) {
                throw new IOException("Cell " + cellIndex + " of page " + cellPage + " is malformed: its fields need more than its payload");
            }
            data = payload(needed);
            start = 0;
            available = data.length;
        }
    }

    /// {@return the offset of cell `index` of `frame`}
    ///
    /// @throws IOException if the offset lies outside the page's cell content area
    private int cellOffset(Frame frame, int index) throws IOException {
        var offset = SqliteBytes.u16(frame.data, frame.pointers + 2 * index);
        if (offset < frame.pointers + 2 * frame.cells || offset > usable - 4) {
            throw new IOException("Cell " + index + " of page " + frame.page + " starts at offset " + offset
                    + ", outside the page's cell content area");
        }
        return offset;
    }

    /// Parses cell `index` of `frame` into the cell fields.
    ///
    /// @throws IOException if the cell runs past the usable part of its page or declares an impossible payload
    private void cell(Frame frame, int index) throws IOException {
        var data = frame.data;
        var position = cellOffset(frame, index);
        cellData = data;
        cellPage = frame.page;
        cellIndex = index;
        if (!frame.leaf()) {
            cellChild = SqliteBytes.u32(data, position);
            position += 4;
        }
        if (frame.type == TABLE_INTERIOR) {
            var end = SqliteBytes.varintEnd(data, position, usable);
            if (end < 0) {
                throw truncated(frame, index);
            }
            cellRowid = SqliteBytes.varint(data, position);
            return;
        }
        var sizeEnd = SqliteBytes.varintEnd(data, position, usable);
        if (sizeEnd < 0) {
            throw truncated(frame, index);
        }
        payloadSize = SqliteBytes.varint(data, position);
        position = sizeEnd;
        if (frame.type == TABLE_LEAF) {
            var rowidEnd = SqliteBytes.varintEnd(data, position, usable);
            if (rowidEnd < 0) {
                throw truncated(frame, index);
            }
            cellRowid = SqliteBytes.varint(data, position);
            position = rowidEnd;
        }
        if (payloadSize < 0 || payloadSize > maxPayload) {
            throw new IOException("Cell " + index + " of page " + frame.page + " declares a payload of "
                    + Long.toUnsignedString(payloadSize) + " bytes, more than the database holds");
        }
        localSize = localSize(payloadSize, frame.type == TABLE_LEAF);
        payloadOffset = position;
        var end = (long) position + localSize + (localSize < payloadSize ? 4 : 0);
        if (end > usable) {
            throw truncated(frame, index);
        }
        overflowPage = localSize < payloadSize ? SqliteBytes.u32(data, position + localSize) : 0;
    }

    /// {@return an exception reporting that cell `index` of `frame` runs past the usable part of its page}
    private static IOException truncated(Frame frame, int index) {
        return new IOException("Cell " + index + " of page " + frame.page + " runs past the end of the page's usable space");
    }

    /// {@return how many bytes of a payload of `size` bytes its cell keeps on the page}
    private int localSize(long size, boolean tableLeaf) {
        var maxLocal = tableLeaf ? usable - 35 : (usable - 12) * 64 / 255 - 23;
        if (size <= maxLocal) {
            return (int) size;
        }
        var minLocal = (usable - 12) * 32 / 255 - 23;
        var surplus = minLocal + (size - minLocal) % (usable - 4);
        return (int) (surplus <= maxLocal ? surplus : minLocal);
    }

    /// {@return the first `limit` bytes of the last parsed cell's payload, or all of it if it is shorter}
    ///
    /// @throws IOException if the overflow chain ends early, leaves the database or repeats a page
    private byte[] payload(long limit) throws IOException {
        var length = (int) Math.min(limit, payloadSize);
        var payload = new byte[length];
        var copied = Math.min(localSize, length);
        System.arraycopy(cellData, payloadOffset, payload, 0, copied);
        var next = overflowPage;
        var chain = 0;
        BitSet seen = null;
        while (copied < length) {
            if (next < 2 || next > pager.pageCount()) {
                throw new IOException("The overflow chain of cell " + cellIndex + " of page " + cellPage + " "
                        + (next == 0 ? "ends" : "points to page " + next) + " after " + chain + " pages, "
                        + (payloadSize - copied) + " bytes short of its " + payloadSize + "-byte payload");
            }
            var page = (int) next;
            if (chain > 0) {
                if (seen == null) {
                    seen = new BitSet();
                    seen.set((int) overflowPage);
                }
                if (seen.get(page)) {
                    throw new IOException("The overflow chain of cell " + cellIndex + " of page " + cellPage + " reaches page " + page + " twice");
                }
                seen.set(page);
            }
            var data = pager.page(page);
            var count = Math.min(length - copied, usable - 4);
            System.arraycopy(data, 4, payload, copied, count);
            copied += count;
            next = SqliteBytes.u32(data, 0);
            chain++;
        }
        return payload;
    }
}
