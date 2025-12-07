package com.windanesz.thieves.network;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.packet.PacketPlayerSync;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class PacketHandler {

	public static SimpleNetworkWrapper net;
	private static int nextPacketId = 0;

	public static void initPackets() {
		net = NetworkRegistry.INSTANCE.newSimpleChannel(Thieves.MODID.toUpperCase());
		registerMessage(PacketPlayerSync.class, PacketPlayerSync.Message.class);
	}

	private static <REQ extends IMessage, REPLY extends IMessage> void registerMessage(
			Class<? extends IMessageHandler<REQ, REPLY>> packet, Class<REQ> message) {
		net.registerMessage(packet, message, nextPacketId, Side.CLIENT);
		net.registerMessage(packet, message, nextPacketId, Side.SERVER);
		nextPacketId++;
	}
}