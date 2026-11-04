# Viridian

Reads the storage of Chromium-based browsers and apps in Java: IndexedDB on both of its backends, Local and Session
Storage, and the JavaScript values they hold. No dependencies, no native code, read-only.

```java
var profile = ChromiumProfile.open(Path.of(System.getenv("LOCALAPPDATA"), "Google", "Chrome", "User Data", "Default"));
var origin = StorageOrigin.parse("https://example.com");
try (var database = profile.openIndexedDb(origin, "app").orElseThrow();
     var records = database.records("settings")) {
    records.forEach(record -> System.out.println(record.key() + " = " + record.value()));
}
Map<String, String> items = profile.localStorage(origin);
```

A profile is a directory such as Chrome's `User Data/Default`, an Electron app's `userData` directory, or a WebView2
app's `EBWebView/Default`.

## What it reads

| Storage | Format |
|---|---|
| IndexedDB, LevelDB backend | `IndexedDB/<origin>.indexeddb.leveldb`, blobs in `<origin>.indexeddb.blob` |
| IndexedDB, SQLite backend | `IndexedDB/<origin>/<database>`, one SQLite file per database (the default for new databases since Chrome 156) |
| `localStorage` | `Local Storage/leveldb` |
| `sessionStorage` | `Session Storage` |

Values decode to a tree of `JsValue`s: every type the structured clone algorithm stores, cycles and shared references
included, and Blink's platform objects: `CryptoKey`s (with their key material, extractable or not), `Blob`s and
`File`s (with their bytes), `ImageData`, `ImageBitmap`, geometry objects, `DOMException`s and File System Access
handles. `StructuredClone.deserialize` decodes any value Chromium serialized, from IndexedDB or elsewhere.

Inside, Viridian has its own read-only LevelDB reader (descriptors, logs, tables, checksums), SQLite reader (b-trees,
overflow pages, write-ahead logs) and Snappy and zstd decoders, all pure Java.

## What it does not do

Viridian never writes to a profile and takes no lock in it: a profile the browser has closed reads as it was left,
one the browser is writing gives a best-effort view. It never decrypts what a browser keeps encrypted for its user,
such as saved passwords or cookies. It reads first-party storage, not partitioned (third-party) or Storage Buckets
storage.

## Tested on

CI runs the tests on Windows, macOS and Linux, checking the SQLite reader against SQLite itself, the zstd and Snappy
decoders against their reference implementations, and the LevelDB reader against iq80's LevelDB. Then a real Chrome,
stable and beta, writes a profile on each IndexedDB backend with every kind of value, and everything Viridian reads
must match what the page reads back. It runs weekly too, to catch a Chrome release that changes a format.

## Building

Java 26 and Maven: `mvn verify`. To run the Chrome test locally, have Chrome write a profile with
`ci/chrome/generate.mjs` (Node.js, `npm install` in `ci/chrome`), then run `mvn test -Dtest=ChromeProfileTest` with
`VIRIDIAN_CHROME_PROFILE` and `VIRIDIAN_CHROME_MANIFEST` set.
