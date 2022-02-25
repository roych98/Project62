package net.sf.odinms.net.login.handler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.database.DatabaseConnection;
import net.sf.odinms.net.MaplePacketHandler;
import net.sf.odinms.net.login.LoginServer;
import net.sf.odinms.net.login.LoginWorker;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;
import net.sf.odinms.tools.MaplePacketCreator;

public class GuestLoginHandler implements MaplePacketHandler {

    public boolean validateState(MapleClient c) {
        return !c.isLoggedIn();
    }

    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
  
    }
}