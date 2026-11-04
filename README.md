# Viridian

Reads the storage of Chromium-based browsers and apps in pure Java: IndexedDB on both of its backends, Local and
Session Storage, and the JavaScript values they hold. No dependencies, no native code.

```java
var profile = ChromiumProfile.open(Path.of(System.getenv("LOCALAPPDATA"), "Google", "Chrome", "User Data", "Default"));
var origin = StorageOrigin.parse("https://example.com");
try (var database = profile.openIndexedDb(origin, "app").orElseThrow();
     var records = database.records("settings")) {
    for (var record : records.toList()) {
        System.out.println(record.key() + " = " + record.value());
    }
}
Map<String, String> items = profile.localStorage(origin);
```

A profile is a directory such as Chrome's `User Data/Default`, an Electron app's `userData` directory, or a WebView2
app's `EBWebView/Default`.

## What it reads

| Storage | Where |
|---|---|
| IndexedDB, LevelDB backend | `IndexedDB/<origin>.indexeddb.leveldb`, one database per origin, with large blobs in `<origin>.indexeddb.blob` |
| IndexedDB, SQLite backend | `IndexedDB/<origin>/`, one SQLite file per database, blobs included; Chrome began rolling it out for new databases in Chrome 156 |
| `localStorage`, LevelDB backend | `Local Storage/leveldb` |
| `localStorage`, SQLite backend | `LocalStorage`, one SQLite file, which Chrome is rolling out through field trials |
| `sessionStorage`, LevelDB backend | `Session Storage`, one area per browsing session the browser kept |
| `sessionStorage`, SQLite backend | `SessionStorage`, one SQLite file |

A profile can hold data in both backends. Every method reads the backend Chromium reads, and an overload that takes
a `StorageBackend` reads the other. For IndexedDB that is LevelDB, where Chromium keeps an origin until it migrates
its data; for DOM Storage it is SQLite, which Chromium creates only where it uses SQLite, deleting the LevelDB
database once it has migrated it. `indexedDbBackend(origin)`, `localStorageBackend()` and `sessionStorageBackend()`
say which one that is. A database's records come in key order, from `records(store)` or `record(store, key)`.

Inside, Viridian has its own LevelDB reader (descriptors, logs, tables, checksums), SQLite reader (b-trees, overflow
pages, rollback journals and write-ahead logs) and Snappy and zstd decoders.

## Values

A record's value decodes to a `JsValue`, a sealed type that covers everything the structured clone algorithm stores:
primitives, `BigInt`s, dates, regular expressions, objects, arrays (dense and sparse), `Map`s, `Set`s, `ArrayBuffer`s
and their views, wrapped primitives and errors. Primitives are records. Objects and the other containers are identity
classes: a value the page stored twice decodes to one Java object, and cycles are kept. `toString()` renders a value
the way a console would, and stops at a cycle.

Blink's platform objects decode too:

- A `CryptoKey`: its algorithm, usages and extractable flag, and its key material, as raw bytes, PKCS #8 or SPKI. The
  material is there even for a key the page made non-extractable.
- A `Blob`, `File` or `FileList`. The bytes are read only when `open()` is called, from the profile's blob files or
  its database.
- `ImageData`, `ImageBitmap`, the geometry types (`DOMPoint`, `DOMRect`, `DOMQuad`, `DOMMatrix`), `DOMException`,
  `RTCCertificate`, and File System Access handles.

`StructuredClone.deserialize` decodes any value Chromium serialized, from IndexedDB or elsewhere.

## A profile in use

Viridian never writes to a profile and takes no lock in it. A profile the browser has closed reads as the browser left
it. One the browser is writing gives a best-effort view: it may lack the latest writes, or fail to read when a file
changes under the reader. To read a running browser's storage consistently, copy the profile first, or close the
browser.

## Errors

Every method that reads throws an `IOException`. A `ViridianException` means the files were read but what they hold
could not be, and its `reason()` says why:

| Reason | Meaning |
|---|---|
| `CORRUPTED` | The data is damaged, truncated, or not what it claims to be |
| `UNSUPPORTED` | The data is valid but uses a format version or feature Viridian does not know |
| `MISSING` | The data refers to something that is gone, such as the file of a stored blob |

A stream of records reports a failure while it is consumed as an `UncheckedIOException`. Damaged or hostile files make
the readers throw rather than loop or recurse without bound.

## What it does not do

- It does not write. Writing a profile, so that data can move into a browser as well as out of one, is planned.
- It never decrypts what a browser keeps encrypted for its user, such as saved passwords and cookies.
- It reads first-party storage, not partitioned (third-party) or Storage Buckets storage.

## Tested on

CI runs the tests on Windows, macOS and Linux. They check the SQLite reader against SQLite itself, the zstd and Snappy
decoders against their reference implementations (the zstd one on fuzzed input as well), and the LevelDB reader
against iq80's LevelDB.

Then Chrome writes a profile on each backend, with IndexedDB and DOM Storage both on LevelDB or both on SQLite:
Chrome for Testing of the stable channel on Windows, macOS and Linux, and of the beta channel on Linux. The page
stores every kind of value, key, blob and file, a non-extractable `CryptoKey`, and `localStorage` and
`sessionStorage` items long enough to be compressed, then reads it all back. Everything Viridian reads from the
profile must match what the page read. The workflow runs weekly too, to catch a Chrome release that changes a format.

## Building

Java 26 and Maven: `mvn verify`. To run the Chrome test locally, run `npm install` in `ci/chrome` (Node.js), then
have Chrome write a profile with `node generate.mjs <chrome> <profile> <leveldb|sqlite> <manifest>`. Any Chrome
executable works; `npx @puppeteer/browsers install chrome@stable` installs the one CI uses. Then run `mvn test
-Dtest=ChromeProfileTest` with `VIRIDIAN_CHROME_PROFILE` set to `<profile>/Default` and `VIRIDIAN_CHROME_MANIFEST` to
`<manifest>`.
