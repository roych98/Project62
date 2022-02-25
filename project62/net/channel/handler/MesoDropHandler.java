package net.sf.odinms.net.channel.handler;

import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.net.channel.ChannelServer;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class MesoDropHandler extends AbstractMaplePacketHandler {

    public MesoDropHandler() {
    }

    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        c.getPlayer().resetAfkTime();
        c.getPlayer().getLastRequestTime();
        long time = slea.readInt();
        if (c.getPlayer().getLastRequestTime() > time || c.getPlayer().getLastRequestTime() == time) { 
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        int meso = slea.readInt();
        c.getPlayer().setLastRequestTime(time); 
        if (!c.getPlayer().isAlive()) {
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        if (meso <= c.getPlayer().getMeso() && meso >= 10 && meso <= 50000) {
            c.getPlayer().gainMeso(-meso, false, true);
            c.getPlayer().getMap().spawnMesoDrop(meso, meso, c.getPlayer().getPosition(), c.getPlayer(), c.getPlayer(), false);
        } else {
            for (ChannelServer cs : ChannelServer.getAllInstances()) {
                cs.broadcastGMPacket(MaplePacketCreator.serverNotice(6, "[MESO HACK] Please be aware of player " + c.getPlayer() + " in channel " + c.getChannel()));
            }
            c.getPlayer().setMeso(0);
            return;
        }
    }
}
