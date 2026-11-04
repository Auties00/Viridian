package com.github.auties00.viridian;

import java.io.IOException;

/// What a [ChromiumProfile#write(ProfileEditor)] does: the writes it makes through an edit's facades, committed
/// together once it returns.
@FunctionalInterface
public interface ProfileEditor {
    /// Stages writes through `edit`'s facades.
    ///
    /// @throws IOException if what it reads to make the writes cannot be read; the edit is then dropped
    void edit(ProfileEdit edit) throws IOException;
}
