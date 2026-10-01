package cn.blockforge.smartworkbench.network;

import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.io.IOException;

/** 网络包读写的小工具：ResourceLocation 在 1.12.2 走字符串通道。 */
public final class PacketIO {

    private PacketIO() {
    }

    /**
     * 1.12.2 的网络包处理器跑在网络线程上，动游戏世界前必须调度回主线程。
     */
    public static void schedule(MessageContext ctx, Runnable task) {
        IThreadListener thread = FMLCommonHandler.instance().getWorldThread(ctx.netHandler);
        if (thread != null && !thread.isCallingFromMinecraftThread()) {
            thread.addScheduledTask(task);
        } else {
            task.run();
        }
    }

    public static void writeResourceLocation(PacketBuffer buf, @Nullable ResourceLocation location) {
        buf.writeString(location == null ? "" : location.toString());
    }

    @Nullable
    public static ResourceLocation readResourceLocation(PacketBuffer buf) {
        String text = buf.readString(256);
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return new ResourceLocation(text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 写一个带数量的物品堆：原版 writeItemStack 的数量字段只有一个字节（-128~127），
     * 超过 127 会被截断。所以物品按 1 个写、真实数量另用 varint 传。
     */
    public static void writeStack(PacketBuffer buf, ItemStack stack) {
        if (stack.isEmpty()) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        ItemStack single = stack.copy();
        single.setCount(1);
        buf.writeItemStack(single);
        buf.writeVarInt(stack.getCount());
    }

    public static ItemStack readStack(PacketBuffer buf) {
        if (!buf.readBoolean()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack;
        try {
            stack = buf.readItemStack();
        } catch (IOException e) {
            return ItemStack.EMPTY;
        }
        int count = buf.readVarInt();
        if (!stack.isEmpty()) {
            stack.setCount(Math.max(1, count));
        }
        return stack;
    }
}
