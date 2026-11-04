package com.github.auties00.viridian.value;

import java.time.Instant;
import java.util.Optional;

/// A JavaScript `Date`.
///
/// @param time the milliseconds since the Unix epoch, which JavaScript keeps as a double; `NaN` for an invalid date
public record JsDate(double time) implements JsValue {
    /// {@return the instant of the date, or an empty optional for an invalid date or one outside `Instant`'s range}
    public Optional<Instant> instant() {
        if (Double.isNaN(time) || Double.isInfinite(time)) {
            return Optional.empty();
        }
        var millis = (long) Math.floor(time);
        var nanos = Math.round((time - millis) * 1_000_000);
        try {
            return Optional.of(Instant.ofEpochMilli(millis).plusNanos(nanos));
        } catch (ArithmeticException | java.time.DateTimeException _) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return instant().map(instant -> "Date(" + instant + ")").orElse("Date(Invalid Date)");
    }
}
