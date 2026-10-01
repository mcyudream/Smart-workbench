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
 * 客户端 → 服务器：在 JEI(HEI) 的合成表上点了配方旁边的加号。
 * <p>
 * 缺料判断已经在客户端做完了（见 compat 包，那里把接入存储也算成可用材料），
 * 所以这个包只负责说清「把哪份配方摆进合成格」。
 */
public class C2SPlaceRecipePacket implements IMessage {

    private BlockPos pos;
    private ResourceLocation recipeId;
    private boolean stackAll;

    public C2SPlaceRecipePacket() {
    }

    public C2SPlaceRecipePacket(BlockPos pos, ResourceLocation recipeId, boolean stackAll) {
        this.pos = pos;
        this.recipeId = recipeId;
        this.stackAll = stackAll;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        this.pos = BlockPos.fromLong(buffer.readLong());
        this.recipeId = PacketIO.readResourceLocation(buffer);
        this.stackAll = buffer.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        buffer.writeLong(this.pos.toLong());
        PacketIO.writeResourceLocation(buffer, this.recipeId);
        buffer.writeBoolean(this.stackAll);
    }

    public static final class Handler implements IMessageHandler<C2SPlaceRecipePacket, IMessage> {

        @Override
        public IMessage onMessage(final C2SPlaceRecipePacket message, final MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            PacketIO.schedule(ctx, new Runnable() {
                @Override
                public void run() {
                    if (player != null && player.openContainer instanceof SmartWorkbenchContainer) {
                        SmartWorkbenchContainer container = (SmartWorkbenchContainer) player.openContainer;
                        if (container.getBenchPos().equals(message.pos) && container.canInteractWith(player)) {
                            container.placeRecipeFromClient(player, message.recipeId, message.stackAll);
                        }
                    }
                }
            });
            return null;
        }
    }
}
