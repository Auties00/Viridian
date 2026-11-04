/// Reads and writes the storage of Chromium-based browsers and apps: IndexedDB and Local Storage on their LevelDB and
/// SQLite backends, Session Storage, and the JavaScript values they hold, storage buckets and partitioned storage
/// included, in Java with no dependency and no native code.
///
/// [com.github.auties00.viridian.ChromiumProfile] is where to start: it reads a profile through its `indexedDb()`,
/// `localStorage()` and `sessionStorage()` facades, and writes it with `write(...)`. The values IndexedDB holds are
/// the types of [com.github.auties00.viridian.value].
///
/// Reading never changes a profile and takes no lock in it. Writing replaces stores of a profile in the formats Chrome
/// writes, holding the browser's own lock on the profile meanwhile, in one step a crash cannot leave half done.
/// Viridian never decrypts what a browser keeps encrypted for its user, such as saved passwords or cookies.
module com.github.auties00.viridian {
    exports com.github.auties00.viridian;
    exports com.github.auties00.viridian.value;
}
