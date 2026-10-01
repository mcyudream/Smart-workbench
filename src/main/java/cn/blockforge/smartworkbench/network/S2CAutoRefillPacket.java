package cn.blockforge.smartworkbench.network;

import cn.blockforge.smartworkbench.container.SmartWorkbenchContainer;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务器 → 客户端：把「自动补齐」开关的真实状态告诉界面。
 * <p>
 * 为什么不用原版的进度条同步：那些值只在变化时才发包，而内部记住的初值是 0。
 * 玩家上次关掉了自动补齐，重开界面时两边一比较「没变」，这一包就永远不发，
 * 客户端就一直用自己默认的「开」。所以这里在界面打开时明确发一次。
 */
public class S2CAutoRefillPacket implements IMessage {

    private BlockPos pos;
    private boolean enabled;

    public S2CAutoRefillPacket() {
    }

    public S2CAutoRefillPacket(BlockPos pos, boolean enabled) {
        this.pos = pos;
        this.enabled = enabled;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        this.pos = BlockPos.fromLong(buffer.readLong());
        this.enabled = buffer.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer buffer = new PacketBuffer(buf);
        buffer.writeLong(this.pos.toLong());
        buffer.writeBoolean(this.enabled);
    }

    public static final class Handler implements IMessageHandler<S2CAutoRefillPacket, IMessage> {

        @Override
        public IMessage onMessage(final S2CAutoRefillPacket message, final MessageContext ctx) {
            PacketIO.schedule(ctx, new Runnable() {
                @Override
                public void run() {
                    Minecraft minecraft = Minecraft.getMinecraft();
                    EntityPlayer player = minecraft.player;
                    if (player != null && player.openContainer instanceof SmartWorkbenchContainer) {
                        SmartWorkbenchContainer container = (SmartWorkbenchContainer) player.openContainer;
                        if (container.getBenchPos().equals(message.pos)) {
                            container.setAutoRefillEnabled(message.enabled);
                        }
                    }
                }
            });
            return null;
        }
    }
}
