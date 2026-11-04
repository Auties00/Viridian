/// Reads the storage of Chromium-based browsers and apps: IndexedDB on its LevelDB and SQLite backends, Local and
/// Session Storage, and the JavaScript values they hold, in Java with no dependency and no native code.
///
/// Viridian only reads. It never writes to a profile, never takes a lock in one, and never decrypts what a browser
/// keeps encrypted for its user, such as saved passwords or cookies.
module com.github.auties00.viridian {
    exports com.github.auties00.viridian;
    exports com.github.auties00.viridian.value;
}
