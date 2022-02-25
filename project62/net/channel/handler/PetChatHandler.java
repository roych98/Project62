package net.sf.odinms.net.channel.handler;

import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.PetDataFactory;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class PetChatHandler extends AbstractMaplePacketHandler {

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        int petId = slea.readInt();
        slea.readInt();
        int slot = c.getPlayer().getPetIndex(petId);
        if (slot == -1) {
            return;
        }
        int petItemId = c.getPlayer().getPet(slot).getItemId();
        // int unknownShort = slea.readShort();
        byte nType = slea.readByte();
        byte nAction = slea.readByte();
        String text = slea.readMapleAsciiString();
        MapleCharacter player = c.getPlayer();
        if (nAction < 1 || PetDataFactory.IsValidPetAction(petItemId, nAction)) {
            player.getMap().broadcastMessage(player, MaplePacketCreator.petChat(player.getId(), nType, nAction, text, slot, c.getPlayer().haveItem(1832000, 1, true, false)), true);
        }
    }
}