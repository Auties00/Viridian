# Viridian

Reads and writes the storage of Chromium-based browsers and apps in pure Java: IndexedDB and Local Storage on both of
their backends, Session Storage, storage buckets and partitioned storage, and the JavaScript values they hold. No
dependencies, no native code.

```java
var profile = ChromiumProfile.open(Path.of(System.getenv("LOCALAPPDATA"), "Google", "Chrome", "User Data", "Default"));
var origin = StorageOrigin.parse("https://example.com");
try (var database = profile.indexedDb().open(origin, "app").orElseThrow();
     var records = database.records("settings")) {
    records.forEach(record -> System.out.println(record.key() + " = " + record.value()));
}
Map<String, String> items = profile.localStorage().items(origin);

profile.write(edit -> {
    edit.indexedDb().put(origin, IdbDatabase.builder("app", 1)
            .put(IdbObjectStore.of("settings"), IdbKey.of("theme"), JsValue.of("dark"))
            .build());
    edit.localStorage().replaceAll(origin, Map.of("session", "restored"));
});
```

## Profiles

A profile is the directory that holds a browser's or an app's site storage: `Default` or `Profile 1` in Chrome's
`User Data`, an Electron app's `userData` directory and each of its `Partitions/<name>`, a WebView2 app's
`EBWebView/Default`.

```java
var chrome = ChromiumUserData.open(Path.of(System.getenv("LOCALAPPDATA"), "Google", "Chrome", "User Data"));
System.out.println(chrome.profileNames());                    // [Default, Profile 1]
ChromiumProfile profile = chrome.profile("Default");

ChromiumProfile app = ChromiumProfile.open(Path.of(System.getenv("APPDATA"), "MyElectronApp"));
ChromiumProfile work = ChromiumProfile.open(app.directory().resolve("Partitions").resolve("work"));

ChromiumProfile.open(chrome.directory());                     // IllegalArgumentException: a user data directory
ChromiumProfile seeded = ChromiumProfile.create(Files.createTempDirectory("seed").resolve("Default"));
```

`ChromiumProfile.open` refuses a user data directory, the classic mistake that otherwise reads as empty, and
`ChromiumProfile.create` makes the empty profile a harness seeds before it starts a browser on it.

A profile reads through three facades: `indexedDb()`, `localStorage()` and `sessionStorage()`. Each reads the backend
Chromium reads, and an overload that takes a `StorageBackend` reads that one only.

| Storage | Where |
|---|---|
| IndexedDB, LevelDB backend | `IndexedDB/<origin>.indexeddb.leveldb`, one database per origin, with large blobs in `<origin>.indexeddb.blob`; other buckets in `WebStorage/<id>/IndexedDB/` |
| IndexedDB, SQLite backend | `IndexedDB/<origin>/`, one SQLite file per database, blobs included; other buckets in `WebStorage/<id>/IndexedDB/`. Chrome began rolling it out for new databases in Chrome 156 |
| `localStorage`, LevelDB backend | `Local Storage/leveldb` |
| `localStorage`, SQLite backend | `LocalStorage`, one SQLite file, which Chrome is rolling out through field trials |
| `sessionStorage`, LevelDB backend | `Session Storage`, one area per browsing session the browser kept |
| `sessionStorage`, SQLite backend | `SessionStorage`, one SQLite file |

For IndexedDB Chromium reads LevelDB whenever a bucket has a LevelDB store; for DOM Storage it reads SQLite, which it
creates only where it uses it.

## Reading IndexedDB

```java
var indexedDb = profile.indexedDb();
for (var name : indexedDb.names(origin)) {
    try (var database = indexedDb.open(origin, name).orElseThrow()) {
        System.out.println(name + " v" + database.version() + " on " + database.backend());
        for (var store : database.objectStores()) {
            System.out.printf("  %s keyPath=%s autoIncrement=%s records=%d next key=%s%n", store.name(),
                    store.keyPath().map(Object::toString).orElse("out-of-line"), store.autoIncrement(),
                    database.count(store.name()), database.keyGenerator(store.name()));
        }
    }
}

try (var database = indexedDb.open(origin, "app").orElseThrow()) {
    Optional<JsValue> theme = database.get("settings", IdbKey.of("theme"));
    Optional<JsValue> entry = database.get("entries", IdbKey.of(IdbKey.of("chat-7"), IdbKey.of(1001)));
}

IdbDatabase app = indexedDb.read(origin, "app").orElseThrow();      // the whole database, as a value
app.records("settings").forEach((key, value) -> System.out.println(key + " = " + value));
```

`records(store)` streams a store in key order, decoding each record as it is reached; nothing in the stream throws a
checked exception, and a failure while it is consumed is an `UncheckedIOException`, as `Files.lines` reports one. A
cursor reads record by record and decodes a value only when asked, so a forensic reader can skip a damaged record:

```java
try (var database = indexedDb.open(origin, "mail").orElseThrow();
     var cursor = database.openCursor("messages")) {
    while (cursor.next()) {
        try {
            index(cursor.key(), cursor.value());
        } catch (ViridianException damaged) {
            Files.write(out.resolve(cursor.key() + ".bin"), cursor.serializedValue());
        }
    }
}
```

Values read as a page reads them. In a store with a key generator and a key path, Chromium stores a value without
the key the generator gave it, and Blink injects the key when it reads the value back; Viridian injects it too, while
`serializedValue()` keeps the bytes as stored.

## Values

A value decodes to a `JsValue`, a sealed type covering everything the structured clone algorithm stores: primitives,
`BigInt`s, dates, regular expressions, objects, arrays (dense and sparse), `Map`s, `Set`s, `ArrayBuffer`s and their
views, wrapped primitives, errors, and Blink's platform objects: `CryptoKey`, `Blob`, `File`, `FileList`, `ImageData`,
`ImageBitmap`, the geometry types, `DOMException`, `RTCCertificate` and File System Access handles. Values are
immutable. Primitives are records; objects compare by identity, as JavaScript's do, and a value the page stored twice
decodes to one object, cycles included. `JsValue.deepEquals` compares two values by what they hold, sharing and cycles
included.

```java
if (app.records("users").get(IdbKey.of(42)) instanceof JsObject user) {
    String name = user.getString("name").orElse("?");
    long age = user.getLong("age").orElseThrow();
    Optional<Instant> lastSeen = user.getDate("lastSeen");
    byte[] avatar = user.getBytes("avatar").orElseThrow();          // an ArrayBuffer, a Uint8Array or any view
    JsCryptoKey key = user.get("key", JsCryptoKey.class).orElseThrow();
    for (JsValue tag : user.getArray("tags").orElseThrow().values()) {   // holes read as undefined
        System.out.println(tag);
    }
    switch (user.get("nickname").orElse(null)) {                     // get tells {nickname: null} from no nickname
        case null -> System.out.println("no nickname property");
        case JsNull _ -> System.out.println("nickname explicitly null");
        case JsString(var nickname) -> System.out.println(nickname);
        default -> throw new IllegalStateException("unexpected nickname");
    }
}
```

The typed getters read `undefined`, `null` and an absent property as empty, throw `ClassCastException` naming the
property and both types for another type, and `getLong` throws `ArithmeticException` past 2^53, where a number may
hold only a rounded integer.

Every value a page can store can be built:

```java
JsObject alice = JsObject.builder()
        .put("id", 1)
        .put("name", "Alice")
        .put("address", JsObject.builder().put("city", "Rome"))     // a builder, built with alice
        .put("nickname", JsValue.NULL)
        .put("identityKey", JsArrayBufferView.uint8(publicKey))     // a Uint8Array
        .put("photo", JsBlob.of(Path.of("photo.png"), "image/png")) // read when the value is written
        .build();

JsCryptoKey aes = JsCryptoKey.secret(SecretKeyAlgorithm.AES_GCM, raw32)
        .usages(JsCryptoKey.Usage.ENCRYPT, JsCryptoKey.Usage.DECRYPT)
        .build();
KeyPair pair = KeyPairGenerator.getInstance("EC").generateKeyPair();
JsCryptoKey signing = JsCryptoKey.privateKey(KeyPairAlgorithm.ECDSA, pair.getPrivate())   // P-256, from the key
        .usages(JsCryptoKey.Usage.SIGN)
        .build();
SecretKey forJava = aes.toSecretKey();
```

A key is made from its material and what the material does not say, as `importKey` takes them: the hash of an HMAC
or RSA key, never an AES length, a curve, a modulus or an exponent, which come from the key. `key.algorithm()` holds
the full dictionary a page reads. A key's material is stored even when the page made it non-extractable.

`StructuredClone.deserialize` decodes any value Chromium serialized, from IndexedDB or elsewhere, and
`StructuredClone.serialize` writes one as Chrome would, with the blobs and files it refers to. It writes Blink's
version 21 with V8's version 15, which Chrome reads since 109, and version 16 only for a resizable buffer past 4 GiB,
which Chrome reads since 149.

## Writing

A database is a value. `IdbDatabase.builder` applies IndexedDB's rules at each `put`, as a page's `put` meets them,
and fails at the call that breaks one, naming the store and the key: it derives an in-line key from the value,
generates one with the store's key generator where the value has none and injects it into the value, refuses a second
record under one key of a unique index, and refuses a value that cannot be stored.

```java
IdbObjectStore people = IdbObjectStore.builder("people").keyPath("id").index("byEmail", "email", IdbIndex.Option.UNIQUE).build();
IdbObjectStore settings = IdbObjectStore.of("settings");
IdbObjectStore notes = IdbObjectStore.builder("notes").keyPath("id").autoIncrement().build();
IdbDatabase database = IdbDatabase.builder("app", 3)
        .put(people, alice)                                          // key 1, from alice.id
        .put(settings, IdbKey.of("theme"), JsValue.of("dark"))
        .put(notes, JsObject.builder().put("text", "hi").build())   // key 1, generated and injected as id
        .build();

profile.indexedDb().put(origin, database);                           // "app" added or replaced, others kept
profile.indexedDb().replaceAll(origin, List.of(database));           // the origin ends with "app" alone
profile.indexedDb().delete(origin, "app");

IdbDatabase current = profile.indexedDb().read(origin, "app").orElseThrow();
profile.indexedDb().put(origin, current.toBuilder().put(settings, IdbKey.of("theme"), JsValue.of("light")).build());

target.indexedDb().copyFrom(source.indexedDb(), origin);             // every database, record by record
```

Viridian writes what Chrome writes, so the browser opens it as its own and keeps using it: Chrome's metadata, index
entries computed from the values through the indexes' key paths, key generators as the store had them, values
compressed or moved into a blob past 64 KiB as Blink does, and blob and file bytes. A copy writes each value as
stored, a generated key left out as Chromium leaves it out.

An origin's IndexedDB is written on the backend it is on, LevelDB for an origin with none, and its data on the other
backend is deleted, since Chrome would otherwise read the old data or ignore the new. `localStorage` is written to
every backend the profile has, kept equal, since which one Chrome reads depends on its rollout of SQLite, or to
LevelDB. A facade pinned to a backend writes that one only. Values are refused, with `UNSUPPORTED`, when the browser
that last ran the profile is too old to read them, as `Last Version` or the profile's `Preferences` record it; no
record, as in an Electron app, means no check.

### One step, under the browser's lock

`profile.write(edit -> ...)` makes every write its editor stages in one step; a write on a profile's facade is a step
of its own. The stores are written beside the old ones and put in their place by a plan a crash cannot leave half
done: the next write, or `recover()`, finishes it if it had begun and drops it otherwise.

```java
var source = ChromiumProfile.open(chromeDefault);
var target = ChromiumProfile.open(edgeDefault);
target.write(edit -> {
    for (var bucket : source.indexedDb().buckets()) {
        edit.indexedDb().copyFrom(source.indexedDb(), bucket);
    }
    for (var key : source.localStorage().storageKeys()) {
        edit.localStorage().copyFrom(source.localStorage(), key);
    }
});
```

While it writes, Viridian holds the browser's own lock on the profile, in its user data directory, the app's
directory for an Electron app: a browser that starts meanwhile does not open the profile, and two writers, in one
process or two, exclude each other. On Windows that lock is `lockfile`, which a starting Chrome cannot open while
Viridian holds it, so it exits. Elsewhere it is `SingletonLock`, with the socket a starting Chrome connects to: Viridian
answers it once the write is done and the lock is gone, after which that Chrome starts as usual, or within 15 seconds
in any case, before Chrome would kill the process the lock names.

```java
try {
    profile.write(edit -> edit.localStorage().replaceAll(origin, items));
} catch (ProfileInUseException inUse) {
    System.err.println("Close the browser using " + profile.lockDirectory()
            + inUse.process().map(process -> " (process " + process.pid() + ")").orElse(""));
}
if (profile.needsRecovery()) {         // after a crash of this process, before starting a browser on the profile
    profile.recover();
}
```

## Storage keys and buckets

A page embedded by another site has storage of its own, partitioned by the top-level site; a page that opens a
bucket with `navigator.storageBuckets` keeps its IndexedDB there apart from its default bucket. A `StorageKey` names
the first kind, a `StorageBucket` the second; every method also takes a `StorageOrigin` for an origin's first-party
key and default bucket.

```java
var key = StorageKey.partitioned(origin, "https://news.example");    // origin embedded in news.example
var drafts = new StorageBucket(StorageKey.of(origin), "drafts");
for (var bucket : profile.indexedDb().buckets()) {
    System.out.println(bucket + ": " + profile.indexedDb().names(bucket));
}
profile.indexedDb().put(drafts, database);
profile.localStorage().replaceAll(key, Map.of("embedded", "true"));
```

Chromium keeps every bucket but an origin's first-party default one in `WebStorage/<id>`, the ids numbered by its
quota database, `WebStorage/QuotaManager`. Viridian finds buckets there, and records a bucket it writes that is not
there yet, in a copy of the database it puts in place with the rest. DOM Storage has no named buckets.

## Errors

Every method that reads or writes throws an `IOException`. A `ViridianException` means the files were reached but
what they hold could not be read, or may not be written, and its `reason()` says why:

| Reason | Meaning |
|---|---|
| `CORRUPTED` | The data is damaged, truncated, or not what it claims to be |
| `UNSUPPORTED` | The data uses a format version or feature Viridian does not know, or a value is newer than the browser that would read it |
| `MISSING` | The data refers to something that is gone, such as the file of a stored blob, or a blob to write has no bytes |
| `IN_USE` | A browser, an app or another writer has the profile or a store open; always a `ProfileInUseException` |

Damaged or hostile files make the readers throw rather than loop or recurse without bound.

## What it does not do

- It never decrypts what a browser keeps encrypted for its user, such as saved passwords and cookies.
- It does not write `sessionStorage`, which belongs to the browsing sessions a browser restores, nor File System
  Access handles, whose grants only the browser that issued them holds.
- It writes a LevelDB store whole, so putting one database writes the bucket's others again, decoded and serialized
  anew.
- It reads a profile a browser is writing as a best-effort view, which may lack the latest writes. Reading takes no
  lock; copy the profile or close the browser to read it consistently.
- An Electron app that does not ask for a single instance holds no profile lock; Viridian then refuses only the stores
  the app has open.

## Tested on

CI runs the tests on Windows, macOS and Linux. They check the SQLite reader and writer against SQLite itself, the zstd
and Snappy codecs against their reference implementations (on fuzzed input as well), and the LevelDB reader and writer
against iq80's LevelDB.

Then Chrome writes a profile on each backend, with IndexedDB and DOM Storage both on LevelDB or both on SQLite:
Chrome for Testing of the stable channel on Windows, macOS and Linux, and of the beta channel on Linux. The page
stores every kind of value, key, blob and file, a non-extractable `CryptoKey`, generated keys and a key generator past
its keys, `localStorage` and `sessionStorage` items long enough to be compressed, IndexedDB in a named storage bucket,
and, in a frame another site embeds, partitioned IndexedDB and `localStorage`; then it reads it all back. Everything
Viridian reads from the profile must match what the page read. Viridian then copies the profile, through its public
API and in one write, into a new one, and Chrome must read the copy as it wrote the original, every index entry and
key generator included, and keep writing to it across a restart. Last, Chrome started while Viridian holds the lock
must not open the profile, and Viridian must not write a profile Chrome runs on. The workflow runs weekly too, to catch
a Chrome release that changes a format.

## Building

Java 26 and Maven: `mvn verify`. To run the Chrome tests locally, run `npm install` in `ci/chrome` (Node.js), then
have Chrome write a profile with `node generate.mjs <chrome> <user data> <leveldb|sqlite> <manifest>`. Any Chrome
executable works; `npx @puppeteer/browsers install chrome@stable` installs the one CI uses. Run `mvn test
-Dtest=ChromeProfileTest,ChromeCopyTest` with `VIRIDIAN_CHROME_PROFILE` set to `<user data>/Default`,
`VIRIDIAN_CHROME_MANIFEST` to `<manifest>` and `VIRIDIAN_COPY_USER_DATA` to a new directory, then
`node read.mjs <chrome> <copy> <leveldb|sqlite> <manifest>` to have Chrome read the copy. `mvn test
-Dtest=ChromeLockTest` with `VIRIDIAN_CHROME_EXECUTABLE` set to the Chrome executable checks the lock.
