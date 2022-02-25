package net.sf.odinms.net.channel.handler.npcHandlers;

import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.scripting.npc.NPCScriptManager;
import net.sf.odinms.server.life.MapleNPC;
import net.sf.odinms.server.maps.MapleMapObject;
import net.sf.odinms.server.maps.PlayerNPCs;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class TalkHandler extends AbstractMaplePacketHandler {

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        c.getPlayer().resetAfkTime();
        if (c.getPlayer().getLastSelectNPCTime()+200 > System.currentTimeMillis() ){
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        c.getPlayer().setLastSelectNPCTime(System.currentTimeMillis());
        if (!c.getPlayer().isAlive() || c.getPlayer() == null || c.getPlayer().getMap() == null) {
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        int oid = slea.readInt();
        slea.readInt();
        MapleMapObject obj = c.getPlayer().getMap().getMapObject(oid);
        if (obj instanceof MapleNPC) {
            MapleNPC npc = (MapleNPC) obj;
            if (NPCScriptManager.getInstance() != null)
                NPCScriptManager.getInstance().dispose(c);
            if (!c.getPlayer().getCheatTracker().Spam(1000, 4)) {
                if (npc.hasShop()) {
                    //destroy the old shop if one exists...
                    if (c.getPlayer().getShop() != null) {
                        c.getPlayer().setShop(null);
                        c.getSession().write(MaplePacketCreator.confirmShopTransaction((byte) 20));
                    }
                    npc.sendShop(c);
                } else {
                    if (c.getCM() != null || c.getQM() != null) {
                        c.getSession().write(MaplePacketCreator.enableActions());
                        return;
                    }
                    NPCScriptManager.getInstance().start(c, npc.getId());
                }
            }
        } else if (obj instanceof PlayerNPCs) {
            PlayerNPCs npc = (PlayerNPCs) obj;
            NPCScriptManager.getInstance().start(c, npc.getId(), npc.getName(), null);
        }
    }
}