package com.github.auties00.viridian.value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// What every [JsBuilder] keeps besides its value: whether it is built, and the builders it holds, which build with
/// it.
///
/// A builder makes its value's instance when it is created and fills it in place, so that another builder can hold
/// the instance before it is complete; the instance is reachable from outside only through a built value, and
/// building marks every builder reachable from it as built first.
final class BuilderState {
    private boolean built;
    // The builders whose values this one holds; cleared once built
    private List<JsBuilder<?>> held;

    BuilderState() {
        this.held = new ArrayList<>();
    }

    /// Throws if the builder already built its value.
    void checkOpen() {
        if (built) {
            throw new IllegalStateException("The builder already built its value, which cannot change");
        }
    }

    /// {@return the value `builder` builds, which the builder of this state now holds}
    JsValue hold(JsBuilder<?> builder) {
        checkOpen();
        held.add(Objects.requireNonNull(builder, "builder"));
        return instanceOf(builder);
    }

    /// Marks this builder and every builder it holds, directly or through others, as built.
    void build() {
        if (built) {
            return;
        }
        // A work list rather than recursion, since values may be nested arbitrarily deep
        var pending = new ArrayDeque<BuilderState>();
        pending.push(this);
        while (!pending.isEmpty()) {
            var state = pending.pop();
            if (state.built) {
                continue;
            }
            state.built = true;
            for (var builder : state.held) {
                pending.push(stateOf(builder));
            }
            state.held = List.of();
        }
    }

    private static BuilderState stateOf(JsBuilder<?> builder) {
        return switch (builder) {
            case JsObject.Builder object -> object.state;
            case JsArray.Builder array -> array.state;
            case JsMap.Builder map -> map.state;
            case JsSet.Builder set -> set.state;
            case JsError.Builder error -> error.state;
        };
    }

    private static JsValue instanceOf(JsBuilder<?> builder) {
        return switch (builder) {
            case JsObject.Builder object -> object.instance;
            case JsArray.Builder array -> array.instance;
            case JsMap.Builder map -> map.instance;
            case JsSet.Builder set -> set.instance;
            case JsError.Builder error -> error.instance;
        };
    }
}
