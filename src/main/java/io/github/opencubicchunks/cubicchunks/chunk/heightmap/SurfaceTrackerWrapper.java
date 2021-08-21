package io.github.opencubicchunks.cubicchunks.chunk.heightmap;

import static io.github.opencubicchunks.cubicchunks.utils.Coords.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.opencubicchunks.cubicchunks.chunk.IBigCube;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.HeightmapAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

public class SurfaceTrackerWrapper extends Heightmap {
    protected final SurfaceTrackerSection surfaceTracker;
    /** global x of min block in column */
    protected final int dx;
    /** global z of min block in column */
    protected final int dz;

    private final boolean fromDisk;

    public SurfaceTrackerWrapper(ChunkAccess chunkAccess, Types types) {
        super(chunkAccess, types);
        ((HeightmapAccess) this).setIsOpaque(null);
        this.surfaceTracker = new SurfaceTrackerSection(types);
        this.dx = sectionToMinBlock(chunkAccess.getPos().x);
        this.dz = sectionToMinBlock(chunkAccess.getPos().z);
        this.fromDisk = false;

    }

    protected SurfaceTrackerWrapper(ChunkAccess chunkAccess, Types types, SurfaceTrackerSection root) {
        super(chunkAccess, types);
        ((HeightmapAccess) this).setIsOpaque(null);
        this.surfaceTracker = root;
        this.dx = sectionToMinBlock(chunkAccess.getPos().x);
        this.dz = sectionToMinBlock(chunkAccess.getPos().z);
        this.fromDisk = root.fromDisk;
    }

    /**
     * @param columnLocalX column-local x
     * @param globalY global y
     * @param columnLocalZ column-local z
     * @param blockState unused.
     *
     * @return currently unused; always false
     */
    @Override
    public boolean update(int columnLocalX, int globalY, int columnLocalZ, BlockState blockState) {
        surfaceTracker.getCubeNode(blockToCube(globalY)).markDirty(columnLocalX + dx, columnLocalZ + dz);
        // We always return false, because the result is never used anywhere anyway (by either vanilla or us)
        return false;
    }

    @Override
    public int getFirstAvailable(int columnLocalX, int columnLocalZ) {
        return surfaceTracker.getHeight(columnLocalX + dx, columnLocalZ + dz) + 1;
    }

    // TODO not sure what to do about these methods
    @Override
    public void setRawData(ChunkAccess clv, Types a, long[] ls) {
        throw new UnsupportedOperationException();
    }

    public CompoundTag saveToDisk() {
        this.surfaceTracker.clearDirtyPositions();
        return this.surfaceTracker.getSaveTag();
    }

    public static SurfaceTrackerWrapper fromDiskCompound(ChunkAccess chunkAccess, Heightmap.Types type, CompoundTag chunkTag) {
        return new SurfaceTrackerWrapper(chunkAccess, type, SurfaceTrackerSection.fromChunkTag(chunkTag, type));
    }

    @Override
    public long[] getRawData() {
        BitStorage data = ((HeightmapAccess) this).getData();
        surfaceTracker.writeData(dx, dz, data, ((HeightmapAccess) this).getChunk().getMinBuildHeight());
        return data.getRaw();
    }

    public void loadCube(IBigCube cube) {
        // TODO loading should only cause marking as dirty if not loading from save file
        this.surfaceTracker.loadCube(blockToCubeLocalSection(dx), blockToCubeLocalSection(dz), cube, !this.fromDisk);
    }

    public void unloadCube(IBigCube cube) {
        this.surfaceTracker.getCubeNode(cube.getCubePos().getY()).unloadCube(cube);
    }
}
