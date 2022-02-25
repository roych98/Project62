package net.sf.odinms.net.login.handler;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.constants.ItemConstants;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.net.login.LoginServer;
import net.sf.odinms.tools.FilePrinter;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CharSelectedHandler extends AbstractMaplePacketHandler {
    private static Logger log = LoggerFactory.getLogger(CharSelectedHandler.class);

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        int charId = slea.readInt();
        String macs = slea.readMapleAsciiString();
        c.updateMacs(macs);
        if (c.hasBannedMac()) {
            c.getSession().close();
            return;
        } else if(c.hasBannedHWID()) {
            c.getSession().close();
            return;
        }
        if (macs.equalsIgnoreCase("00-50-56-C0-00-08") || macs.equalsIgnoreCase("00-50-56-C0-00-01")) {
            FilePrinter.printError(FilePrinter.susMac + c.getAccountName() +".txt", c.getAccountName() + " is using a MAC that is considered suspicious! \n");
        }
//        if (macs.isEmpty()) {
//            c.getSession().close();
//            return;
//        }
        try {
            if (c.getIdleTask() != null) {
                c.getIdleTask().cancel(true);
            }
            //c.getSession().write(MaplePacketCreator.getServerIP(InetAddress.getByName("127.0.0.1"), 7575, charId));
            c.updateLoginState(MapleClient.LOGIN_SERVER_TRANSITION, c.getSessionIPAddress());
            String channelServerIP = MapleClient.getChannelServerIPFromSubnet(c.getSession().getRemoteAddress().toString().replace("/", "").split(":")[0], c.getChannel());
            if(channelServerIP.equals("0.0.0.0")) {
                String[] socket = LoginServer.getInstance().getIP(c.getChannel()).split(":");
                c.getSession().write(MaplePacketCreator.getServerIP(InetAddress.getByName(socket[0]), Integer.parseInt(socket[1]), charId));
            } else {
                String[] socket = LoginServer.getInstance().getIP(c.getChannel()).split(":");
                c.getSession().write(MaplePacketCreator.getServerIP(InetAddress.getByName(channelServerIP), Integer.parseInt(socket[1]), charId));
            }
        } catch (UnknownHostException e) {
            log.error("Host not found", e);
        }
    }
}