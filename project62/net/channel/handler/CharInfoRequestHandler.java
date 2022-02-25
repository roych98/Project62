package net.sf.odinms.net.channel.handler;

import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class CharInfoRequestHandler extends AbstractMaplePacketHandler {

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        c.getPlayer().resetAfkTime();
        long time = slea.readInt();
        int cid = slea.readInt();
        MapleCharacter player = (MapleCharacter) c.getPlayer().getMap().getMapObject(cid);
        if (player == null) {
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        if (player.getId() != cid) {
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        if (!player.isGM() || (c.getPlayer().isGM() && player.isGM())) {
            c.getSession().write(MaplePacketCreator.charInfo(player));
        } else {
            c.getSession().write(MaplePacketCreator.enableActions());
        }
    }
}