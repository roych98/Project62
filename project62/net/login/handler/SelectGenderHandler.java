package net.sf.odinms.net.login.handler;

import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.server.TimerManager;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class SelectGenderHandler extends AbstractMaplePacketHandler {
    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        byte type = slea.readByte();
        if (type == 0x01 && c.getGender() == 10) {
            c.setGender(slea.readByte());
            c.getSession().write(MaplePacketCreator.onCheckPassword(c));  
            final MapleClient client = c;
            c.setIdleTask(TimerManager.getInstance().schedule(new Runnable() {
                @Override
                public void run() {
                    client.getSession().close();
                }
            }, 600000));
        } else {
        }
    }
}