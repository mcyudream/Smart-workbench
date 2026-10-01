package cn.blockforge.smartworkbench;

import cn.blockforge.smartworkbench.network.C2SAutoCraftPacket;
import cn.blockforge.smartworkbench.network.C2SCraftRequestPacket;
import cn.blockforge.smartworkbench.network.C2SPlaceRecipePacket;
import cn.blockforge.smartworkbench.network.C2SRecursiveCraftPacket;
import cn.blockforge.smartworkbench.network.S2CAutoRefillPacket;
import cn.blockforge.smartworkbench.network.S2CCraftableListPacket;
import cn.blockforge.smartworkbench.network.S2CRecursiveCraftPromptPacket;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 网络通道：服务器把「可合成列表 + 接入存储快照」推给客户端；
 * 客户端把「点了列表里的哪个配方」（含 Ctrl 批量合成）和「JEI 点了加号要摆哪份配方」发回服务器。
 */
public final class ModNetwork {

    private static SimpleNetworkWrapper channel;

    private ModNetwork() {
    }

    public static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(SmartWorkbenchMod.MOD_ID);
        channel.registerMessage(S2CCraftableListPacket.Handler.class, S2CCraftableListPacket.class, 0, Side.CLIENT);
        channel.registerMessage(C2SCraftRequestPacket.Handler.class, C2SCraftRequestPacket.class, 1, Side.SERVER);
        channel.registerMessage(C2SPlaceRecipePacket.Handler.class, C2SPlaceRecipePacket.class, 2, Side.SERVER);
        channel.registerMessage(C2SRecursiveCraftPacket.Handler.class, C2SRecursiveCraftPacket.class, 3, Side.SERVER);
        channel.registerMessage(S2CRecursiveCraftPromptPacket.Handler.class, S2CRecursiveCraftPromptPacket.class, 4, Side.CLIENT);
        channel.registerMessage(C2SAutoCraftPacket.Handler.class, C2SAutoCraftPacket.class, 5, Side.SERVER);
        channel.registerMessage(S2CAutoRefillPacket.Handler.class, S2CAutoRefillPacket.class, 6, Side.CLIENT);
    }

    public static void sendToPlayer(EntityPlayerMP player, net.minecraftforge.fml.common.network.simpleimpl.IMessage packet) {
        channel.sendTo(packet, player);
    }

    public static void sendToServer(net.minecraftforge.fml.common.network.simpleimpl.IMessage packet) {
        channel.sendToServer(packet);
    }
}
