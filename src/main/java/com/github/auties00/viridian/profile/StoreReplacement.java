package com.github.auties00.viridian.profile;

import com.github.auties00.viridian.ViridianException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// Replaces files and directories of a profile in one step that a crash cannot leave half done.
///
/// A replacement stages its new stores in a directory of the profile, `.viridian-<id>`, whose `new` directory holds
/// them. [#commit()] forces them to disk and writes the plan, `COMMITTED`, which names every path the replacement
/// replaces or deletes; then it moves each original into the staging directory's `old` directory, moves each new
/// store into place, and deletes the staging directory. Until `COMMITTED` exists, the originals are untouched, and
/// a replacement cut short is dropped; once it exists, a replacement cut short is finished. [#recover(Path)], which
/// [#begin(Path)] runs first, does either for every staging directory a replacement left behind.
///
/// Paths are relative to the profile. A replacement is not safe for concurrent use, and is made by the holder of the
/// profile's [ProfileLock] only, since recovering takes every staging directory for a crash's.
public final class StoreReplacement implements AutoCloseable {
    private static final String PREFIX = ".viridian-";
    private static final String PLAN = "COMMITTED";
    private static final String NEW = "new";
    private static final String OLD = "old";

    private final Path profile;
    private final Path staging;
    private final List<Step> steps = new ArrayList<>();
    private boolean done;

    private StoreReplacement(Path profile, Path staging) {
        this.profile = profile;
        this.staging = staging;
    }

    /// Starts a replacement in `profile`, after finishing or dropping those a crash left behind.
    ///
    /// @throws IOException if a replacement left behind cannot be recovered, or the staging directory cannot be made
    public static StoreReplacement begin(Path profile) throws IOException {
        recover(profile);
        var staging = profile.resolve(PREFIX + UUID.randomUUID());
        Files.createDirectories(staging.resolve(NEW));
        return new StoreReplacement(profile, staging);
    }

    /// {@return where to write the new version of `target`, a path relative to the profile; nothing is there yet}
    ///
    /// If nothing is written there, the commit deletes `target`.
    ///
    /// @throws IllegalArgumentException if `target` is absolute, leaves the profile, or is named twice
    public Path stage(Path target) {
        var step = add(target, true);
        return staging.resolve(NEW).resolve(Integer.toString(step.number()));
    }

    /// Makes the commit delete `target`, a path relative to the profile, if it exists.
    ///
    /// @throws IllegalArgumentException if `target` is absolute, leaves the profile, or is named twice
    public void delete(Path target) {
        add(target, false);
    }

    private Step add(Path target, boolean replace) {
        if (done) {
            throw new IllegalStateException("The replacement is over");
        }
        var normalized = target.normalize();
        if (normalized.isAbsolute() || normalized.toString().isEmpty() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Not a path inside the profile: " + target);
        }
        for (var step : steps) {
            if (step.target().equals(normalized)) {
                throw new IllegalArgumentException("The replacement already names " + target);
            }
        }
        var step = new Step(steps.size(), replace, normalized);
        steps.add(step);
        return step;
    }

    /// Puts the staged stores in place of their targets and deletes the targets to delete.
    ///
    /// Once the plan is on disk the replacement happens, even if this process stops before it is done: the next
    /// [#begin(Path)] or [#recover(Path)] finishes it.
    ///
    /// @throws IOException if the stores cannot be forced to disk or moved
    public void commit() throws IOException {
        if (done) {
            throw new IllegalStateException("The replacement is over");
        }
        for (var i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            if (step.replace() && !Files.exists(staging.resolve(NEW).resolve(Integer.toString(step.number())), LinkOption.NOFOLLOW_LINKS)) {
                steps.set(i, new Step(step.number(), false, step.target()));
            }
        }
        force(staging.resolve(NEW));
        var plan = new StringBuilder();
        for (var step : steps) {
            plan.append(step.replace() ? "replace" : "delete").append('\t').append(step.number()).append('\t')
                    .append(step.target().toString().replace('\\', '/')).append('\n');
        }
        var temporary = staging.resolve(PLAN + ".tmp");
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(plan.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        Files.move(temporary, staging.resolve(PLAN), StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(staging);
        done = true;
        finish(profile, staging, steps);
    }

    /// Drops the replacement if it was not committed, deleting what it staged.
    ///
    /// @throws IOException if the staging directory cannot be deleted
    @Override
    public void close() throws IOException {
        if (!done) {
            done = true;
            deleteTree(staging);
        }
    }

    /// {@return whether `profile` holds a replacement a crash left behind, or one a writer is making now}
    ///
    /// @throws IOException if the profile cannot be listed
    public static boolean hasLeftovers(Path profile) throws IOException {
        if (!Files.isDirectory(profile)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(profile, PREFIX + "*")) {
            return entries.iterator().hasNext();
        }
    }

    /// Finishes every committed replacement a crash left in `profile`, and drops every uncommitted one.
    ///
    /// Only the holder of the profile's lock may run it: a replacement another writer is making looks like one a
    /// crash left.
    ///
    /// @throws ViridianException if a plan is damaged
    /// @throws IOException       if a store cannot be moved, or a staging directory cannot be deleted
    public static void recover(Path profile) throws IOException {
        if (!Files.isDirectory(profile)) {
            return;
        }
        var leftovers = new ArrayList<Path>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(profile, PREFIX + "*")) {
            entries.forEach(leftovers::add);
        }
        for (var staging : leftovers) {
            var plan = staging.resolve(PLAN);
            if (Files.isRegularFile(plan)) {
                finish(profile, staging, readPlan(plan));
            } else {
                deleteTree(staging);
            }
        }
    }

    /// Moves every original into `old` and every new store into place, then deletes the staging directory; each step
    /// is skipped when an earlier run already took it.
    private static void finish(Path profile, Path staging, List<Step> steps) throws IOException {
        Files.createDirectories(staging.resolve(OLD));
        for (var step : steps) {
            var target = profile.resolve(step.target());
            var old = staging.resolve(OLD).resolve(Integer.toString(step.number()));
            if (step.replace()) {
                var replacement = staging.resolve(NEW).resolve(Integer.toString(step.number()));
                if (!Files.exists(replacement)) {
                    continue;
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                        // Made after the original moved away, by the browser: the replacement wins
                        deleteTree(target);
                    } else {
                        Files.move(target, old, StandardCopyOption.ATOMIC_MOVE);
                    }
                }
                Files.createDirectories(target.getParent());
                Files.move(replacement, target, StandardCopyOption.ATOMIC_MOVE);
            } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(target, old, StandardCopyOption.ATOMIC_MOVE);
            }
        }
        deleteTree(staging);
    }

    private static List<Step> readPlan(Path plan) throws IOException {
        var steps = new ArrayList<Step>();
        for (var line : Files.readAllLines(plan, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            var fields = line.split("\t", 3);
            if (fields.length != 3 || !(fields[0].equals("replace") || fields[0].equals("delete"))) {
                throw new ViridianException(ViridianException.Reason.CORRUPTED, "The replacement plan " + plan + " is damaged");
            }
            int number;
            try {
                number = Integer.parseInt(fields[1]);
            } catch (NumberFormatException exception) {
                throw new ViridianException(ViridianException.Reason.CORRUPTED, "The replacement plan " + plan + " is damaged", exception);
            }
            var target = Path.of(fields[2]).normalize();
            if (target.isAbsolute() || target.startsWith("..")) {
                throw new ViridianException(ViridianException.Reason.CORRUPTED, "The replacement plan " + plan + " names a path outside the profile");
            }
            steps.add(new Step(number, fields[0].equals("replace"), target));
        }
        return steps;
    }

    /// Forces every file under `root`, and the directories that hold them, to disk.
    private static void force(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) {
                    try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                forceDirectory(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// Forces a directory's entries to disk where the platform allows it; Windows does not open directories.
    private static void forceDirectory(Path directory) {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException _) {
            // Not supported here; the file system orders the renames
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null && !(failure instanceof NoSuchFileException)) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// One path of a replacement: replaced by staged store number `number`, or deleted.
    private record Step(int number, boolean replace, Path target) {
    }
}
