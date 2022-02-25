package net.sf.odinms.net.channel.handler;

import net.sf.odinms.client.*;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;
import net.sf.odinms.server.maps.SavedLocationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnterMTSHandler extends AbstractMaplePacketHandler {

    private static Logger log = LoggerFactory.getLogger(DistributeSPHandler.class);

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        if ((c.getPlayer().getMapId() >= 910000000 && c.getPlayer().getMapId() <= 910000021) 
                || (c.getPlayer().getMapId() < 100000000)
                || !c.getPlayer().isAlive()
                ) {
            c.getSession().write(MaplePacketCreator.enableActions());
            c.getPlayer().dropMessage(1, "You can not be warped to Free Market right now.");
            return;
        }
        if (c.getPlayer().getMap() == null || c.getPlayer().getEventInstance() != null || c.getChannelServer() == null) {
            c.getPlayer().dropMessage(1, "Please try again later.");
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        c.getPlayer().setPreviousFmMap(c.getPlayer().getMapId());
        c.getPlayer().saveLocation(SavedLocationType.FREE_MARKET);
        c.getPlayer().changeMap(910000000);
    }
}