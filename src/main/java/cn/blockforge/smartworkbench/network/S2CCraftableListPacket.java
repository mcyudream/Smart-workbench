package cn.blockforge.smartworkbench.network;

import cn.blockforge.smartworkbench.container.CraftableEntry;
import cn.blockforge.smartworkbench.container.SmartWorkbenchContainer;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 服务器 → 客户端：推送「附近存储里现在能合成什么」这份列表，
 * 外加一份接入存储的内容快照（同类物品已合并）。
 * <p>
 * 快照是给 JEI 用的：JEI 判断「原料够不够」时只看玩家背包和菜单里的槽位，
 * 看不到我们接在 IItemHandler 上的箱子，所以它老喊缺料。拿到快照后，
 * {@code compat} 包里的自定义转移处理器就能把箱子里的材料算进去。
 */
public class S2CCraftableListPacket implements IMessage {

    /** 快照里单个物品数量的上限，防止异常数据把包撑爆。 */
    private static final int MAX_SNAPSHOT_COUNT = 1_000_000;

    private BlockPos pos;
    private List<CraftableEntry> entries;
    private List<ItemStack> storageStacks;
    private int storageCount;
    private int outputCount;

    public S2CCraftableListPacket() {
    }

    public S2CCraftableListPacket(BlockPos pos, List<CraftableEntry> entries,
                                  List<ItemStack> storageStacks, int storageCount, int outputCount) {
        this.pos = pos;
        this.entries = new ArrayList<CraftableEntry>(entries);
        this.storageStacks = new ArrayList<ItemStack>(storageStacks);
        this.storageCount = Math.max(0, storageCount);
        this.outputCount = Math.max(0, outputCount);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        this.pos = BlockPos.fromLong(buffer.readLong());
        int size = buffer.readVarInt();
        List<CraftableEntry> list = new ArrayList<CraftableEntry>(size);
        for (int i = 0; i < size; i++) {
            CraftableEntry entry = CraftableEntry.read(buffer);
            if (entry != null) {
                list.add(entry);
            }
        }
        this.entries = list;
        int storageSize = buffer.readVarInt();
        List<ItemStack> storage = new ArrayList<ItemStack>(storageSize);
        for (int i = 0; i < storageSize; i++) {
            ItemStack stack = PacketIO.readStack(buffer);
            if (!stack.isEmpty()) {
                storage.add(stack);
            }
        }
        this.storageStacks = storage;
        this.storageCount = buffer.readVarInt();
        this.outputCount = buffer.readVarInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        buffer.writeLong(this.pos.toLong());
        buffer.writeVarInt(this.entries.size());
        for (CraftableEntry entry : this.entries) {
            entry.write(buffer);
        }
        buffer.writeVarInt(this.storageStacks.size());
        for (ItemStack stack : this.storageStacks) {
            PacketIO.writeStack(buffer, stack);
        }
        buffer.writeVarInt(this.storageCount);
        buffer.writeVarInt(this.outputCount);
    }

    public List<CraftableEntry> entries() {
        return this.entries == null ? Collections.<CraftableEntry>emptyList() : this.entries;
    }

    public List<ItemStack> storageStacks() {
        return this.storageStacks == null ? Collections.<ItemStack>emptyList() : this.storageStacks;
    }

    public int storageCount() {
        return this.storageCount;
    }

    public int outputCount() {
        return this.outputCount;
    }

    public static final class Handler implements IMessageHandler<S2CCraftableListPacket, IMessage> {

        @Override
        public IMessage onMessage(final S2CCraftableListPacket message, final MessageContext ctx) {
            PacketIO.schedule(ctx, new Runnable() {
                @Override
                public void run() {
                    Minecraft minecraft = Minecraft.getMinecraft();
                    EntityPlayer player = minecraft.player;
                    if (player != null && player.openContainer instanceof SmartWorkbenchContainer) {
                        SmartWorkbenchContainer container = (SmartWorkbenchContainer) player.openContainer;
                        if (container.getBenchPos().equals(message.pos)) {
                            container.setClientCraftables(message.entries());
                            container.setClientStorageStacks(message.storageStacks());
                            container.setClientConnectedStorageCount(message.storageCount());
                            container.setClientOutputCount(message.outputCount());
                        }
                    }
                }
            });
            return null;
        }
    }
}
