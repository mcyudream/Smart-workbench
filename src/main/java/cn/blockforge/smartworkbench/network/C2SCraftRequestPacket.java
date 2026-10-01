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
 * 客户端 → 服务器：玩家点了右侧「可合成」列表里的某一项。
 * <p>
 * 三种点击：普通 = 把材料配到合成格；Shift = 从存储合成一次；按住批量合成键（默认 Ctrl）=
 * 批量合成直到材料用完。
 */
public class C2SCraftRequestPacket implements IMessage {

    private BlockPos pos;
    private ResourceLocation recipeId;
    /** true 表示不经过合成格，直接从接入的存储取材合成 */
    private boolean craft;
    /** true 表示反复合成（按住批量合成键点击），craft 必为 true */
    private boolean batch;

    public C2SCraftRequestPacket() {
    }

    public C2SCraftRequestPacket(BlockPos pos, ResourceLocation recipeId, boolean craft, boolean batch) {
        this.pos = pos;
        this.recipeId = recipeId;
        this.craft = craft || batch;
        this.batch = batch;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        this.pos = BlockPos.fromLong(buffer.readLong());
        this.recipeId = PacketIO.readResourceLocation(buffer);
        this.craft = buffer.readBoolean();
        this.batch = buffer.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        buffer.writeLong(this.pos.toLong());
        PacketIO.writeResourceLocation(buffer, this.recipeId);
        buffer.writeBoolean(this.craft);
        buffer.writeBoolean(this.batch);
    }

    public static final class Handler implements IMessageHandler<C2SCraftRequestPacket, IMessage> {

        @Override
        public IMessage onMessage(final C2SCraftRequestPacket message, final MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            PacketIO.schedule(ctx, new Runnable() {
                @Override
                public void run() {
                    // 只认玩家当前真正打开的那台工作台，避免拿着旧界面乱点
                    if (player != null && player.openContainer instanceof SmartWorkbenchContainer) {
                        SmartWorkbenchContainer container = (SmartWorkbenchContainer) player.openContainer;
                        if (container.getBenchPos().equals(message.pos) && container.canInteractWith(player)) {
                            container.onRecipeListClick(player, message.recipeId, message.craft, message.batch);
                        }
                    }
                }
            });
            return null;
        }
    }
}
