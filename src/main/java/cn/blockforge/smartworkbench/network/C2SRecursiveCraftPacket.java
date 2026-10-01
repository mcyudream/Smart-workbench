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

/** 客户端确认或取消“自动补齐中间材料”。 */
public class C2SRecursiveCraftPacket implements IMessage {

    private BlockPos pos;
    private ResourceLocation recipeId;
    private boolean accepted;

    public C2SRecursiveCraftPacket() {
    }

    public C2SRecursiveCraftPacket(BlockPos pos, ResourceLocation recipeId, boolean accepted) {
        this.pos = pos;
        this.recipeId = recipeId;
        this.accepted = accepted;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        this.pos = BlockPos.fromLong(buffer.readLong());
        this.recipeId = PacketIO.readResourceLocation(buffer);
        this.accepted = buffer.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        buffer.writeLong(this.pos.toLong());
        PacketIO.writeResourceLocation(buffer, this.recipeId);
        buffer.writeBoolean(this.accepted);
    }

    public static final class Handler implements IMessageHandler<C2SRecursiveCraftPacket, IMessage> {

        @Override
        public IMessage onMessage(final C2SRecursiveCraftPacket message, final MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            PacketIO.schedule(ctx, new Runnable() {
                @Override
                public void run() {
                    if (player != null && player.openContainer instanceof SmartWorkbenchContainer) {
                        SmartWorkbenchContainer container = (SmartWorkbenchContainer) player.openContainer;
                        if (container.getBenchPos().equals(message.pos) && container.canInteractWith(player)) {
                            container.confirmRecursiveCraft(player, message.recipeId, message.accepted);
                        }
                    }
                }
            });
            return null;
        }
    }
}
