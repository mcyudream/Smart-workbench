package cn.blockforge.smartworkbench.network;

import cn.blockforge.smartworkbench.container.SmartWorkbenchContainer;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 → 服务器：玩家对某个配方点了「自动合成」
 * （界面上的自动合成按钮、快捷键，或列表里 Alt+左键点击）。
 * <p>
 * 服务器会直接从接入的存储取料，缺的中间材料按依赖递归补齐，
 * 一直做到配置里的上限为止，产物优先送进绑定的输出容器。
 */
public class C2SAutoCraftPacket implements IMessage {

    private BlockPos pos;
    private ResourceLocation recipeId;

    public C2SAutoCraftPacket() {
    }

    public C2SAutoCraftPacket(BlockPos pos, ResourceLocation recipeId) {
        this.pos = pos;
        this.recipeId = recipeId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        this.pos = BlockPos.fromLong(buffer.readLong());
        this.recipeId = PacketIO.readResourceLocation(buffer);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        buffer.writeLong(this.pos.toLong());
        PacketIO.writeResourceLocation(buffer, this.recipeId);
    }

    public static final class Handler implements IMessageHandler<C2SAutoCraftPacket, IMessage> {

        @Override
        public IMessage onMessage(final C2SAutoCraftPacket message, final MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            PacketIO.schedule(ctx, new Runnable() {
                @Override
                public void run() {
                    if (player != null && player.openContainer instanceof SmartWorkbenchContainer) {
                        SmartWorkbenchContainer container = (SmartWorkbenchContainer) player.openContainer;
                        if (container.getBenchPos().equals(message.pos) && container.canInteractWith(player)) {
                            container.autoCraftFromStorage(player, message.recipeId);
                        }
                    }
                }
            });
            return null;
        }
    }
}
