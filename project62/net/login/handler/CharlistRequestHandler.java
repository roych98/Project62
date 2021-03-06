package net.sf.odinms.net.login.handler;

import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class CharlistRequestHandler extends AbstractMaplePacketHandler {

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        try {
        int server = slea.readByte();
        int channel = slea.readByte() + 1;
        c.setWorld(server);
        c.setChannel(channel);
        c.sendCharList(server);
    } catch (StackOverflowError soe) {
        soe.printStackTrace();
    }
    }
}