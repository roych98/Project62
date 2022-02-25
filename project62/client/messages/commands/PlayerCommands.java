package net.sf.odinms.client.messages.commands;

import java.rmi.RemoteException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleCharacterUtil;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.world.remote.WorldChannelInterface;
import net.sf.odinms.client.messages.Command;
import net.sf.odinms.client.messages.CommandDefinition;
import net.sf.odinms.client.messages.MessageCallback;
import net.sf.odinms.database.DatabaseConnection;
import net.sf.odinms.net.channel.ChannelServer;
import net.sf.odinms.scripting.npc.NPCScriptManager;
import net.sf.odinms.server.MapleItemInformationProvider;
import net.sf.odinms.server.life.MapleMonster;
import net.sf.odinms.server.life.MapleMonsterInformationProvider;
import net.sf.odinms.server.maps.MapleMap;
import net.sf.odinms.tools.FilePrinter;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.Pair;
import net.sf.odinms.tools.StringUtil;

public class PlayerCommands implements Command {

    @Override
    public void execute(MapleClient c, MessageCallback mc, String[] splitted) throws Exception {
        splitted[0] = splitted[0].toLowerCase();
        MapleCharacter player = c.getPlayer();
        ChannelServer cserv = c.getChannelServer();
        if (splitted[0].equals("@command") || splitted[0].equals("@commands") || splitted[0].equals("@help")) {
            mc.dropMessage("@save | Saves your progress.");
            mc.dropMessage("@dispose | Unstucks you.");
            mc.dropMessage("@togglesmega | Turn megaphones OFF/ON.");
            mc.dropMessage("@gm <message> | Sends a message to all online GMs.");
            mc.dropMessage("@whatdrops | Shows what mob drops a certain item.");
            mc.dropMessage("@showonline | Shows a list of online players.");
            if (player.getClient().getChannelServer().extraCommands()) {
                mc.dropMessage("@cody/@storage/@news/@kin/@nimakin/@reward/@reward1/@fredrick/@spinel/@clan");
                mc.dropMessage("@banme - | - This command will ban you, SGM's will not unban you from this.");
                mc.dropMessage("@goafk - | - Uses a CB to say that you are AFK.");
                mc.dropMessage("@slime - | - For a small cost, it summons smiles for you.");
                mc.dropMessage("@go - | - Takes you to many towns and fighting areas.");
                mc.dropMessage("@buynx - | - You can purchase NX with this command.");
    }
        /*}   else if (splitted[0].equals("@points")) {
            mc.dropMessage("You currently have:");
            mc.dropMessage("NX: " + player.getCSPoints(4));
            mc.dropMessage("Donor Points: " + player.getofficialDP());
            mc.dropMessage("Event Points: " + player.getEventPoints());
            
        */} else if (splitted[0].equals("@save")) {
            if (!player.getCheatTracker().Spam(300000, 0)) { // 5 minutes
                player.saveToDB(true, true);
                mc.dropMessage("Saved.");
            } else {
                mc.dropMessage("You cannot save more than once every 5 minutes.");
            }
        } else if (splitted[0].equals("@dispose")) {
            NPCScriptManager.getInstance().dispose(c);
            mc.dropMessage("You have been disposed.");
        } else if (splitted[0].equals("@togglesmega")) {
                player.setSmegaEnabled(!player.getSmegaEnabled());
                String text = (!player.getSmegaEnabled() ? "[Disable] Megaphones are now disabled." : "[Enable] Megaphones are now enabled.");
                mc.dropMessage(text);
        } else if (splitted[0].equals("@gm")) {
            if (!player.getCheatTracker().Spam(300000, 0)) { // 5 minutes.
                try {
                    c.getChannelServer().getWorldInterface().broadcastGMMessage(null, MaplePacketCreator.serverNotice(6, "Channel: " + c.getChannel() + "  " + player.getName() + ": " + StringUtil.joinStringFrom(splitted, 1)).getBytes());
                    FilePrinter.printError(FilePrinter.GMLog + "GMLog" + ".txt", player.getName() + ": " + StringUtil.joinStringFrom(splitted, 1)  + "\n");
                } catch (RemoteException ex) {
                    c.getChannelServer().reconnectWorld();
                }
                mc.dropMessage("Message sent.");
            }
           } else if (splitted[0].equals("@whatdrops")) {
            String searchString = StringUtil.joinStringFrom(splitted, 1);
            boolean itemSearch = splitted[0].equals("@whatdrops");
            int limit = 5;
            ArrayList<Pair<Integer, String>> searchList;
            if(itemSearch)
                searchList = MapleItemInformationProvider.getInstance().getItemDataByName(searchString);
            else
                searchList = MapleMonsterInformationProvider.getMobsIDsFromName(searchString);
            Iterator<Pair<Integer, String>> listIterator = searchList.iterator();
            for (int i = 0; i < limit; i++)
            {
                if(listIterator.hasNext())
                {
                    Pair<Integer, String> data = listIterator.next();
                    if(itemSearch)
                        player.dropMessage("Item " + data.getRight() + " dropped by:");
                    else
                        player.dropMessage("Mob " + data.getRight() + " drops:");
                    try
                    {
                        PreparedStatement ps = DatabaseConnection.getConnection().prepareStatement("SELECT * FROM monsterdrops WHERE " + (itemSearch ? "itemid" : "monsterid") + " = ? LIMIT 50");
                        ps.setInt(1, data.getLeft());
                        ResultSet rs = ps.executeQuery();
                        while(rs.next())
                        {
                            int mobid = -1;
                            String resultName;
                            if(itemSearch) {
                                mobid = rs.getInt("monsterid");
                                resultName = MapleMonsterInformationProvider.getMobNameFromID(mobid);
                            }
                            else
                                resultName = MapleItemInformationProvider.getInstance().getName(rs.getInt("itemid"));
                            int itemid = rs.getInt("itemid");
                            if(resultName != null) {
                                //double chance = (((Integer.valueOf(c.getChannelServer().getDropRate()).doubleValue()) / Integer.valueOf((MapleMonsterInformationProvider.getInstance().getDropChance(mobid, itemid))).doubleValue()) * 100.0);
                                player.dropMessage(resultName);
                            }
                        }
                        rs.close();
                        ps.close();
                    } catch (Exception e)
                    {
                        player.dropMessage("There was a problem retreiving the required data. Please try again.");
                        e.printStackTrace();
                        return;
                    }
                } else
                    break;
            }
           /*} else if (splitted[0].equals("@joinevent")) {
            if (c.getChannelServer().eventOn == true) {
                MapleMap map = null;
                
                  c.getPlayer().changeMap(c.getChannelServer().eventMap, 0); // there..... had to make a whole new method....
            }
            else
            {
                player.dropMessage("There is no active event at this time or has closed. Please try again later!");
            }
            } else if (splitted[0].equals("@leaveevent")) {
            if (player.getMapId() != c.getChannelServer().eventMap) {
                player.dropMessage("You may only use this to leave the event map!");
                //mc.dropMessage("You may only use this command in the Leaving the Event map.");
                    return;
            }
            if (c.getChannelServer().eventOn == true) {
            MapleMap map = cserv.getMapFactory().getMap(910000000);
            player.changeMap(map, map.getPortal(0));
            } else {
                player.dropMessage("There is no active event at this time or has closed. Please try again later!");
                return;
             }
        } else if (splitted[0].equals("@afk")) {
            if (splitted.length >= 2) {
                String name = splitted[1];
                MapleCharacter victim = c.getChannelServer().getPlayerStorage().getCharacterByName(name);
                if (victim == null) {
                    try {
                        WorldChannelInterface wci = c.getChannelServer().getWorldInterface();
                        int channel = wci.find(name);
                        if (channel == -1 || victim.isGM()) {
                            mc.dropMessage("This player is not online.");
                            return;
                        }
                        victim = ChannelServer.getInstance(channel).getPlayerStorage().getCharacterByName(name);
                    } catch (RemoteException re) {
                        c.getChannelServer().reconnectWorld();
                    }
                }
                long blahblah = System.currentTimeMillis() - victim.getAfkTime();
                if (Math.floor(blahblah / 60000) == 0) { // less than a minute
                    mc.dropMessage("Player has not been afk!");
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append(victim.getName());
                    sb.append(" has been afk for");
                    compareTime(sb, blahblah);
                    mc.dropMessage(sb.toString());
                }
            } else {
                mc.dropMessage("You forgot to include the player IGN!");
            }
        } else if (splitted[0].equals("@onlinetime")) {
            if (splitted.length >= 2) {
                String name = splitted[1];
                MapleCharacter victim = c.getChannelServer().getPlayerStorage().getCharacterByName(name);
                if (victim == null) {
                    try {
                        WorldChannelInterface wci = c.getChannelServer().getWorldInterface();
                        int channel = wci.find(name);
                        if (channel == -1 || victim.isGM()) {
                            mc.dropMessage("This player is not online.");
                            return;
                        }
                        victim = ChannelServer.getInstance(channel).getPlayerStorage().getCharacterByName(name);
                    } catch (RemoteException re) {
                        c.getChannelServer().reconnectWorld();
                    }
                }
                long blahblah = System.currentTimeMillis() - victim.getLastLogin();
                StringBuilder sb = new StringBuilder();
                sb.append(victim.getName());
                sb.append(" has been online for");
                compareTime(sb, blahblah);
                mc.dropMessage(sb.toString());
            } else {
                mc.dropMessage("You forgot to include the player IGN!");
            }
        */} else if (splitted[0].equals("@ks") || splitted[0].equals("@mapowner")) {
            try {
                Date date = new Date(player.getMap().getMapOwnerSinceTime());
                String mapownerTimestamp = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(date);
                MapleCharacter p = c.getPlayer().getMap().getMapOwner();
                if(p == null) {
                    return;
                }
                String toDrop = "[Map Owner - " + p.getName() + "] owns map " +  c.getPlayer().getMapId() + " Since: " + mapownerTimestamp;
                player.dropMessage(5, toDrop);
                String timeStamp = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Calendar.getInstance().getTime());
                player.dropMessage(5, "Current server time is: " + timeStamp);
            } catch (StackOverflowError soe) {
                soe.printStackTrace();
            }
        } else if (splitted[0].equals("@time")) {
            String timeStamp = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Calendar.getInstance().getTime());
            player.dropMessage(5, "Current server time is: " + timeStamp);
        } else if (splitted[0].equals("@showonline")) {
            int i = 0;
            for (ChannelServer cs : ChannelServer.getAllInstances()) {
                if (cs.getPlayerStorage().getAllCharacters().size() > 0) {
                    StringBuilder sb = new StringBuilder();
                    mc.dropMessage("Channel " + cs.getChannel());
                    for (MapleCharacter chr : cs.getPlayerStorage().getAllCharacters()) {
                        i++;
                        if (sb.length() > 150) { // Chars per line. Could be more or less
                            mc.dropMessage(sb.toString());
                            sb = new StringBuilder();
                        }
                        if(!chr.isGM())
                            sb.append(chr.getName()).append(" ,  ");
                    }
                    mc.dropMessage(sb.toString());
                }
            }
        } else if(splitted[0].equals("@showquest")) {
            player.showquest = !player.showquest;
            if(player.showquest)
                player.dropMessage(5, "Upon starting, finishing or forfeiting quest, a message will drop down below with the quest's id and npc.");
            else player.dropMessage(5, "Disabled showing quests information.");
        } else if(splitted[0].equals("@showreactor")) {
            player.showreactor = !player.showreactor;
            if(player.showreactor) 
                player.dropMessage(5, "Upon hitting a reactor, a message will drop down below with the reactor's id");
            else player.dropMessage(5, "Disabled showing reactors information.");
        } else if(splitted[0].equals("@showportal")) {
            player.showportal = !player.showportal;
            if(player.showportal) 
                player.dropMessage(5, "Upon using a portal, a message will drop down below with the portal's id");
            else player.dropMessage(5, "Disabled showing portals information.");
        }
        /*else if(splitted[0].equals("@chance")) {
            double ce = Integer.valueOf(Integer.parseInt(splitted[1])).doubleValue();
            double dropRate = Integer.valueOf(c.getChannelServer().getDropRate()).doubleValue();
            double chance = (dropRate / ce) * 100.0;
            player.dropMessage("Chance: " + chance + "%.");
        }*/
        
    }

    private void compareTime(StringBuilder sb, long timeDiff) {
        double secondsAway = timeDiff / 1000;
        double minutesAway = 0;
        double hoursAway = 0;

        while (secondsAway > 60) {
            minutesAway++;
            secondsAway -= 60;
        }
        while (minutesAway > 60) {
            hoursAway++;
            minutesAway -= 60;
        }
        boolean hours = false;
        boolean minutes = false;
        if (hoursAway > 0) {
            sb.append(" ");
            sb.append((int) hoursAway);
            sb.append(" hours");
            hours = true;
        }
        if (minutesAway > 0) {
            if (hours) {
                sb.append(" -");
            }
            sb.append(" ");
            sb.append((int) minutesAway);
            sb.append(" minutes");
            minutes = true;
        }
        if (secondsAway > 0) {
            if (minutes) {
                sb.append(" and");
            }
            sb.append(" ");
            sb.append((int) secondsAway);
            sb.append(" seconds !");
        }
    }

    @Override
    public CommandDefinition[] getDefinition() {
        return new CommandDefinition[]{
            new CommandDefinition("command", 0),
            new CommandDefinition("commands", 0),
            new CommandDefinition("help", 0),
            new CommandDefinition("save", 0),
            new CommandDefinition("dispose", 0),
            new CommandDefinition("togglesmega", 0),
            new CommandDefinition("gm", 0),
          //  new CommandDefinition("afk", 0),
           // new CommandDefinition("onlinetime", 0),
            //new CommandDefinition("points", 0),
            new CommandDefinition("whatdrops", 0),
            //new CommandDefinition("joinevent", 0),
            //new CommandDefinition("leaveevent", 0),
            new CommandDefinition("ks", 0),
            new CommandDefinition("mapowner", 0),
            new CommandDefinition("time", 0),
            new CommandDefinition("showonline", 0),
            new CommandDefinition("showquest", 0),
            new CommandDefinition("showreactor", 0),
            new CommandDefinition("showportal", 0)
            //new CommandDefinition("chance", 0)
        };
    }
}