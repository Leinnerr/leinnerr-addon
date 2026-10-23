package com.leinnerr.addon.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

import java.util.Set;

public final class StateMatch {
    private static final Set<String> IGNORED = Set.of(
        "waterlogged", "powered", "lit", "triggered", "power", "distance", "persistent", "snowy",
        "north", "south", "east", "west", "up", "down", "shape", "attached", "open", "locked",
        "extended", "bottom", "tilt", "occupied", "disarmed", "has_book", "berries", "age"
    );

    private StateMatch() {}

    public static boolean sameState(BlockState want, BlockState have) {
        if (want.getBlock() != have.getBlock()) return false;

        for (Property<?> property : want.getProperties()) {
            if (IGNORED.contains(property.getName())) continue;
            if (!have.contains(property)) continue;
            if (!want.get(property).equals(have.get(property))) return false;
        }
        return true;
    }
}
