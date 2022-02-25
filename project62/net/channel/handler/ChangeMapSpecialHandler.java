package net.sf.odinms.net.channel.handler;

import net.sf.odinms.client.MapleBuffStat;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.server.MaplePortal;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class ChangeMapSpecialHandler extends AbstractMaplePacketHandler {

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        c.getPlayer().resetAfkTime();
        if (c.getPlayer().getMap() == null || c.getPlayer() == null) {
            return;
        }
        slea.readByte(); //field byte stuff
        String startwp;
        startwp = slea.readMapleAsciiString();
        short xPos = slea.readShort();
        short yPos = slea.readShort();
        MaplePortal portal = c.getPlayer().getMap().getPortal(startwp);
        if (portal != null || c.getPlayer().portalDelay() > System.currentTimeMillis()) {
            portal.enterPortal(c);
        } else {
            c.getSession().write(MaplePacketCreator.enableActions());
        }
    }
}