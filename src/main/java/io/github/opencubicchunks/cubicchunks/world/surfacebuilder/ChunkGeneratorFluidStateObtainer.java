package io.github.opencubicchunks.cubicchunks.world.surfacebuilder;

import net.minecraft.world.level.block.state.BlockState;

public interface ChunkGeneratorFluidStateObtainer {

    BlockState getDefaultFluidBlockState();
}