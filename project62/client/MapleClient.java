package net.sf.odinms.client;

import java.io.UnsupportedEncodingException;
import java.rmi.RemoteException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import javax.script.ScriptEngine;
import net.sf.odinms.client.messages.MessageCallback;
import net.sf.odinms.database.DatabaseConnection;
import net.sf.odinms.database.DatabaseException;
import net.sf.odinms.net.channel.ChannelServer;
import net.sf.odinms.net.login.LoginServer;
import net.sf.odinms.net.world.MapleMessengerCharacter;
import net.sf.odinms.net.world.MapleParty;
import net.sf.odinms.net.world.MaplePartyCharacter;
import net.sf.odinms.net.world.PartyOperation;
import net.sf.odinms.net.world.PlayerCoolDownValueHolder;
import net.sf.odinms.net.world.guild.MapleGuildCharacter;
import net.sf.odinms.net.world.remote.WorldChannelInterface;
import net.sf.odinms.scripting.npc.NPCConversationManager;
import net.sf.odinms.scripting.npc.NPCScriptManager;
import net.sf.odinms.scripting.quest.QuestActionManager;
import net.sf.odinms.scripting.quest.QuestScriptManager;
import net.sf.odinms.server.MapleInventoryManipulator;
import net.sf.odinms.server.MapleTrade;
import net.sf.odinms.server.PlayerInteraction.HiredMerchant;
import net.sf.odinms.server.PlayerInteraction.IPlayerInteractionManager;
import net.sf.odinms.server.PlayerInteraction.MaplePlayerShopItem;
import net.sf.odinms.server.PublicChatHandler;
import net.sf.odinms.server.TimerManager;
import net.sf.odinms.server.maps.MapleMap;
import net.sf.odinms.tools.HexTool;
import net.sf.odinms.tools.IPAddressTool;
import net.sf.odinms.tools.MapleAESOFB;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.Pair;
import org.apache.mina.common.IoSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapleClient {

    public static final int LOGIN_NOTLOGGEDIN = 0;
    public static final int LOGIN_SERVER_TRANSITION = 1;
    public static final int LOGIN_LOGGEDIN = 2;
    public static final int LOGIN_WAITING = 3;
    public static final String CLIENT_KEY = "CLIENT";
    private static final Logger log = LoggerFactory.getLogger(MapleClient.class);
    private MapleAESOFB send;
    private MapleAESOFB receive;
    private IoSession session;
    private MapleCharacter player;
    private int channel = 1;
    private int accId = 1;
    private boolean loggedIn = false;
    private boolean serverTransition = false;
    private Calendar birthday = null;
    private Calendar tempban = null;
    private String accountName;
    private String accountPass;
    private int world;
    private long lastPong, hwid;
    private boolean gm = false;
    private byte greason = 1;
	private byte gender = -1;
    private boolean guest;
    private Map<Pair<MapleCharacter, Integer>, Integer> timesTalked = new HashMap<Pair<MapleCharacter, Integer>, Integer>(); //npcid, times
    private Set<String> macs = new HashSet<String>();
    private Map<String, ScriptEngine> engines = new HashMap<String, ScriptEngine>();
    private ScheduledFuture<?> idleTask = null;
    private int attemptedLogins = 0;
    private List<String> SessionIPS;

    public MapleClient(MapleAESOFB send, MapleAESOFB receive, IoSession session) {
        this.send = send;
        this.receive = receive;
        this.session = session;
    }
    public static boolean checkHash(String hash, String type, String password) {
        try {
            MessageDigest digester = MessageDigest.getInstance(type);
            digester.update(password.getBytes("UTF-8"), 0, password.length());
            return HexTool.toString(digester.digest()).replace(" ", "").toLowerCase().equals(hash);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new RuntimeException("Encoding the string failed", e);
        }
    }
    public MapleAESOFB getReceiveCrypto() {
        return receive;
    }

    public MapleAESOFB getSendCrypto() {
        return send;
    }

    public synchronized IoSession getSession() {
        return session;
    }

    public MapleCharacter getPlayer() {
        return player;
    }

    public void setPlayer(MapleCharacter player) {
        this.player = player;
    }

    public void sendCharList(int server) {
        this.session.write(MaplePacketCreator.getCharList(this, server));
    }

    public List<MapleCharacter> loadCharacters(int serverId) {

        List<MapleCharacter> chars = new LinkedList<MapleCharacter>();
        for (CharNameAndId cni : loadCharactersInternal(serverId)) {
            try {
                chars.add(MapleCharacter.loadCharFromDB(cni.id, this, false));
            } catch (SQLException e) {
                log.error("Loading characters failed", e);
            }
        }
        return chars;
    }

    public List<String> loadCharacterNames(int serverId) {
        List<String> chars = new LinkedList<String>();
        for (CharNameAndId cni : loadCharactersInternal(serverId)) {
            chars.add(cni.name);
        }
        return chars;
    }

    private List<CharNameAndId> loadCharactersInternal(int serverId) {
        Connection con = DatabaseConnection.getConnection();
        PreparedStatement ps;
        List<CharNameAndId> chars = new LinkedList<CharNameAndId>();
        try {
            ps = con.prepareStatement("SELECT id, name FROM characters WHERE accountid = ? AND world = ?");
            ps.setInt(1, this.accId);
            ps.setInt(2, serverId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                chars.add(new CharNameAndId(rs.getString("name"), rs.getInt("id")));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            log.error("THROW", e);
        }
        return chars;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    private Calendar getTempBanCalendar(ResultSet rs) throws SQLException {
        Calendar lTempban = Calendar.getInstance();
        long blubb = rs.getLong("tempban");
        if (blubb == 0) {
            lTempban.setTimeInMillis(0);
            return lTempban;
        }
        Calendar today = Calendar.getInstance();
        lTempban.setTimeInMillis(rs.getTimestamp("tempban").getTime());
        if (today.getTimeInMillis() < lTempban.getTimeInMillis()) {
            return lTempban;
        }
        lTempban.setTimeInMillis(0);
        return lTempban;
    }

    public Calendar getTempBanCalendar() {
        return tempban;
    }

    public byte getBanReason() {
        return greason;
    }

    public boolean hasBannedIP() {
        boolean ret = false;
        try {
            Connection con = DatabaseConnection.getConnection();
            PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM ipbans WHERE ? LIKE CONCAT(ip, '%')");
            ps.setString(1, getSessionIPAddress());
            ResultSet rs = ps.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) {
                ret = true;
            }
            rs.close();
            ps.close();
        } catch (SQLException ex) {
            log.error("Error checking ip bans", ex);
        }
        return ret;
    }

    public boolean hasBannedMac() {
        if (macs.isEmpty()) {
            return false;
        }
        boolean ret = false;
        int i = 0;
        try {
            Connection con = DatabaseConnection.getConnection();
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM macbans WHERE mac IN (");
            for (i = 0; i < macs.size(); i++) {
                sql.append("?");
                if (i != macs.size() - 1) {
                    sql.append(", ");
                }
            }
            sql.append(")");
            PreparedStatement ps = con.prepareStatement(sql.toString());
            i = 0;
            for (String mac : macs) {
                i++;
                ps.setString(i, mac);
            }
            ResultSet rs = ps.executeQuery();
            rs.next();
            if (rs.getInt(1) > 0) {
                ret = true;
            }
            rs.close();
            ps.close();
        } catch (SQLException ex) {
            log.error("Error checking mac bans", ex);
        }
        return ret;
    }
    
    public boolean hasBannedHWID() {
        Connection con = DatabaseConnection.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM hwidbans WHERE hwid = ?");
            ps.setLong(1, hwid);
            ResultSet rs = ps.executeQuery();
            boolean found = false;
            if(rs.next()) {
                found = true;
            }
            rs.close();
            ps.close();
            return found;
        } catch (SQLException e) {
            return false;
        }
    }
    
    public boolean banHWID() {
        Connection con = DatabaseConnection.getConnection();
        loadgIfNescessary();
        try {
            PreparedStatement ps = con.prepareStatement("INSERT INTO hwidbans (hwid) VALUES (?)");
            ps.setLong(1, hwid);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private void loadMacsIfNescessary() throws SQLException {
        if (macs.isEmpty()) {
            Connection con = DatabaseConnection.getConnection();
            PreparedStatement ps = con.prepareStatement("SELECT macs FROM accounts WHERE id = ?");
            ps.setInt(1, accId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String[] macData = rs.getString("macs").split(", ");
                for (String mac : macData) {
                    if (!mac.equals("")) {
                        macs.add(mac);
                    }
                }
            } else {
                throw new RuntimeException("No valid account associated with this client.");
            }
            rs.close();
            ps.close();
        }
    }

    public void banMacs() {
        Connection con = DatabaseConnection.getConnection();
        try {
            loadMacsIfNescessary();
            List<String> filtered = new LinkedList<String>();
            PreparedStatement ps = con.prepareStatement("SELECT filter FROM macfilters");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                filtered.add(rs.getString("filter"));
            }
            rs.close();
            ps.close();
            ps = con.prepareStatement("INSERT INTO macbans (mac) VALUES (?)");
            for (String mac : macs) {
                boolean matched = false;
                for (String filter : filtered) {
                    if (mac.matches(filter)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    ps.setString(1, mac);
                    try {
                        ps.executeUpdate();
                    } catch (SQLException e) {
                    }
                }
            }
            ps.close();
        } catch (SQLException e) {
            log.error("Error banning MACs", e);
        }
    }

    public int finishLogin(boolean success) {
        if (success) {
            synchronized (MapleClient.class) {
                if (getLoginState() > LOGIN_NOTLOGGEDIN && getLoginState() != LOGIN_WAITING) {
                    loggedIn = false;
                    return 7;
                }
            }
            updateLoginState(LOGIN_LOGGEDIN, getSessionIPAddress());
            try {
                Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("UPDATE accounts SET LastLoginInMilliseconds = ? WHERE id = ?");
                ps.setLong(1, System.currentTimeMillis());
                ps.setInt(2, getAccID());
                ps.executeUpdate();
                ps.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
            return 0;
        } else {
            return 10;
        }
    }

    public int login(String login, String pwd, boolean ipMacBanned) {
        int loginok = 5;
        attemptedLogins++;
        if (attemptedLogins > 4) {
            session.close();
        }
        Connection con = DatabaseConnection.getConnection();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = con.prepareStatement("SELECT id, password, salt, tempban, banned, gm, macs, lastknownip, greason, gender FROM accounts WHERE name = ?");
            ps.setString(1, login);
            rs = ps.executeQuery();
            if (rs.next()) {
                int banned = rs.getInt("banned");
                if (rs.getByte("banned") == 1) {
                    return 3;
                }
                accId = rs.getInt("id");
                gm = rs.getInt("gm") > 0;
                String passhash = rs.getString("password");
                String salt = rs.getString("salt");
                greason = rs.getByte("greason");
                gender = rs.getByte("gender");
                tempban = getTempBanCalendar(rs);
                if ((banned == 0 && !ipMacBanned) || banned == -1) {
                    PreparedStatement ips = con.prepareStatement("INSERT INTO iplog (accountid, ip) VALUES (?, ?)");
                    ips.setInt(1, accId);
                    String sockAddr = session.getRemoteAddress().toString();
                    ips.setString(2, sockAddr.substring(1, sockAddr.lastIndexOf(':')));
                    ips.executeUpdate();
                    ips.close();
                }
                if (!rs.getString("lastknownip").equals(session.getRemoteAddress().toString())) {
                    PreparedStatement lkip = con.prepareStatement("UPDATE accounts SET lastknownip = ? WHERE id = ?");
                    String sockAddr = session.getRemoteAddress().toString();
                    lkip.setString(1, sockAddr.substring(1, sockAddr.lastIndexOf(':')));
                    lkip.setInt(2, accId);
                    lkip.executeUpdate();
                    lkip.close();
                }
                ps.close();
                rs.close();
                if (getLoginState() > LOGIN_NOTLOGGEDIN) {
                    loggedIn = false;
                    loginok = 7;
                } else if (pwd.equals(passhash) || checkHash(passhash, "SHA-1", pwd) || checkHash(passhash, "SHA-512", pwd + salt)) {
                    loginok = 0;
                } else {
                    loggedIn = false;
                    loginok = 4;
                }
            }
        } catch (SQLException e) {
            log.error("ERROR", e);
        } finally {
            try {
                if (ps != null && !ps.isClosed()) {
                    ps.close();
                }
                if (rs != null && !rs.isClosed()) {
                    rs.close();
                }
            } catch (SQLException e) {
            }
        }
        if (loginok == 0) {
            attemptedLogins = 0;
        }
        return loginok;
    }

    public static String getChannelServerIPFromSubnet(String clientIPAddress, int channel) {
        long ipAddress = IPAddressTool.dottedQuadToLong(clientIPAddress);
        Properties subnetInfo = LoginServer.getInstance().getSubnetInfo();

        if (subnetInfo.contains("net.sf.odinms.net.login.subnetcount")) {
            int subnetCount = Integer.parseInt(subnetInfo.getProperty("net.sf.odinms.net.login.subnetcount"));
            for (int i = 0; i < subnetCount; i++) {
                String[] connectionInfo = subnetInfo.getProperty("net.sf.odinms.net.login.subnet." + i).split(":");
                long subnet = IPAddressTool.dottedQuadToLong(connectionInfo[0]);
                long channelIP = IPAddressTool.dottedQuadToLong(connectionInfo[1]);
                int channelNumber = Integer.parseInt(connectionInfo[2]);

                if (((ipAddress & subnet) == (channelIP & subnet)) && (channel == channelNumber)) {
                    return connectionInfo[1];
                }
            }
        }

        return "0.0.0.0";
    }

    private void unban() {
        int i;
        try {
            Connection con = DatabaseConnection.getConnection();
            loadMacsIfNescessary();
            StringBuilder sql = new StringBuilder("DELETE FROM macbans WHERE mac IN (");
            for (i = 0; i < macs.size(); i++) {
                sql.append("?");
                if (i != macs.size() - 1) {
                    sql.append(", ");
                }
            }
            sql.append(")");
            PreparedStatement ps = con.prepareStatement(sql.toString());
            i = 0;
            for (String mac : macs) {
                i++;
                ps.setString(i, mac);
            }
            ps.executeUpdate();
            ps.close();
            ps = con.prepareStatement("DELETE FROM ipbans WHERE ip LIKE CONCAT(?, '%')");
            ps.setString(1, getSession().getRemoteAddress().toString().split(":")[0]);
            ps.executeUpdate();
            ps.close();
            ps = con.prepareStatement("UPDATE accounts SET banned = 0 WHERE id = ?");
            ps.setInt(1, accId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            log.error("Error while unbanning", e);
        }
    }

    public void updateMacs(String macData) {
        for (String mac : macData.split(", ")) {
            macs.add(mac);
        }
        StringBuilder newMacData = new StringBuilder();
        Iterator<String> iter = macs.iterator();
        while (iter.hasNext()) {
            String cur = iter.next();
            newMacData.append(cur);
            if (iter.hasNext()) {
                newMacData.append(", ");
            }
        }
        Connection con = DatabaseConnection.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement("UPDATE accounts SET macs = ? WHERE id = ?");
            ps.setString(1, newMacData.toString());
            ps.setInt(2, accId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            log.error("Error saving MACs", e);
        }
    }

    public void setAccID(int id) {
        this.accId = id;
    }

    public int getAccID() {
        return this.accId;
    }

    public void updateLoginState(int newstate, String sessionID) {
        Connection con = DatabaseConnection.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement("UPDATE accounts SET loggedin = ?, SessionIP = ?, lastlogin = CURRENT_TIMESTAMP() WHERE id = ?");
                ps.setInt(1, newstate);
                ps.setString(2, sessionID);
                ps.setInt(3, getAccID());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            log.error("ERROR", e);
        }
        if (newstate == LOGIN_NOTLOGGEDIN) {
            loggedIn = false;
            serverTransition = false;
        } /*else if (newstate == LOGIN_WAITING) {
            loggedIn = false;
            serverTransition = false;
        }*/ else {
            serverTransition = (newstate == LOGIN_SERVER_TRANSITION);
            loggedIn = !serverTransition;
        }
    }

    public int getLoginState() {
        Connection con = DatabaseConnection.getConnection();
        try {
            PreparedStatement ps;
            ps = con.prepareStatement("SELECT loggedin, lastlogin, UNIX_TIMESTAMP(birthday) as birthday FROM accounts WHERE id = ?");
            ps.setInt(1, getAccID());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                rs.close();
                ps.close();
                throw new DatabaseException("Everything sucks");
            }
            birthday = Calendar.getInstance();
            long blubb = rs.getLong("birthday");
            if (blubb > 0) {
                birthday.setTimeInMillis(blubb * 1000);
            }
            int state = rs.getInt("loggedin");
            if (state == LOGIN_SERVER_TRANSITION) {
                Timestamp ts = rs.getTimestamp("lastlogin");
                long t = ts.getTime();
                long now = System.currentTimeMillis();
                if (t + 30000 < now) { // Connecting to chanserver timeout.
                    state = LOGIN_NOTLOGGEDIN;
                    updateLoginState(MapleClient.LOGIN_NOTLOGGEDIN, getSessionIPAddress());
                    if (isGuest()) {
                        deleteAllCharacters();
                    }
                }
            }
            rs.close();
            ps.close();
            if (state == LOGIN_LOGGEDIN) {
                loggedIn = true;
            } else if (state == LOGIN_SERVER_TRANSITION) {
                ps = con.prepareStatement("UPDATE accounts SET loggedin = 0 WHERE id = ?");
                ps.setInt(1, getAccID());
                ps.executeUpdate();
                ps.close();
            } else {
                loggedIn = false;
            }
            return state;
        } catch (SQLException e) {
            loggedIn = false;
            log.error("ERROR", e);
            throw new DatabaseException("Everything sucks", e);
        }
    }
   public final String getSessionIPAddress() {
        return session.getRemoteAddress().toString().split(":")[0];
    }
    public final boolean CheckIPAddress() {
        if (this.accId < 0) {
            return false;
        }
        try {
            boolean canlogin = false;
            try (PreparedStatement ps = DatabaseConnection.getConnection().prepareStatement("SELECT SessionIP, banned FROM accounts WHERE id = ?");) {
                ps.setInt(1, this.accId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        final String sessionIP = rs.getString("SessionIP");
                        if (sessionIP != null) { // Probably a login proced skipper?
                            canlogin = getSessionIPAddress().equals(sessionIP.split(":")[0]);
                        }
                        if (rs.getInt("banned") > 0) {
                            canlogin = false; //canlogin false = close client
                        }
                    }
                }
            }
            return canlogin;
        } catch (final SQLException e) {
            System.out.println("Failed in checking IP address for client.");
        }
        return true;
    }
    public boolean checkBirthDate(Calendar date) {
        if (date.get(Calendar.YEAR) == birthday.get(Calendar.YEAR)) {
            if (date.get(Calendar.MONTH) == birthday.get(Calendar.MONTH)) {
                if (date.get(Calendar.DAY_OF_MONTH) == birthday.get(Calendar.DAY_OF_MONTH)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void disconnect() {
        MapleCharacter chr = this.getPlayer();
        if (chr != null && isLoggedIn()) {
            chr.saveToDB(true, true);
            if (chr.getTrade() != null) {
                MapleTrade.cancelTrade(chr);
            }
            List<PlayerCoolDownValueHolder> cooldowns = chr.getAllCooldowns();
            if (cooldowns != null && cooldowns.size() > 0) {
                Connection con = DatabaseConnection.getConnection();
                for (PlayerCoolDownValueHolder cooling : cooldowns) {
                    try {
                        PreparedStatement ps = con.prepareStatement("INSERT INTO CoolDowns (charid, SkillID, StartTime, length) VALUES (?, ?, ?, ?)");
                        ps.setInt(1, chr.getId());
                        ps.setInt(2, cooling.skillId);
                        ps.setLong(3, cooling.startTime);
                        ps.setLong(4, cooling.length);
                        ps.executeUpdate();
                        ps.close();
                    } catch (SQLException se) {
                        se.printStackTrace();
                    }
                }
            }
            chr.cancelAllBuffs();
            chr.dispelDebuffs();
            if (chr.getEventInstance() != null) {
                chr.getEventInstance().playerDisconnected(chr);
            }
            IPlayerInteractionManager interaction = chr.getInteraction();
            if (interaction != null) {
                if (interaction.isOwner(chr)) {
                    if (interaction.getShopType() == 1) {
                        HiredMerchant hm = (HiredMerchant) interaction;
                        hm.setOpen(true);
                    } else if (interaction.getShopType() == 2) {
                        for (MaplePlayerShopItem items : interaction.getItems()) {
                            if (items.getBundles() > 0) {
                                IItem item = items.getItem();
                                item.setQuantity(items.getBundles());
                                MapleInventoryManipulator.addFromDrop(this, item);
                            }
                        }
                        interaction.removeAllVisitors(3, 1);
                        interaction.closeShop(true);
                    } else if (interaction.getShopType() == 3 || interaction.getShopType() == 4) {
                        interaction.removeAllVisitors(3, 1);
                        interaction.closeShop(true);
                    }
                } else {
                    interaction.removeVisitor(chr);
                }
            }
            if (PublicChatHandler.getPublicChatHolder().containsKey(chr.getId())) {
                PublicChatHandler.getPublicChatHolder().remove(chr.getId());
            }
            try {
                WorldChannelInterface wci = getChannelServer().getWorldInterface();
                if (chr.getMessenger() != null) {
                    MapleMessengerCharacter messengerplayer = new MapleMessengerCharacter(chr);
                    wci.leaveMessenger(chr.getMessenger().getId(), messengerplayer);
                    chr.setMessenger(null);
                }
            } catch (RemoteException e) {
                getChannelServer().reconnectWorld();
            }
            if (!chr.isAlive()) {
                chr.setHp(50, true);
            }
            chr.setMessenger(null);
            chr.getCheatTracker().dispose();
            if (!isGuest()) {
                chr.saveToDB(true, true);
            }
            chr.getMap().removePlayer(chr);
            try {
                WorldChannelInterface wci = getChannelServer().getWorldInterface();
                if (chr.getParty() != null) {
                    try {
                        MaplePartyCharacter chrp = new MaplePartyCharacter(chr);
                        chrp.setOnline(false);
                        wci.updateParty(chr.getParty().getId(), PartyOperation.LOG_ONOFF, chrp);
                    } catch (Exception e) {
                        log.warn("Failed removing party character. Player already removed.", e);
                    }
                }
                try{
                if (!this.serverTransition && isLoggedIn()) {
                    wci.loggedOff(chr.getName(), chr.getId(), channel, chr.getBuddylist().getBuddyIds());
                } else {
                    wci.loggedOn(chr.getName(), chr.getId(), channel, chr.getBuddylist().getBuddyIds());
                }
                }catch(Exception e){
                    log.warn("Something went wrong with shutting down? " + e.toString());
                }
                if (chr.getGuildId() > 0) {
                    wci.setGuildMemberOnline(chr.getMGC(), false, -1);
                    int allianceId = chr.getGuild().getAllianceId();
                    if (allianceId > 0) {
                        wci.allianceMessage(allianceId, MaplePacketCreator.allianceMemberOnline(chr, false), chr.getId(), -1);
                    }
                }
            } catch (RemoteException e) {
                getChannelServer().reconnectWorld();
            } catch (Exception e) {
                log.error(getLogMessage(this, "ERROR"), e);
            } finally {
                if (getChannelServer() != null) {
                    getChannelServer().removePlayer(chr);
                } else {
                    log.error(getLogMessage(this, "No channelserver associated to char {}", chr.getName()));
                }
            }
            if (isGuest()) {
                deleteAllCharacters();
            }
        }
        if (!this.serverTransition && isLoggedIn()) {
            this.updateLoginState(MapleClient.LOGIN_NOTLOGGEDIN, getSessionIPAddress());
            session.removeAttribute(MapleClient.CLIENT_KEY); // prevents double dcing during login
        }
        NPCScriptManager npcsm = NPCScriptManager.getInstance();
        if (npcsm != null) {
            npcsm.dispose(this);
        }
        if (QuestScriptManager.getInstance() != null) {
            QuestScriptManager.getInstance().dispose(this);
        }
    }

    public void dropDebugMessage(MessageCallback mc) {
        StringBuilder builder = new StringBuilder();
        builder.append("Connected: ");
        builder.append(getSession().isConnected());
        builder.append(" Closing: ");
        builder.append(getSession().isClosing());
        builder.append(" ClientKeySet: ");
        builder.append(getSession().getAttribute(CLIENT_KEY) != null);
        builder.append(" loggedin: ");
        builder.append(isLoggedIn());
        builder.append(" has char: ");
        builder.append(getPlayer() != null);
        mc.dropMessage(builder.toString());
    }

    public int getChannel() {
        return channel;
    }

    public ChannelServer getChannelServer() {
        return ChannelServer.getInstance(getChannel());
    }

    public boolean deleteCharacter(int cid) {
        Connection con = DatabaseConnection.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement("SELECT id, guildid, guildrank, name, allianceRank FROM characters WHERE id = ? AND accountid = ?");
            ps.setInt(1, cid);
            ps.setInt(2, accId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                rs.close();
                ps.close();
                return false;
            }
            if (rs.getInt("guildid") > 0) {
                MapleGuildCharacter mgc = new MapleGuildCharacter(cid, 0, rs.getString("name"), -1, 0, rs.getInt("guildrank"), rs.getInt("guildid"), false, rs.getInt("allianceRank"));
                try {
                    LoginServer.getInstance().getWorldInterface().deleteGuildCharacter(mgc);
                } catch (RemoteException re) {
                    getChannelServer().reconnectWorld();
                    rs.close();
                    ps.close();
                    return false;
                }
            }
            rs.close();
            ps.close();
            ps = con.prepareStatement("DELETE FROM characters WHERE id = ?");
            ps.setInt(1, cid);
            ps.executeUpdate();
            ps.close();
            return true;
        } catch (SQLException e) {
            log.error("ERROR", e);
        }
        return false;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getAccountPass() {
        return accountPass;
    }

    public void setAccountPass(String pass) {
        this.accountPass = pass;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public int getWorld() {
        return world;
    }

    public void setWorld(int world) {
        this.world = world;
    }

    public void pongReceived() {
        lastPong = System.currentTimeMillis();
    }

    public void sendPing() {
        final long then = System.currentTimeMillis();
        getSession().write(MaplePacketCreator.getPing());
        TimerManager.getInstance().schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    if (lastPong - then < 0) {
                        if (getSession().isConnected()) {
                            System.out.println(session.getRemoteAddress().toString() + ": Ping Timeout.");
                            getSession().close();
                        }
                    }
                } catch (NullPointerException e) {
                }
            }
        }, 15000);

    }

    public static String getLogMessage(MapleClient cfor, String message) {
        return getLogMessage(cfor, message, new Object[0]);
    }

    public static String getLogMessage(MapleCharacter cfor, String message) {
        return getLogMessage(cfor == null ? null : cfor.getClient(), message);
    }

    public static String getLogMessage(MapleCharacter cfor, String message, Object... parms) {
        return getLogMessage(cfor == null ? null : cfor.getClient(), message, parms);
    }

    public static String getLogMessage(MapleClient cfor, String message, Object... parms) {
        StringBuilder builder = new StringBuilder();
        if (cfor != null) {
            if (cfor.getPlayer() != null) {
                builder.append("<");
                builder.append(MapleCharacterUtil.makeMapleReadable(cfor.getPlayer().getName()));
                builder.append(" (cid: ");
                builder.append(cfor.getPlayer().getId());
                builder.append(")> ");
            }
            if (cfor.getAccountName() != null) {
                builder.append("(Account: ");
                builder.append(MapleCharacterUtil.makeMapleReadable(cfor.getAccountName()));
                builder.append(") ");
            }
        }
        builder.append(message);
        for (Object parm : parms) {
            int start = builder.indexOf("{}");
            builder.replace(start, start + 2, parm.toString());
        }
        return builder.toString();
    }

    public static int findAccIdForCharacterName(String charName) {
        Connection con = DatabaseConnection.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement("SELECT accountid FROM characters WHERE name = ?");
            ps.setString(1, charName);
            ResultSet rs = ps.executeQuery();
            int ret = -1;
            if (rs.next()) {
                ret = rs.getInt("accountid");
            }
            rs.close();
            ps.close();
            return ret;
        } catch (SQLException e) {
            log.error("SQL THROW");
        }
        return -1;
    }

    public static int getAccIdFromCID(int id) {
        Connection con = DatabaseConnection.getConnection();
        int ret = -1;
        try {
            PreparedStatement ps = con.prepareStatement("SELECT accountid FROM characters WHERE id = ?");
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ret = rs.getInt("accountid");
            }
            rs.close();
            ps.close();
            return ret;
        } catch (SQLException e) {
            log.error("ERROR", e);
        }
        return -1;
    }

    public Set<String> getMacs() {
        return Collections.unmodifiableSet(macs);
    }

    public boolean isGm() {
        return gm;
    }

    public void setScriptEngine(String name, ScriptEngine e) {
        engines.put(name, e);
    }

    public ScriptEngine getScriptEngine(String name) {
        return engines.get(name);
    }

    public void removeScriptEngine(String name) {
        engines.remove(name);
    }

    public ScheduledFuture<?> getIdleTask() {
        return idleTask;
    }

    public void setIdleTask(ScheduledFuture<?> idleTask) {
        this.idleTask = idleTask;
    }

    public NPCConversationManager getCM() {
        return NPCScriptManager.getInstance().getCM(this);
    }

    public QuestActionManager getQM() {
        return QuestScriptManager.getInstance().getQM(this);
    }

    public void setTimesTalked(int n, int t) {
        timesTalked.remove(new Pair<MapleCharacter, Integer>(getPlayer(), n));
        timesTalked.put(new Pair<MapleCharacter, Integer>(getPlayer(), n), t);
    }

    public int getTimesTalked(int n) {
        if (timesTalked.get(new Pair<MapleCharacter, Integer>(getPlayer(), n)) == null) {
            setTimesTalked(n, 0);
        }
        return timesTalked.get(new Pair<MapleCharacter, Integer>(getPlayer(), n));
    }

    private static class CharNameAndId {
        public String name;
        public int id;
        public CharNameAndId(String name, int id) {
            super();
            this.name = name;
            this.id = id;
        }
    }

    public boolean isGuest() {
        return guest;
    }

    public void setGuest(boolean set) {
        this.guest = set;
    }
    
    public void deleteAllCharacters() {
        Connection con = DatabaseConnection.getConnection();
        try {
            int accountid = -1;
            PreparedStatement ps = con.prepareStatement("SELECT id FROM accounts WHERE name = ?");
            ps.setString(1, accountName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                accountid = rs.getInt("id");
            }
            rs.close();
            ps.close();
            if (accountid == -1) {
                return;
            }
            ps = con.prepareStatement("SELECT id FROM characters WHERE accountid = ?");
            ps.setInt(1, accountid);
            rs = ps.executeQuery();
            while (rs.next()) {
                deleteCharacter(rs.getInt("id"));
            }
            rs.close();
            ps.close();
        } catch (SQLException sqe) {
            sqe.printStackTrace();
            return;
        }
        return;
    }
    public void clear() {
        if (player != null) {
            player.clear();
        }
        player = null;
    }
    public byte getGender() {
        return gender;
    }

    public void setGender(byte m) {
        this.gender = m;
        try {
            try (PreparedStatement ps = DatabaseConnection.getConnection().prepareStatement("UPDATE accounts SET gender = ? WHERE id = ?")) {
                ps.setByte(1, gender);
                ps.setInt(2, accId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
        }
    }
    	public void updateHWID(long newHwid, String name) {
			PreparedStatement ps = null;
			try {
				ps = DatabaseConnection.getConnection().prepareStatement("UPDATE accounts SET hwid = ? WHERE name = ?");
				ps.setLong(1, newHwid);
				ps.setString(2, this.getAccountName());
				ps.executeUpdate();
				ps.close();
			} catch (SQLException e) {
				e.printStackTrace();
			} finally {
				try {
					if(ps != null && !ps.isClosed()) {
						ps.close();
					}
				} catch (SQLException e) {
				}
			}
	}
   	private void loadgIfNescessary() {
			try(PreparedStatement ps = DatabaseConnection.getConnection().prepareStatement("SELECT hwid FROM accounts WHERE id = ?")) {
				ps.setInt(1, accId);
				try(ResultSet rs = ps.executeQuery()) {
					if(rs.next()) {
						hwid = rs.getLong("hwid");
					}
				}
			} catch (SQLException e) {
                            
                        }
	}
        public long returnHWID()throws SQLException {
           try(PreparedStatement ps = DatabaseConnection.getConnection().prepareStatement("SELECT hwid FROM accounts WHERE id = ?")) {
		ps.setInt(1, accId);
			try(ResultSet rs = ps.executeQuery()) {
				if(rs.next()) {
					hwid = rs.getLong("hwid");
				}
			}
		}
           return hwid;
        }
}