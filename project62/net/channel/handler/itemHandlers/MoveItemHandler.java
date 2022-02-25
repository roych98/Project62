package net.sf.odinms.net.channel.handler.itemHandlers;

import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.MapleInventoryType;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.server.AutobanManager;
import net.sf.odinms.server.MapleInventoryManipulator;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class MoveItemHandler extends AbstractMaplePacketHandler {

    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        c.getPlayer().resetAfkTime();
        c.getPlayer().getLastRequestTime();
        long time = slea.readInt();
        if (c.getPlayer().getLastRequestTime() > time || c.getPlayer().getLastRequestTime() == time) { 
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        c.getPlayer().setLastRequestTime(time); 
        MapleInventoryType type = MapleInventoryType.getByType(slea.readByte());
        byte src = (byte) slea.readShort();
        byte dst = (byte) slea.readShort();
        long checkq = slea.readShort();
        short quantity = (short)(int)checkq;
        if (src < 0 && dst > 0) {
            MapleInventoryManipulator.unequip(c, src, dst);
        } else if (dst < 0) {
            MapleInventoryManipulator.equip(c, src, dst);
        } else if (dst == 0) {
            if (c.getPlayer().getInventory(type).getItem(src) == null) return;
            if (checkq > 4000 || checkq < 1) {
                AutobanManager.getInstance().autoban(c, "Player attempted a dupe("+c.getPlayer().getInventory(type).getItem(src).getItemId()+").");
                return;
            }
            MapleInventoryManipulator.drop(c, type, src, quantity);
        } else {
            MapleInventoryManipulator.move(c, type, src, dst);
        }
    }
}