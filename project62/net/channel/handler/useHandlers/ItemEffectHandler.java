package net.sf.odinms.net.channel.handler.useHandlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.sf.odinms.client.IItem;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.MapleInventoryType;
import net.sf.odinms.client.anticheat.CheatingOffense;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.server.maps.FakeCharacter;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class ItemEffectHandler extends AbstractMaplePacketHandler {

    private static Logger log = LoggerFactory.getLogger(ItemHandler.class);

    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        c.getPlayer().resetAfkTime();
        int itemId = slea.readInt();

        if (itemId >= 5000000 && itemId <= 5000053) {
            //log.warn(slea.toString());
        }

        if (itemId != 0) {
            IItem toUse = c.getPlayer().getInventory(MapleInventoryType.CASH).findById(itemId);

            if (toUse == null) {
                c.getPlayer().getCheatTracker().registerOffense(CheatingOffense.USING_UNAVAILABLE_ITEM, Integer.toString(itemId));
                return;
            }
        }
        c.getPlayer().setItemEffect(itemId);
        c.getPlayer().getMap().broadcastMessage(c.getPlayer(), MaplePacketCreator.itemEffect(c.getPlayer().getId(), itemId), false);
    }
}