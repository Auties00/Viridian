package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Builds the cells and b-trees of a database being written, bottom up, onto its [SqlitePageWriter].
///
/// A cell's payload keeps on its page the part SQLite computes for its size, and the rest in a chain of overflow
/// pages, which are written as soon as the cell is made, so that only the on-page part of a large record stays in
/// memory. A b-tree is built from its cells in key order: the leaves are filled one after another, then each level of
/// interior pages above them, until a level has one page, the root. A table b-tree's interior cell holds a child and
/// the largest rowid under it; an index b-tree moves one entry up between two neighbouring children. Every page but
/// the root has at least one cell, and every interior page at least two children, but for the root of
/// `sqlite_schema` on page 1: with 100 bytes less there, it is an interior page over a single leaf when its one row does
/// not fit beside the database header, as SQLite leaves it. Every leaf is at the same depth, cells lie back to back at
/// the end of their page, and no page has free blocks or fragmented bytes.
///
/// See [SqliteBTreeCursor] for the page and cell layouts and the payload split.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
final class SqliteBTreeWriter {
    /// The type byte of an index b-tree interior page.
    private static final int INDEX_INTERIOR = 2;

    /// The type byte of a table b-tree interior page.
    private static final int TABLE_INTERIOR = 5;

    /// The type byte of an index b-tree leaf page.
    private static final int INDEX_LEAF = 10;

    /// The type byte of a table b-tree leaf page.
    private static final int TABLE_LEAF = 13;

    /// The bytes the database header takes at the start of page 1.
    private static final int DATABASE_HEADER = 100;

    /// The smallest space SQLite gives a leaf cell, so that a freed cell can become a free block.
    private static final int MIN_CELL = 4;

    /// The pages written to.
    private final SqlitePageWriter pages;

    /// The bytes of each page that b-tree content may use; no bytes are reserved.
    private final int usable;

    /// The largest payload a table leaf cell keeps entirely on its page.
    private final int maxTableLocal;

    /// The largest payload an index cell keeps entirely on its page.
    private final int maxIndexLocal;

    /// The smallest part of a payload a cell keeps on its page when the payload overflows.
    private final int minLocal;

    /// The content of page 1 once the tree rooted there is built, for the caller to add the database header to.
    private byte[] pageOne;

    /// Builds onto `pages`.
    SqliteBTreeWriter(SqlitePageWriter pages) {
        this.pages = pages;
        this.usable = pages.pageSize();
        this.maxTableLocal = usable - 35;
        this.maxIndexLocal = (usable - 12) * 64 / 255 - 23;
        this.minLocal = (usable - 12) * 32 / 255 - 23;
    }

    /// {@return the table leaf cell of the row `rowid` whose record is `payload`, its overflow pages written}
    byte[] tableCell(long rowid, byte[] payload) throws IOException {
        var local = localSize(payload.length, maxTableLocal);
        var overflow = local < payload.length;
        var cell = new byte[SqliteBytes.varintLength(payload.length) + SqliteBytes.varintLength(rowid) + local + (overflow ? 4 : 0)];
        var position = SqliteBytes.putVarint(cell, 0, payload.length);
        position = SqliteBytes.putVarint(cell, position, rowid);
        System.arraycopy(payload, 0, cell, position, local);
        if (overflow) {
            SqliteBytes.putI32(cell, position + local, spill(payload, local));
        }
        return cell;
    }

    /// {@return the index leaf cell of the entry `payload`, its overflow pages written}
    ///
    /// The same bytes, after a 4-byte child page number, make the entry's cell on an interior page.
    byte[] indexCell(byte[] payload) throws IOException {
        var local = localSize(payload.length, maxIndexLocal);
        var overflow = local < payload.length;
        var cell = new byte[SqliteBytes.varintLength(payload.length) + local + (overflow ? 4 : 0)];
        var position = SqliteBytes.putVarint(cell, 0, payload.length);
        System.arraycopy(payload, 0, cell, position, local);
        if (overflow) {
            SqliteBytes.putI32(cell, position + local, spill(payload, local));
        }
        return cell;
    }

    /// {@return how much of a `payload`-byte payload a cell keeps on its page, when at most `maxLocal` fit there}
    private int localSize(long payload, int maxLocal) {
        if (payload <= maxLocal) {
            return (int) payload;
        }
        var kept = minLocal + (payload - minLocal) % (usable - 4);
        return (int) (kept <= maxLocal ? kept : minLocal);
    }

    /// Writes `payload` past its first `local` bytes to a chain of overflow pages, each a 4-byte next page number,
    /// 0 on the last, followed by up to `U - 4` bytes.
    ///
    /// @return the first page of the chain
    private int spill(byte[] payload, int local) throws IOException {
        var chunk = usable - 4;
        var count = (payload.length - local + chunk - 1) / chunk;
        var numbers = new int[count];
        for (var i = 0; i < count; i++) {
            numbers[i] = pages.allocate();
        }
        var page = new byte[usable];
        for (var i = 0; i < count; i++) {
            var start = local + i * chunk;
            var length = Math.min(chunk, payload.length - start);
            SqliteBytes.putI32(page, 0, i + 1 < count ? numbers[i + 1] : 0);
            System.arraycopy(payload, start, page, 4, length);
            if (length < chunk) {
                Arrays.fill(page, 4 + length, usable, (byte) 0);
            }
            pages.write(numbers[i], page);
        }
        return numbers[0];
    }

    /// Writes a table b-tree of `count` leaf cells, sorted by the strictly increasing `rowids`.
    ///
    /// @param root the page the root must be, 1 for `sqlite_schema`, or 0 for a new page
    /// @return the root page
    int writeTable(long[] rowids, byte[][] cells, int count, int root) throws IOException {
        var capacity = usable - 8;
        var groups = new ArrayList<int[]>();
        var start = 0;
        while (start < count) {
            var end = start;
            var used = 0;
            while (end < count && used + leafCost(cells[end]) <= capacity) {
                used += leafCost(cells[end]);
                end++;
            }
            groups.add(new int[]{start, end});
            start = end;
        }
        if (groups.isEmpty()) {
            var page = root != 0 ? root : pages.allocate();
            writePage(page, TABLE_LEAF, List.of(), 0);
            return page;
        }
        if (root == 1 && groups.size() == 1 && leafCost(cells, 0, count) > capacity - DATABASE_HEADER) {
            if (count == 1) {
                var leaf = pages.allocate();
                writePage(leaf, TABLE_LEAF, List.of(cells[0]), 0);
                writePage(root, TABLE_INTERIOR, List.of(), leaf);
                return root;
            }
            groups.set(0, new int[]{0, count / 2});
            groups.add(new int[]{count / 2, count});
        }
        var children = new int[groups.size()];
        var keys = new long[groups.size()];
        for (var i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            children[i] = groups.size() == 1 && root != 0 ? root : pages.allocate();
            keys[i] = rowids[group[1] - 1];
            writePage(children[i], TABLE_LEAF, Arrays.asList(cells).subList(group[0], group[1]), 0);
        }
        while (children.length > 1) {
            var level = interiorGroups(children.length, i -> 4 + SqliteBytes.varintLength(keys[i]) + 2, root == 1);
            var parents = new int[level.size()];
            var parentKeys = new long[level.size()];
            for (var g = 0; g < level.size(); g++) {
                var group = level.get(g);
                var interior = new ArrayList<byte[]>(group[1] - group[0]);
                for (var i = group[0]; i < group[1]; i++) {
                    var cell = new byte[4 + SqliteBytes.varintLength(keys[i])];
                    SqliteBytes.putI32(cell, 0, children[i]);
                    SqliteBytes.putVarint(cell, 4, keys[i]);
                    interior.add(cell);
                }
                parents[g] = level.size() == 1 && root != 0 ? root : pages.allocate();
                parentKeys[g] = keys[group[1]];
                writePage(parents[g], TABLE_INTERIOR, interior, children[group[1]]);
            }
            children = parents;
            System.arraycopy(parentKeys, 0, keys, 0, parentKeys.length);
        }
        return children[0];
    }

    /// Writes an index b-tree of the leaf cells `cells`, in key order.
    ///
    /// @return the root page, a new one
    int writeIndex(List<byte[]> cells) throws IOException {
        var count = cells.size();
        if (count == 0) {
            var page = pages.allocate();
            writePage(page, INDEX_LEAF, List.of(), 0);
            return page;
        }
        var capacity = usable - 8;
        var groups = new ArrayList<int[]>();
        var dividers = new ArrayList<byte[]>();
        var start = 0;
        while (start < count) {
            var end = start;
            var used = 0;
            while (end < count && used + leafCost(cells.get(end)) <= capacity) {
                used += leafCost(cells.get(end));
                end++;
            }
            if (end == count) {
                groups.add(new int[]{start, end});
                break;
            }
            if (end + 1 == count) {
                groups.add(new int[]{start, end - 1});
                dividers.add(cells.get(end - 1));
                groups.add(new int[]{end, count});
                break;
            }
            groups.add(new int[]{start, end});
            dividers.add(cells.get(end));
            start = end + 1;
        }
        var children = new int[groups.size()];
        for (var i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            children[i] = pages.allocate();
            writePage(children[i], INDEX_LEAF, cells.subList(group[0], group[1]), 0);
        }
        while (children.length > 1) {
            var separators = dividers;
            var level = interiorGroups(children.length, i -> 4 + separators.get(i).length + 2, false);
            var parents = new int[level.size()];
            var promoted = new ArrayList<byte[]>(level.size() - 1);
            for (var g = 0; g < level.size(); g++) {
                var group = level.get(g);
                var interior = new ArrayList<byte[]>(group[1] - group[0]);
                for (var i = group[0]; i < group[1]; i++) {
                    var divider = separators.get(i);
                    var cell = new byte[4 + divider.length];
                    SqliteBytes.putI32(cell, 0, children[i]);
                    System.arraycopy(divider, 0, cell, 4, divider.length);
                    interior.add(cell);
                }
                parents[g] = pages.allocate();
                writePage(parents[g], INDEX_INTERIOR, interior, children[group[1]]);
                if (g + 1 < level.size()) {
                    promoted.add(separators.get(group[1]));
                }
            }
            children = parents;
            dividers = promoted;
        }
        return children[0];
    }

    /// The cost on an interior page of the cell of each child but the last.
    @FunctionalInterface
    private interface CellCost {
        /// {@return the bytes, pointer included, the interior cell of child `child` takes}
        int of(int child);
    }

    /// {@return the children of each interior page of a level over `count` children, as `[first, last]` pairs, both
    /// inclusive: every child but the last of a page has a cell on it, the last is its rightmost child}
    ///
    /// Pages are filled one after another. Between two pages of an index b-tree, the cell of the first page's last
    /// child moves up a level, which the caller does. A page left with one child takes one from the page before.
    ///
    /// @param rootAtPageOne whether a level of one page must fit on page 1, after the database header
    private List<int[]> interiorGroups(int count, CellCost cost, boolean rootAtPageOne) {
        var capacity = usable - 12;
        var groups = new ArrayList<int[]>();
        var first = 0;
        while (first < count) {
            var last = first;
            var used = 0;
            while (last < count - 1 && used + cost.of(last) <= capacity) {
                used += cost.of(last);
                last++;
            }
            if (last == count - 1) {
                groups.add(new int[]{first, last});
                break;
            }
            if (last + 1 == count - 1) {
                groups.add(new int[]{first, last - 1});
                groups.add(new int[]{last, count - 1});
                break;
            }
            groups.add(new int[]{first, last});
            first = last + 1;
        }
        if (rootAtPageOne && groups.size() == 1) {
            var used = 0;
            for (var i = 0; i < count - 1; i++) {
                used += cost.of(i);
            }
            if (used > capacity - DATABASE_HEADER) {
                groups.set(0, new int[]{0, count / 2 - 1});
                groups.add(new int[]{count / 2, count - 1});
            }
        }
        return groups;
    }

    /// {@return the bytes, pointer included, the leaf cell `cell` takes}
    private static int leafCost(byte[] cell) {
        return Math.max(MIN_CELL, cell.length) + 2;
    }

    /// {@return the bytes, pointers included, the leaf cells `cells[from, to)` take}
    private static int leafCost(byte[][] cells, int from, int to) {
        var used = 0;
        for (var i = from; i < to; i++) {
            used += leafCost(cells[i]);
        }
        return used;
    }

    /// Writes page `page` as a b-tree page of type `type` holding `cells` in order, back to back from the end.
    ///
    /// @param rightChild the rightmost child of an interior page; ignored for a leaf
    private void writePage(int page, int type, List<byte[]> cells, int rightChild) throws IOException {
        var data = new byte[usable];
        var header = page == 1 ? DATABASE_HEADER : 0;
        var leaf = type == TABLE_LEAF || type == INDEX_LEAF;
        var pointer = header + (leaf ? 8 : 12);
        var content = usable;
        for (var cell : cells) {
            content -= leaf ? Math.max(MIN_CELL, cell.length) : cell.length;
            System.arraycopy(cell, 0, data, content, cell.length);
            SqliteBytes.putU16(data, pointer, content);
            pointer += 2;
        }
        if (pointer > content) {
            throw new IllegalStateException("The cells of page " + page + " overrun its cell pointers");
        }
        data[header] = (byte) type;
        SqliteBytes.putU16(data, header + 3, cells.size());
        SqliteBytes.putU16(data, header + 5, content & 0xFFFF);
        if (!leaf) {
            SqliteBytes.putI32(data, header + 8, rightChild);
        }
        if (page == 1) {
            pageOne = data;
        } else {
            pages.write(page, data);
        }
    }

    /// {@return the content of page 1, written by the last tree rooted there, without its database header}
    byte[] pageOne() {
        return pageOne;
    }
}
