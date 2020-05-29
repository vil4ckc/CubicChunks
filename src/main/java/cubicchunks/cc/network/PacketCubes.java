package cubicchunks.cc.network;

import cubicchunks.cc.chunk.IClientCubeProvider;
import cubicchunks.cc.chunk.cube.Cube;
import cubicchunks.cc.chunk.util.CubePos;
import cubicchunks.cc.utils.MathUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Collectors;

public class PacketCubes {
    // vanilla has max chunk size of 2MB, it works out to be 128kB per cube
    private static final int MAX_CUBE_SIZE = 128 * 1024;

    private final Cube[] cubes;
    private final BitSet cubeExists;
    private final byte[] packetData;
    private final List<CompoundNBT> tileEntityTags;

    public PacketCubes(List<Cube> cubes) {
        this.cubes = cubes.toArray(new Cube[0]);
        this.cubeExists = new BitSet(cubes.size());
        this.packetData = new byte[calculateDataSize(cubes)];
        fillDataBuffer(wrapBuffer(this.packetData), cubes, cubeExists);
        this.tileEntityTags = cubes.stream()
                .flatMap(cube -> cube.getTileEntityMap().values().stream())
                .map(TileEntity::getUpdateTag)
                .collect(Collectors.toList());
    }

    PacketCubes(PacketBuffer buf) {
        this.cubes = new Cube[buf.readVarInt()];
        // one long stores information about 64 chunks
        int length = MathUtil.ceilDiv(cubes.length, 64);
        this.cubeExists = BitSet.valueOf(buf.readLongArray(new long[length]));
        int packetLength = buf.readVarInt();
        if (packetLength > MAX_CUBE_SIZE * cubes.length) {
            throw new RuntimeException("Cubes Packet trying to allocate too much memory on read: " +
                    packetLength + " bytes for " + cubes.length + " cubes");
        }
        this.packetData = new byte[packetLength];
        buf.readBytes(this.packetData);
        int teTagCount = buf.readVarInt();
        this.tileEntityTags = new ArrayList<>(teTagCount);
        for (int i = 0; i < teTagCount; i++) {
            this.tileEntityTags.add(buf.readCompoundTag());
        }
    }

    void encode(PacketBuffer buf) {
        buf.writeVarInt(cubes.length);
        for (Cube cube : cubes) {
            buf.writeInt(cube.getCubePos().getX());
            buf.writeInt(cube.getCubePos().getY());
            buf.writeInt(cube.getCubePos().getZ());
        }

        buf.writeLongArray(cubeExists.toLongArray());

        buf.writeVarInt(this.packetData.length);
        buf.writeBytes(this.packetData);
        buf.writeVarInt(this.tileEntityTags.size());

        for (CompoundNBT compoundnbt : this.tileEntityTags) {
            buf.writeCompoundTag(compoundnbt);
        }
    }

    public static class Handler {

        public static void handle(PacketCubes packet, World worldIn) {
            ClientWorld world = (ClientWorld) worldIn;

            PacketBuffer dataReader = wrapBuffer(packet.packetData);
            BitSet cubeExists = packet.cubeExists;
            for (int i = 0; i < packet.cubes.length; i++) {
                CubePos pos = packet.cubes[i].getCubePos();
                int x = pos.getX();
                int y = pos.getY();
                int z = pos.getZ();

                ((IClientCubeProvider) world.getChunkProvider()).loadCube(
                        x, y, z, null, dataReader, new CompoundNBT(), cubeExists.get(i));

                // TODO: full cube info
                //            if (cube != null /*&&fullCube*/) {
                //                world.addEntitiesToChunk(cube.getColumn());
                //            }
                for (int dx = 0; dx < 2; dx++) {
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            world.markSurroundingsForRerender(x + dx, y + dy, z + dz);
                        }
                    }
                }

                for (CompoundNBT nbt : packet.tileEntityTags) {
                    BlockPos tePos = new BlockPos(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z"));
                    TileEntity te = world.getTileEntity(tePos);
                    if (te != null) {
                        te.handleUpdateTag(nbt);
                    }
                }
            }
        }
    }
    private static void fillDataBuffer(PacketBuffer buf, List<Cube> cubes, BitSet existingChunks) {
        buf.writerIndex(0);
        int i = 0;
        for (Cube cube : cubes) {
            if (!cube.isEmptyCube()) {
                existingChunks.set(i);
                cube.write(buf);
            }
            i++;
        }
    }

    private static PacketBuffer wrapBuffer(byte[] packetData) {
        ByteBuf bytebuf = Unpooled.wrappedBuffer(packetData);
        return new PacketBuffer(bytebuf);
    }

    private static int calculateDataSize(List<Cube> cubes) {
        return cubes.stream().filter(c -> !c.isEmptyCube()).mapToInt(Cube::getSize).sum();
    }
}