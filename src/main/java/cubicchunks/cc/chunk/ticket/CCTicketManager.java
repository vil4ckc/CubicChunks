package cubicchunks.cc.chunk.ticket;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.mojang.datafixers.util.Either;
import cubicchunks.cc.chunk.graph.CCTicketType;
import cubicchunks.cc.mixin.core.common.chunk.interfaces.InvokeChunkHolder;
import cubicchunks.cc.mixin.core.common.chunk.interfaces.InvokeChunkManager;
import cubicchunks.cc.mixin.core.common.ticket.interfaces.InvokeTicket;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.SectionDistanceGraph;
import net.minecraft.util.SortedArraySet;
import net.minecraft.util.concurrent.ITaskExecutor;
import net.minecraft.util.math.SectionPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ChunkManager;
import net.minecraft.world.server.Ticket;
import net.minecraft.world.server.TicketType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public abstract class CCTicketManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int PLAYER_TICKET_LEVEL = 33 + ChunkStatus.getDistance(ChunkStatus.FULL) - 2;
    private final Long2ObjectMap<ObjectSet<ServerPlayerEntity>> playersByChunkPos = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> tickets = new Long2ObjectOpenHashMap<>();
    private final CCTicketManager.CubeTicketTracker ticketTracker = new CCTicketManager.CubeTicketTracker();
    private final CCTicketManager.PlayerCubeTracker playerChunkTracker = new CCTicketManager.PlayerCubeTracker(8);
    private final CCTicketManager.PlayerTicketTracker playerTicketTracker = new CCTicketManager.PlayerTicketTracker(33);
    private final Set<ChunkHolder> chunkHolders = Sets.newHashSet();
    //TODO: perhaps have to make CubeTaskPriorityQueueSorter
    private final CubeTaskPriorityQueueSorter levelUpdateListener;
    private final ITaskExecutor<CubeTaskPriorityQueueSorter.FunctionEntry<Runnable>> playerTicketThrottler;
    private final ITaskExecutor<CubeTaskPriorityQueueSorter.RunnableEntry> playerTicketThrottlerSorter;
    private final LongSet sectionPositions = new LongOpenHashSet();
    private final Executor mainThreadExecutor;
    private long currentTime;

    protected CCTicketManager(Executor executor, Executor executor2) {
        ITaskExecutor<Runnable> itaskexecutor = ITaskExecutor.inline("player ticket throttler", executor2::execute);
        CubeTaskPriorityQueueSorter CubeTaskPriorityQueueSorter = new CubeTaskPriorityQueueSorter(ImmutableList.of(itaskexecutor), executor, 4);
        this.levelUpdateListener = CubeTaskPriorityQueueSorter;
        this.playerTicketThrottler = CubeTaskPriorityQueueSorter.createExecutor(itaskexecutor, true);
        this.playerTicketThrottlerSorter = CubeTaskPriorityQueueSorter.func_219091_a(itaskexecutor);
        this.mainThreadExecutor = executor2;
    }

    protected void tick() {
        ++this.currentTime;
        ObjectIterator<Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>>> objectiterator = this.tickets.long2ObjectEntrySet().fastIterator();

        while(objectiterator.hasNext()) {
            Long2ObjectMap.Entry<SortedArraySet<Ticket<?>>> entry = objectiterator.next();
            if (entry.getValue().removeIf((ticket) -> ((InvokeTicket)ticket).isexpiredCC(this.currentTime))) {
                this.ticketTracker.updateSourceLevel(entry.getLongKey(), getLevel(entry.getValue()), false);
            }

            if (entry.getValue().isEmpty()) {
                objectiterator.remove();
            }
        }

    }

    private static int getLevel(SortedArraySet<Ticket<?>> ticketSet) {
        return !ticketSet.isEmpty() ? ticketSet.getSmallest().getLevel() : ChunkManager.MAX_LOADED_LEVEL + 1;
    }

    protected abstract boolean contains(long p_219371_1_);

    @Nullable
    protected abstract ChunkHolder getChunkHolder(long sectionPosIn);

    @Nullable
    protected abstract ChunkHolder setChunkLevel(long sectionPosIn, int newLevel, @Nullable ChunkHolder holder, int oldLevel);

    public boolean processUpdates(ChunkManager chunkManager) {
        this.playerChunkTracker.processAllUpdates();
        this.playerTicketTracker.processAllUpdates();
        int i = Integer.MAX_VALUE - this.ticketTracker.update(Integer.MAX_VALUE);
        boolean flag = i != 0;

        if (!this.chunkHolders.isEmpty()) {
            this.chunkHolders.forEach((chunkHolder) -> ((InvokeChunkHolder)chunkHolder).processUpdatesCC(chunkManager));
            this.chunkHolders.clear();
            return true;
        } else {
            if (!this.sectionPositions.isEmpty()) {
                LongIterator longiterator = this.sectionPositions.iterator();

                while(longiterator.hasNext()) {
                    long j = longiterator.nextLong();
                    if (this.getTicketSet(j).stream().anyMatch((p_219369_0_) -> p_219369_0_.getType() == TicketType.PLAYER)) {
                        ChunkHolder chunkholder = ((InvokeChunkManager)chunkManager).chunkHold(j);
                        if (chunkholder == null) {
                            throw new IllegalStateException();
                        }

                        CompletableFuture<Either<Chunk, ChunkHolder.IChunkLoadingError>> completablefuture = chunkholder.getEntityTickingFuture();
                        completablefuture.thenAccept((p_219363_3_) -> this.mainThreadExecutor.execute(() -> {
                            this.playerTicketThrottlerSorter.enqueue(CubeTaskPriorityQueueSorter.func_219073_a(() -> {
                            }, j, false));
                        }));
                    }
                }

                this.sectionPositions.clear();
            }

            return flag;
        }
    }

    private void register(long sectionPosIn, Ticket<?> ticketIn) {
        SortedArraySet<Ticket<?>> sortedarrayset = this.getTicketSet(sectionPosIn);
        int i = getLevel(sortedarrayset);
        Ticket<?> ticket = sortedarrayset.func_226175_a_(ticketIn);
        ((InvokeTicket)ticket).setTimestampCC(this.currentTime);
        if (ticketIn.getLevel() < i) {
            this.ticketTracker.updateSourceLevel(sectionPosIn, ticketIn.getLevel(), true);
        }

    }

    private void release(long sectionPosIn, Ticket<?> ticketIn) {
        SortedArraySet<Ticket<?>> sortedarrayset = this.getTicketSet(sectionPosIn);
        sortedarrayset.remove(ticketIn);

        if (sortedarrayset.isEmpty()) {
            this.tickets.remove(sectionPosIn);
        }

        this.ticketTracker.updateSourceLevel(sectionPosIn, getLevel(sortedarrayset), false);
    }

    public <T> void registerWithLevel(TicketType<T> type, SectionPos pos, int level, T value) {
        this.register(pos.asLong(), new Ticket<>(type, level, value));
    }

    public <T> void releaseWithLevel(TicketType<T> type, SectionPos pos, int level, T value) {
        Ticket<T> ticket = new Ticket<>(type, level, value);
        this.release(pos.asLong(), ticket);
    }

    public <T> void register(TicketType<T> type, SectionPos pos, int distance, T value) {
        this.register(pos.asLong(), new Ticket<>(type, 33 - distance, value));
    }

    public <T> void release(TicketType<T> type, SectionPos pos, int distance, T value) {
        Ticket<T> ticket = new Ticket<>(type, 33 - distance, value);
        this.release(pos.asLong(), ticket);
    }

    private SortedArraySet<Ticket<?>> getTicketSet(long p_229848_1_) {
        return this.tickets.computeIfAbsent(p_229848_1_, (p_229851_0_) -> SortedArraySet.newSet(4));
    }

    protected void forceCube(SectionPos pos, boolean add) {
        Ticket<SectionPos> ticket = new Ticket<>(CCTicketType.CCFORCED, 31, pos);
        if (add) {
            this.register(pos.asLong(), ticket);
        } else {
            this.release(pos.asLong(), ticket);
        }

    }

    public void updatePlayerPosition(SectionPos sectionPosIn, ServerPlayerEntity player) {
        long i = sectionPosIn.asChunkPos().asLong();
        this.playersByChunkPos.computeIfAbsent(i, (p_219361_0_) -> new ObjectOpenHashSet<>()).add(player);
        this.playerChunkTracker.updateSourceLevel(i, 0, true);
        this.playerTicketTracker.updateSourceLevel(i, 0, true);
    }

    public void removePlayer(SectionPos sectionPosIn, ServerPlayerEntity player) {
        long i = sectionPosIn.asChunkPos().asLong();
        ObjectSet<ServerPlayerEntity> objectset = this.playersByChunkPos.get(i);
        objectset.remove(player);
        if (objectset.isEmpty()) {
            this.playersByChunkPos.remove(i);
            this.playerChunkTracker.updateSourceLevel(i, Integer.MAX_VALUE, false);
            this.playerTicketTracker.updateSourceLevel(i, Integer.MAX_VALUE, false);
        }

    }

    protected String func_225413_c(long p_225413_1_) {
        SortedArraySet<Ticket<?>> sortedarrayset = this.tickets.get(p_225413_1_);
        String s;
        if (sortedarrayset != null && !sortedarrayset.isEmpty()) {
            s = sortedarrayset.getSmallest().toString();
        } else {
            s = "no_ticket";
        }

        return s;
    }

    protected void setViewDistance(int viewDistance) {
        this.playerTicketTracker.setViewDistance(viewDistance);
    }

    /**
     * Returns the number of chunks taken into account when calculating the mob cap
     */
    public int getSpawningChunksCount() {
        this.playerChunkTracker.processAllUpdates();
        return this.playerChunkTracker.chunksInRange.size();
    }

    public boolean isOutsideSpawningRadius(long sectionPosIn) {
        this.playerChunkTracker.processAllUpdates();
        return this.playerChunkTracker.chunksInRange.containsKey(sectionPosIn);
    }

    public String func_225412_c() {
        return this.levelUpdateListener.func_225396_a();
    }
    public class CubeTicketTracker extends SectionDistanceGraph
    {
        public CubeTicketTracker() {
            //TODO: change the arguments passed into super to CCCubeManager or CCColumnManager
            super(ChunkManager.MAX_LOADED_LEVEL + 2, 16, 256);
        }

        @Override
        protected int getSourceLevel(long pos) {
            SortedArraySet<Ticket<?>> sortedarrayset = CCTicketManager.this.tickets.get(pos);
            if (sortedarrayset == null) {
                return Integer.MAX_VALUE;
            } else {
                return sortedarrayset.isEmpty() ? Integer.MAX_VALUE : sortedarrayset.getSmallest().getLevel();
            }
        }

        @Override
        protected int getLevel(long sectionPosIn) {
            if (!CCTicketManager.this.contains(sectionPosIn)) {
                ChunkHolder chunkholder = CCTicketManager.this.getChunkHolder(sectionPosIn);
                if (chunkholder != null) {
                    return chunkholder.getChunkLevel();
                }
            }

            return ChunkManager.MAX_LOADED_LEVEL + 1;
        }

        @Override
        protected void setLevel(long sectionPosIn, int level) {
            ChunkHolder chunkholder = CCTicketManager.this.getChunkHolder(sectionPosIn);
            int i = chunkholder == null ? ChunkManager.MAX_LOADED_LEVEL + 1 : chunkholder.getChunkLevel();
            if (i != level) {
                chunkholder = CCTicketManager.this.setChunkLevel(sectionPosIn, level, chunkholder, i);
                if (chunkholder != null) {
                    CCTicketManager.this.chunkHolders.add(chunkholder);
                }

            }
        }

        public int update(int distance) {
            return this.processUpdates(distance);
        }
    }

    public class PlayerCubeTracker extends SectionDistanceGraph
    {
        protected final Long2ByteMap chunksInRange = new Long2ByteOpenHashMap();
        protected final int range;

        public PlayerCubeTracker(int i) {
            super(i + 2, 16, 256);
            this.range = i;
            this.chunksInRange.defaultReturnValue((byte)(i + 2));
        }

        protected int getLevel(long sectionPosIn) {
            return this.chunksInRange.get(sectionPosIn);
        }

        protected void setLevel(long sectionPosIn, int level) {
            byte b0;
            if (level > this.range) {
                b0 = this.chunksInRange.remove(sectionPosIn);
            } else {
                b0 = this.chunksInRange.put(sectionPosIn, (byte)level);
            }

            this.chunkLevelChanged(sectionPosIn, b0, level);
        }

        /**
         * Called after {@link CCTicketManager.PlayerCubeTracker#setLevel(long, int)} puts/removes chunk into/from {@link
         * #chunksInRange}.
         *
         * @param oldLevel Previous level of the chunk if it was smaller than {@link #range}, {@code range + 2} otherwise.
         */
        protected void chunkLevelChanged(long sectionPosIn, int oldLevel, int newLevel) {
        }

        protected int getSourceLevel(long pos) {
            return this.hasPlayerInChunk(pos) ? 0 : Integer.MAX_VALUE;
        }

        private boolean hasPlayerInChunk(long sectionPosIn) {
            ObjectSet<ServerPlayerEntity> objectset = CCTicketManager.this.playersByChunkPos.get(sectionPosIn);
            return objectset != null && !objectset.isEmpty();
        }

        public void processAllUpdates() {
            this.processUpdates(Integer.MAX_VALUE);
        }
    }

    public class PlayerTicketTracker extends PlayerCubeTracker {
        private int viewDistance;
        private final Long2IntMap distances = Long2IntMaps.synchronize(new Long2IntOpenHashMap());
        private final LongSet positionsAffected = new LongOpenHashSet();

        protected PlayerTicketTracker(int i) {
            super(i);
            this.viewDistance = 0;
            this.distances.defaultReturnValue(i + 2);
        }

        /**
         * Called after {@link CCTicketManager.PlayerCubeTracker#setLevel(long, int)} puts/removes chunk into/from {@link
         * #chunksInRange}.
         *
         * @param oldLevel Previous level of the chunk if it was smaller than {@link #range}, {@code range + 2} otherwise.
         */
        protected void chunkLevelChanged(long sectionPosIn, int oldLevel, int newLevel) {
            this.positionsAffected.add(sectionPosIn);
        }

        public void setViewDistance(int viewDistanceIn) {
            for(it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry entry : this.chunksInRange.long2ByteEntrySet()) {
                byte b0 = entry.getByteValue();
                long i = entry.getLongKey();
                this.updateTicket(i, b0, this.isWithinViewDistance(b0), b0 <= viewDistanceIn - 2);
            }

            this.viewDistance = viewDistanceIn;
        }

        private void updateTicket(long sectionPosIn, int distance, boolean oldWithinViewDistance, boolean withinViewDistance) {
            if (oldWithinViewDistance != withinViewDistance) {
                Ticket<?> ticket = new Ticket<>(CCTicketType.CCPLAYER, CCTicketManager.PLAYER_TICKET_LEVEL, SectionPos.from(sectionPosIn));
                if (withinViewDistance) {
                    CCTicketManager.this.playerTicketThrottler.enqueue(CubeTaskPriorityQueueSorter.func_219069_a(() -> {
                        CCTicketManager.this.mainThreadExecutor.execute(() -> {
                            if (this.isWithinViewDistance(this.getLevel(sectionPosIn))) {
                                CCTicketManager.this.register(sectionPosIn, ticket);
                                CCTicketManager.this.sectionPositions.add(sectionPosIn);
                            } else {
                                CCTicketManager.this.playerTicketThrottlerSorter.enqueue(CubeTaskPriorityQueueSorter.func_219073_a(() -> {
                                }, sectionPosIn, false));
                            }

                        });
                    }, sectionPosIn, () -> {
                        return distance;
                    }));
                } else {
                    CCTicketManager.this.playerTicketThrottlerSorter.enqueue(CubeTaskPriorityQueueSorter.func_219073_a(() -> {
                        CCTicketManager.this.mainThreadExecutor.execute(() -> {
                            CCTicketManager.this.release(sectionPosIn, ticket);
                        });
                    }, sectionPosIn, true));
                }
            }

        }

        public void processAllUpdates() {
            super.processAllUpdates();
            if (!this.positionsAffected.isEmpty()) {
                LongIterator longiterator = this.positionsAffected.iterator();

                while(longiterator.hasNext()) {
                    long i = longiterator.nextLong();
                    int j = this.distances.get(i);
                    int k = this.getLevel(i);
                    if (j != k) {
                        //func_219066_a = update level
                        CCTicketManager.this.levelUpdateListener.func_219066_a(SectionPos.from(i), () -> this.distances.get(i), k, (ix) -> {
                            if (ix >= this.distances.defaultReturnValue()) {
                                this.distances.remove(i);
                            } else {
                                this.distances.put(i, ix);
                            }

                        });
                        this.updateTicket(i, k, this.isWithinViewDistance(j), this.isWithinViewDistance(k));
                    }
                }

                this.positionsAffected.clear();
            }

        }

        private boolean isWithinViewDistance(int p_215505_1_) {
            return p_215505_1_ <= this.viewDistance - 2;
        }
    }
}