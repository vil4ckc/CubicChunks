/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2019 OpenCubicChunks
 *  Copyright (c) 2015-2019 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.core.server;

import static io.github.opencubicchunks.cubicchunks.api.util.Coords.localToBlock;

import gnu.trove.list.TShortList;
import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.util.CubePos;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.core.common.vanillaclient.ISPacketChunkData;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.core.common.vanillaclient.ISPacketMultiBlockChange;
import io.github.opencubicchunks.cubicchunks.core.asm.mixin.fixes.common.vanillaclient.INetHandlerPlayServer;
import io.github.opencubicchunks.cubicchunks.core.util.AddressTools;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.network.play.server.SPacketMultiBlockChange;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.network.play.server.SPacketUnloadChunk;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class VanillaNetworkHandler {

    private static final Map<Class<?>, Field[]> packetFields = new HashMap<>();
    private final WorldServer world;
    private Map<EntityPlayerMP, CubePos> playerYOffsetsS2C = new Object2ObjectOpenHashMap<>();
    // separate offset because when switching layers, there is a short moment where
    // packets still sent with the client on the old offset will be processed
    private Map<EntityPlayerMP, CubePos> playerYOffsetsC2S = new Object2ObjectOpenHashMap<>();
    private Map<EntityPlayerMP, Integer> expectedTeleportId = new HashMap<>();

    public VanillaNetworkHandler(WorldServer world) {
        this.world = world;
    }

    // TODO: more efficient way?
    public static Packet<?> copyPacket(Packet<?> packetIn) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return packetIn;
        }
        try {
            Field[] fields = packetFields.computeIfAbsent(packetIn.getClass(), VanillaNetworkHandler::collectFields);
            Constructor<?> constructor = packetIn.getClass().getConstructor();
            Packet<?> newPacket = (Packet<?>) constructor.newInstance();
            for (Field field : fields) {
                Object v = field.get(packetIn);
                field.set(newPacket, v);
            }
            return newPacket;
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new Error(e);
        }
    }


    private static Field[] collectFields(Class<?> aClass) {
        return collectFieldList(aClass).toArray(new Field[0]);
    }

    private static List<Field> collectFieldList(Class<?> aClass) {
        List<Field> fields = new ArrayList<>();
        do {
            Field[] f = aClass.getDeclaredFields();
            for (Field field : f) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            aClass = aClass.getSuperclass();
        } while (aClass != Object.class);
        return fields;
    }

    private CubePos getPlayerOffsetS2C(EntityPlayerMP player) {
        return playerYOffsetsS2C.getOrDefault(player, CubePos.ZERO);
    }

    private CubePos getPlayerOffsetC2S(EntityPlayerMP player) {
        return playerYOffsetsC2S.getOrDefault(player, CubePos.ZERO);
    }

    public void sendCubeLoadPackets(Collection<? extends ICube> cubes, EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return;
        }
        Map<ChunkPos, List<ICube>> columns = cubes.stream().collect(Collectors.groupingBy(c -> c.getCoords().chunkPos()));
        for (Map.Entry<ChunkPos, List<ICube>> chunkPosListEntry : columns.entrySet()) {
            ChunkPos pos = chunkPosListEntry.getKey();
            List<ICube> column = chunkPosListEntry.getValue();
            SPacketChunkData chunkData = constructChunkData(pos, column, getPlayerOffsetS2C(player), world.provider.hasSkyLight());
            player.connection.sendPacket(chunkData);
        }
    }

    public void sendFullCubeLoadPackets(Collection<? extends ICube> cubes, EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return;
        }
        Map<Chunk, List<ICube>> columns = cubes.stream().collect(Collectors.groupingBy(ICube::getColumn));
        for (Map.Entry<Chunk, List<ICube>> chunkPosListEntry : columns.entrySet()) {
            Chunk chunk = chunkPosListEntry.getKey();
            List<ICube> column = chunkPosListEntry.getValue();
            SPacketChunkData chunkData = constructFullChunkData(chunk, column, getPlayerOffsetS2C(player), world.provider.hasSkyLight());
            SPacketUnloadChunk unload = new SPacketUnloadChunk(chunk.x, chunk.z);
            player.connection.sendPacket(unload);
            player.connection.sendPacket(chunkData);
        }
    }

    public void sendColumnLoadPacket(Chunk chunk, EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return;
        }
        player.connection.sendPacket(constructChunkData(chunk));
    }

    public void sendColumnUnloadPacket(ChunkPos pos, EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return;
        }
        player.connection.sendPacket(new SPacketUnloadChunk(pos.x, pos.z));
    }

    @SuppressWarnings("ConstantConditions")
    public void sendBlockChanges(TShortList dirtyBlocks, Cube cube, EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return;
        }
        CubePos offset = getPlayerOffsetS2C(player);
        int idx = cube.getX() + offset.getX();
        int idy = cube.getY() + offset.getY();
        int idz = cube.getZ() + offset.getZ();
        if (idy < 0 || idy >= 16) {
            return;
        }
        if (dirtyBlocks.size() == 1) {
            int localAddress = dirtyBlocks.get(0);
            int x = localToBlock(cube.getX(), AddressTools.getLocalX(localAddress));
            int y = localToBlock(cube.getY(), AddressTools.getLocalY(localAddress));
            int z = localToBlock(cube.getZ(), AddressTools.getLocalZ(localAddress));
            SPacketBlockChange packet = new SPacketBlockChange(world, new BlockPos(x, y, z));
            player.connection.sendPacket(packet);
            return;
        }
        SPacketMultiBlockChange.BlockUpdateData[] updates = new SPacketMultiBlockChange.BlockUpdateData[dirtyBlocks.size()];
        SPacketMultiBlockChange packet = new SPacketMultiBlockChange();
        for (int i = 0; i < dirtyBlocks.size(); i++) {
            int localAddress = dirtyBlocks.get(i);
            int x = AddressTools.getLocalX(localAddress);
            int localY = AddressTools.getLocalY(localAddress);
            int y = localY + Coords.cubeToMinBlock(idy);
            int z = AddressTools.getLocalZ(localAddress);
            short vanillaPos = (short)(x << 12 | z << 8 | y);
            updates[i] = packet.new BlockUpdateData(vanillaPos,
                    cube.getBlockState(
                            localToBlock(cube.getX(), x),
                            localToBlock(cube.getY(), localY),
                            localToBlock(cube.getZ(), z)));
        }
        ((ISPacketMultiBlockChange) packet).setChangedBlocks(updates);
        ((ISPacketMultiBlockChange) packet).setChunkPos(new ChunkPos(idx, idz));

        player.connection.sendPacket(packet);
    }

    public void updatePlayerPosition(PlayerCubeMap cubeMap, EntityPlayerMP player, CubePos managedPos) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return;
        }
        CubePos offset = playerYOffsetsS2C.getOrDefault(player, CubePos.ZERO);
        int idx = managedPos.getX() + offset.getX();
        int idy = managedPos.getY() + offset.getY();
        int idz = managedPos.getZ() + offset.getZ();

        boolean shouldSliceTransition = idy < 2 || idy >= 14;
        boolean isHorizontalSlices = CubicChunksConfig.vanillaClients.horizontalSlices;
        if (!shouldSliceTransition && isHorizontalSlices) {
            int horizontalSliceSize = CubicChunksConfig.vanillaClients.horizontalSliceSize;
            int maxHorizontalOffset = Math.max(Math.abs(idx), Math.abs(idz));
            shouldSliceTransition = maxHorizontalOffset >= Coords.blockToCube(horizontalSliceSize);
        }
        if (shouldSliceTransition) {
            int newXOffset = isHorizontalSlices ? -managedPos.getX() : 0;
            int newYOffset = 8 - managedPos.getY();
            int newZOffset = isHorizontalSlices ? -managedPos.getZ() : 0;
            CubePos newOffset = new CubePos(newXOffset, newYOffset, newZOffset);
            playerYOffsetsS2C.put(player, newOffset);
            switchPlayerOffset(cubeMap, player, offset, newOffset);
        }
    }

    public boolean receiveOffsetUpdateConfirm(EntityPlayerMP player, int teleportId) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return false;
        }
        if (!expectedTeleportId.containsKey(player) || !expectedTeleportId.remove(player, teleportId)) {
            return false;
        }
        playerYOffsetsC2S.put(player, playerYOffsetsS2C.get(player));
        return true;
    }

    public void removePlayer(EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return;
        }
        playerYOffsetsS2C.remove(player);
        playerYOffsetsC2S.remove(player);
        expectedTeleportId.remove(player);
    }

    private void switchPlayerOffset(PlayerCubeMap cubeMap, EntityPlayerMP player, CubePos offset, CubePos newOffset) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return;
        }
        List<ICube> firstSendCubes = new ArrayList<>();
        List<ICube> secondSendCubes = new ArrayList<>();
        List<ICube> lastSendCubes = new ArrayList<>();
        for (CubeWatcher cubeWatcher : cubeMap.cubeWatchers) {
            if (!cubeWatcher.isSentToPlayers() || !cubeWatcher.containsPlayer(player)) {
                continue;
            }
            int cy = Math.abs(player.chunkCoordY - cubeWatcher.getY());
            int cx = Math.abs(player.chunkCoordX - cubeWatcher.getX());
            int cz = Math.abs(player.chunkCoordZ - cubeWatcher.getZ());

            if (cx <= 1 && cz <= 1) {
                if (cy <= 1) {
                    firstSendCubes.add(cubeWatcher.getCube());
                }
                secondSendCubes.add(cubeWatcher.getCube());
            } else {
                lastSendCubes.add(cubeWatcher.getCube());
            }
        }

        sendCubeLoadPackets(firstSendCubes, player);

        int teleportId = ((INetHandlerPlayServer) player.connection).getTeleportId();
        if (++teleportId == Integer.MAX_VALUE) {
            teleportId = 0;
        }
        ((INetHandlerPlayServer) player.connection).setTeleportId(teleportId);
        expectedTeleportId.put(player, teleportId);
        int dx = Coords.cubeToMinBlock(newOffset.getX() - offset.getX());
        int dy = Coords.cubeToMinBlock(newOffset.getY() - offset.getY());
        int dz = Coords.cubeToMinBlock(newOffset.getZ() - offset.getZ());
        SPacketPlayerPosLook tpPacket = new SPacketPlayerPosLook(dx, dy + 0.01, dz, 0, 0,
                EnumSet.allOf(SPacketPlayerPosLook.EnumFlags.class), teleportId);
        player.connection.sendPacket(tpPacket);

        sendFullCubeLoadPackets(secondSendCubes, player);
        sendFullCubeLoadPackets(lastSendCubes, player);
        world.getEntityTracker().removePlayerFromTrackers(player);
        world.getEntityTracker().updateVisibility(player);
    }

    private static SPacketChunkData constructChunkData(ChunkPos pos, Iterable<ICube> cubes, CubePos offset, boolean hasSkyLight) {
        ICube[] cubesToSend = new ICube[16];
        int mask = getCubesToSend(cubes, offset, cubesToSend);

        SPacketChunkData chunkData = new SPacketChunkData();
        @SuppressWarnings("ConstantConditions")
        ISPacketChunkData dataAccess = (ISPacketChunkData) chunkData;
        dataAccess.setChunkX(pos.x + offset.getX());
        dataAccess.setChunkZ(pos.z + offset.getZ());
        dataAccess.setFullChunk(false);
        byte[] dataBuffer = new byte[computeBufferSize(cubesToSend, hasSkyLight, mask)];
        PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(dataBuffer));
        buf.writerIndex(0);
        int availableSections = writeData(buf, cubesToSend, hasSkyLight, mask);
        dataAccess.setAvailableSections(availableSections);
        dataAccess.setBuffer(dataBuffer);

        List<NBTTagCompound> teList = collectTileEntityTags(offset, cubesToSend);
        dataAccess.setTileEntityTags(teList);
        return chunkData;
    }


    private SPacketChunkData constructChunkData(Chunk chunk) {
        SPacketChunkData chunkData = new SPacketChunkData();
        @SuppressWarnings("ConstantConditions")
        ISPacketChunkData dataAccess = (ISPacketChunkData) chunkData;
        dataAccess.setChunkX(chunk.x);
        dataAccess.setChunkZ(chunk.z);
        dataAccess.setAvailableSections(0);
        dataAccess.setFullChunk(true);
        byte[] dataBuffer = new byte[computeBufferSize(chunk)];
        PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(dataBuffer));
        buf.writerIndex(0);
        writeData(buf, chunk);
        dataAccess.setBuffer(dataBuffer);
        dataAccess.setTileEntityTags(new ArrayList<>());
        return chunkData;
    }

    private static SPacketChunkData constructFullChunkData(Chunk chunk, Iterable<ICube> cubes, CubePos offset, boolean hasSkyLight) {
        ICube[] cubesToSend = new ICube[16];
        int mask = getCubesToSend(cubes, offset, cubesToSend);

        SPacketChunkData chunkData = new SPacketChunkData();
        @SuppressWarnings("ConstantConditions")
        ISPacketChunkData dataAccess = (ISPacketChunkData) chunkData;
        dataAccess.setChunkX(chunk.x + offset.getX());
        dataAccess.setChunkZ(chunk.z + offset.getZ());
        dataAccess.setFullChunk(true);
        byte[] dataBuffer = new byte[computeBufferSize(chunk, cubesToSend, hasSkyLight, mask)];
        PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(dataBuffer));
        buf.writerIndex(0);
        int availableSections = writeData(chunk, buf, cubesToSend, hasSkyLight, mask);
        dataAccess.setAvailableSections(availableSections);
        dataAccess.setBuffer(dataBuffer);

        List<NBTTagCompound> teList = collectTileEntityTags(offset, cubesToSend);
        dataAccess.setTileEntityTags(teList);
        return chunkData;
    }

    private static int getCubesToSend(Iterable<ICube> cubes, CubePos offset, ICube[] cubesToSend) {
        int mask = 0;
        for (ICube cube : cubes) {
            int idx = cube.getY() + offset.getY();
            if (idx < 0 || idx >= 16) {
                continue;
            }
            if (cube.isEmpty()) {
                continue;
            }
            cubesToSend[idx] = cube;
            mask |= 1 << idx;
        }
        return mask;
    }

    private static List<NBTTagCompound> collectTileEntityTags(CubePos offset, ICube[] cubesToSend) {
        List<NBTTagCompound> teList = new ArrayList<>();
        for (ICube c : cubesToSend) {
            if (c != null) {
                for (TileEntity value : c.getTileEntityMap().values()) {
                    NBTTagCompound updateTag = value.getUpdateTag();
                    if (updateTag.hasKey("x")) {
                        updateTag.setInteger("x", updateTag.getInteger("x") + Coords.cubeToMinBlock(offset.getX()));
                    }
                    if (updateTag.hasKey("y")) {
                        updateTag.setInteger("y", updateTag.getInteger("y") + Coords.cubeToMinBlock(offset.getY()));
                    }
                    if (updateTag.hasKey("z")) {
                        updateTag.setInteger("z", updateTag.getInteger("z") + Coords.cubeToMinBlock(offset.getZ()));
                    }
                    teList.add(updateTag);
                }
            }
        }
        return teList;
    }

    private static int computeBufferSize(ICube[] cubesToSend, boolean hasSkyLight, int mask) {
        int total = 0;
        int cubeCount = cubesToSend.length;

        for (int j = 0; j < cubeCount; j++) {
            ExtendedBlockStorage storage = getStorage(cubesToSend, j);

            if (storage != Chunk.NULL_BLOCK_STORAGE && (mask & 1 << j) != 0) {
                total += storage.getData().getSerializedSize();
                total += storage.getBlockLight().getData().length;

                if (hasSkyLight) {
                    total += storage.getSkyLight().getData().length;
                }
            }
        }

        return total;
    }

    private static ExtendedBlockStorage getStorage(ICube[] cubesToSend, int idx) {
        return cubesToSend[idx] == null ? null : cubesToSend[idx].getStorage();
    }

    private static int computeBufferSize(Chunk chunk) {
        return 256;
    }

    private static int computeBufferSize(Chunk chunk, ICube[] cubesToSend, boolean hasSkyLight, int mask) {
        int total = 0;
        int cubeCount = cubesToSend.length;

        for (int j = 0; j < cubeCount; j++) {
            ExtendedBlockStorage storage = getStorage(cubesToSend, j);

            if (storage != Chunk.NULL_BLOCK_STORAGE && (!storage.isEmpty()) && (mask & 1 << j) != 0) {
                total += storage.getData().getSerializedSize();
                total += storage.getBlockLight().getData().length;

                if (hasSkyLight) {
                    total += storage.getSkyLight().getData().length;
                }
            }
        }
        total += chunk.getBiomeArray().length;
        return total;
    }

    private static int writeData(PacketBuffer buf, ICube[] cubes, boolean hasSkylight, int mask) {
        int sentSections = 0;

        int cubeCount = cubes.length;
        for (int j = 0; j < cubeCount; ++j) {
            ExtendedBlockStorage storage = getStorage(cubes, j);

            if (storage != Chunk.NULL_BLOCK_STORAGE && (mask & 1 << j) != 0) {
                sentSections |= 1 << j;
                storage.getData().write(buf);
                buf.writeBytes(storage.getBlockLight().getData());

                if (hasSkylight) {
                    buf.writeBytes(storage.getSkyLight().getData());
                }
            }
        }

        return sentSections;
    }

    private static void writeData(PacketBuffer buf, Chunk chunk) {
        buf.writeBytes(chunk.getBiomeArray());
    }

    private static int writeData(Chunk chunk, PacketBuffer buf, ICube[] cubes, boolean hasSkylight, int mask) {
        int sentSections = 0;

        int cubeCount = cubes.length;
        for (int j = 0; j < cubeCount; ++j) {
            ExtendedBlockStorage storage = getStorage(cubes, j);

            if (storage != Chunk.NULL_BLOCK_STORAGE && (!storage.isEmpty()) && (mask & 1 << j) != 0) {
                sentSections |= 1 << j;
                storage.getData().write(buf);
                buf.writeBytes(storage.getBlockLight().getData());

                if (hasSkylight) {
                    buf.writeBytes(storage.getSkyLight().getData());
                }
            }
        }

        buf.writeBytes(chunk.getBiomeArray());

        return sentSections;
    }

    public boolean hasCubicChunks(EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return true;
        }
        NetHandlerPlayServer connection = player.connection;
        if (connection == null) { //if connection or connection.netManager is null, we're currently in the middle of the FML handshake
            return false;
        }
        NetworkManager netManager = connection.netManager;
        if (netManager == null) {
            return false;
        }
        Channel channel = netManager.channel();
        if (!channel.attr(NetworkRegistry.FML_MARKER).get())    {
            return false;
        }
        Attribute<NetworkDispatcher> attr = channel.attr(NetworkDispatcher.FML_DISPATCHER);
        NetworkDispatcher networkDispatcher = attr.get();
        if (networkDispatcher == null) {
            return false;
        }
        Map<String, String> modList = networkDispatcher.getModList();
        return modList.containsKey("cubicchunks");
    }

    public BlockPos modifyPositionC2S(BlockPos position, EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return position;
        }
        BlockPos offset = this.getPlayerOffsetC2S(player).getMinBlockPos();
        return new BlockPos(
                position.getX() - offset.getX(),
                position.getY() - offset.getY(),
                position.getZ() - offset.getZ()
        );
    }

    public BlockPos getC2SOffset(EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return BlockPos.ORIGIN;
        }
        return this.getPlayerOffsetC2S(player).getMinBlockPos();
    }

    public BlockPos getS2COffset(EntityPlayerMP player) {
        if (!CubicChunksConfig.allowVanillaClients) {
            return BlockPos.ORIGIN;
        }
        return getPlayerOffsetS2C(player).getMinBlockPos();
    }
}
