package cn.blockforge.smartworkbench.network;

import cn.blockforge.smartworkbench.container.SmartWorkbenchContainer;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/** 服务器告诉客户端：缺少的材料可以递归合成，询问是否自动补齐。 */
public class S2CRecursiveCraftPromptPacket implements IMessage {

    /** 缺料清单里的一项：缺什么、缺多少、能不能由其他配方补出来。 */
    public static final class MissingEntry {
        public final ItemStack stack;
        public final int count;
        public final boolean craftable;

        public MissingEntry(ItemStack stack, int count, boolean craftable) {
            this.stack = stack;
            this.count = count;
            this.craftable = craftable;
        }
    }

    private BlockPos pos;
    private ResourceLocation recipeId;
    private List<MissingEntry> autoCraftable;
    private List<MissingEntry> unavailable;

    public S2CRecursiveCraftPromptPacket() {
    }

    public S2CRecursiveCraftPromptPacket(BlockPos pos, ResourceLocation recipeId,
                                         List<MissingEntry> autoCraftable, List<MissingEntry> unavailable) {
        this.pos = pos;
        this.recipeId = recipeId;
        this.autoCraftable = autoCraftable;
        this.unavailable = unavailable;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        this.pos = BlockPos.fromLong(buffer.readLong());
        this.recipeId = PacketIO.readResourceLocation(buffer);
        this.autoCraftable = readEntries(buffer);
        this.unavailable = readEntries(buffer);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        buffer.writeLong(this.pos.toLong());
        PacketIO.writeResourceLocation(buffer, this.recipeId);
        writeEntries(buffer, this.autoCraftable);
        writeEntries(buffer, this.unavailable);
    }

    private static List<MissingEntry> readEntries(PacketBuffer buffer) {
        int size = buffer.readVarInt();
        List<MissingEntry> entries = new ArrayList<MissingEntry>(size);
        for (int i = 0; i < size; i++) {
            ItemStack stack = PacketIO.readStack(buffer);
            int count = buffer.readVarInt();
            boolean craftable = buffer.readBoolean();
            if (!stack.isEmpty()) {
                entries.add(new MissingEntry(stack, count, craftable));
            }
        }
        return entries;
    }

    private static void writeEntries(PacketBuffer buffer, List<MissingEntry> entries) {
        buffer.writeVarInt(entries.size());
        for (MissingEntry entry : entries) {
            PacketIO.writeStack(buffer, entry.stack);
            buffer.writeVarInt(Math.max(1, entry.count));
            buffer.writeBoolean(entry.craftable);
        }
    }

    public static final class Handler implements IMessageHandler<S2CRecursiveCraftPromptPacket, IMessage> {

        @Override
        public IMessage onMessage(final S2CRecursiveCraftPromptPacket message, final MessageContext ctx) {
            PacketIO.schedule(ctx, new Runnable() {
                @Override
                public void run() {
                    Minecraft minecraft = Minecraft.getMinecraft();
                    EntityPlayer player = minecraft.player;
                    if (player != null && player.openContainer instanceof SmartWorkbenchContainer) {
                        SmartWorkbenchContainer container = (SmartWorkbenchContainer) player.openContainer;
                        if (container.getBenchPos().equals(message.pos)) {
                            container.setRecursivePrompt(message.recipeId, message.autoCraftable, message.unavailable);
                        }
                    }
                }
            });
            return null;
        }
    }
}
