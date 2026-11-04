package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.leveldb.LevelDbWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Local and Session Storage against databases written by hand.
class DomStorageTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("https://example.com");

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] local(String value, boolean utf16) {
        return concat(new byte[]{(byte) (utf16 ? 0 : 1)}, value.getBytes(utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("an origin's localStorage items read in both encodings, other origins and bookkeeping left out")
    void localStorage(@TempDir Path directory) throws IOException {
        new LevelDbWriter()
                .put(ascii("VERSION"), ascii("1"))
                .put(ascii("META:https://example.com"), new byte[]{8, 1})
                .put(concat(ascii("_https://example.com"), new byte[]{0}, local("theme", false)), local("dark", false))
                .put(concat(ascii("_https://example.com"), new byte[]{0}, local("greeting", true)), local("你好", true))
                .put(concat(ascii("_https://example.com.evil"), new byte[]{0}, local("theme", false)), local("light", false))
                .put(concat(ascii("_https://other.com"), new byte[]{0}, local("x", false)), local("y", false))
                .put(concat(ascii("_https://example.com^0https://top.com"), new byte[]{0}, local("partitioned", false)), local("z", false))
                .writeLog(directory, 3);
        assertEquals(Map.of("theme", "dark", "greeting", "你好"), DomStorage.localStorage(directory, ORIGIN));
        assertEquals(List.of(StorageOrigin.parse("https://example.com"), StorageOrigin.parse("https://example.com.evil"),
                StorageOrigin.parse("https://other.com")), DomStorage.localStorageOrigins(directory));
    }

    @Test
    @DisplayName("an origin's sessionStorage reads per session, from the maps the sessions name")
    void sessionStorage(@TempDir Path directory) throws IOException {
        var first = "11111111_2222_3333_4444_555555555555";
        var second = "aaaaaaaa_bbbb_cccc_dddd_eeeeeeeeeeee";
        new LevelDbWriter()
                .put(ascii("version"), ascii("1"))
                .put(ascii("next-map-id"), ascii("3"))
                .put(ascii("namespace-" + first + "-https://example.com/"), ascii("1"))
                .put(ascii("namespace-" + second + "-https://example.com/"), ascii("2"))
                .put(ascii("namespace-" + second + "-https://other.com/"), ascii("0"))
                .put(concat(ascii("map-1-"), "stép".getBytes(StandardCharsets.UTF_8)), "1".getBytes(StandardCharsets.UTF_16LE))
                .put(concat(ascii("map-2-"), "stép".getBytes(StandardCharsets.UTF_8)), "你".getBytes(StandardCharsets.UTF_16LE))
                .put(concat(ascii("map-0-"), "secret".getBytes(StandardCharsets.UTF_8)), "no".getBytes(StandardCharsets.UTF_16LE))
                .writeLog(directory, 3);
        var areas = DomStorage.sessionStorage(directory, ORIGIN);
        assertEquals(2, areas.size());
        assertEquals(new DomStorage.SessionStorageItems(first, Map.of("stép", "1")), areas.get(0));
        assertEquals(new DomStorage.SessionStorageItems(second, Map.of("stép", "你")), areas.get(1));
    }
}
