package com.github.auties00.viridian.value;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/// A JavaScript `Date`.
///
/// @param time the milliseconds since the Unix epoch, which JavaScript keeps as a double; `NaN` for an invalid date
public record JsDate(double time) implements JsValue {
    // The furthest a date may be from the epoch, in milliseconds: 100 million days
    private static final double MAX_TIME = 8.64e15;

    /// Creates a date, clipping its time as JavaScript's `TimeClip` does: a time more than 8.64e15 milliseconds from
    /// the epoch, or not finite, becomes `NaN`, and any other is truncated to a whole millisecond.
    ///
    /// @spec https://tc39.es/ecma262/#sec-timeclip TimeClip
    public JsDate {
        time = Double.isFinite(time) && Math.abs(time) <= MAX_TIME ? (long) time : Double.NaN;
    }

    /// {@return the date of `instant`, to the millisecond}
    ///
    /// @throws IllegalArgumentException if the instant is more than 8.64e15 milliseconds from the epoch, which no
    ///                                  JavaScript date can be
    public static JsDate of(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        long millis;
        try {
            millis = instant.toEpochMilli();
        } catch (ArithmeticException _) {
            millis = Long.MAX_VALUE;
        }
        if (Math.abs((double) millis) > MAX_TIME) {
            throw new IllegalArgumentException(instant + " is outside the range of JavaScript dates");
        }
        return new JsDate(millis);
    }

    /// {@return the instant of the date, or an empty optional for an invalid date}
    public Optional<Instant> instant() {
        if (Double.isNaN(time)) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochMilli((long) time));
    }

    @Override
    public String toString() {
        return instant().map(instant -> "Date(" + instant + ")").orElse("Date(Invalid Date)");
    }
}
