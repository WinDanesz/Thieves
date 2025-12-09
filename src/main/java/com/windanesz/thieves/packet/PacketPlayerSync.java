package com.windanesz.thieves.packet;

import com.windanesz.thieves.Thieves;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * <b>[Server -> Client]</b>
 */
public class PacketPlayerSync implements IMessageHandler<PacketPlayerSync.Message, IMessage> {

	@Override
	public IMessage onMessage(Message message, MessageContext ctx) {
		// Just to make sure that the side is correct
		if (ctx.side.isClient()) {
			// Using a fully qualified name is a good course of action here; we don't really want to clutter the proxy
			// methods any more than necessary.
			net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> Thieves.proxy.handlePlayerSyncPacket(message));
		}

		return null;
	}

	public static class Message implements IMessage {

		public int hauntedProgress;
		public float robberyProgress;
		public int completedRobberies;
		public boolean scoutWarningActive;
		public int scoutVisitCount;

		// This constructor is required otherwise you'll get errors (used somewhere in fml through reflection)
		public Message() {
		}

		public Message(int hauntedProgress) {
			this.hauntedProgress = hauntedProgress;
		}

		public Message(int hauntedProgress, float robberyProgress, int completedRobberies, boolean scoutWarningActive, int scoutVisitCount) {
			this.hauntedProgress = hauntedProgress;
			this.robberyProgress = robberyProgress;
			this.completedRobberies = completedRobberies;
			this.scoutWarningActive = scoutWarningActive;
			this.scoutVisitCount = scoutVisitCount;
		}

		@Override
		public void fromBytes(ByteBuf buf) {

			this.hauntedProgress = buf.readInt();
			this.robberyProgress = buf.readFloat();
			this.completedRobberies = buf.readInt();
			this.scoutWarningActive = buf.readBoolean();
			this.scoutVisitCount = buf.readInt();

		}

		@Override
		@SuppressWarnings("unchecked")
		public void toBytes(ByteBuf buf) {
			buf.writeInt(hauntedProgress);
			buf.writeFloat(robberyProgress);
			buf.writeInt(completedRobberies);
			buf.writeBoolean(scoutWarningActive);
			buf.writeInt(scoutVisitCount);
		}
	}
}
