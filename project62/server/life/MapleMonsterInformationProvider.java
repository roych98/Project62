package net.sf.odinms.server.life;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.database.DatabaseConnection;
import net.sf.odinms.provider.MapleData;
import net.sf.odinms.provider.MapleDataProvider;
import net.sf.odinms.provider.MapleDataProviderFactory;
import net.sf.odinms.provider.MapleDataTool;
import net.sf.odinms.tools.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapleMonsterInformationProvider {
    public static class DropEntry {
        public DropEntry(int itemId, int chance) {
            this.itemId = itemId;
            this.chance = chance;
        }

        public int itemId;
        public int chance;
        public int assignedRangeStart;
        public int assignedRangeLength;

        @Override
        public String toString() {
            return itemId + " chance: " + chance;
        }
    }

    public static final int APPROX_FADE_DELAY = 90;
    private static MapleMonsterInformationProvider instance = null;
    private Map<Integer,List<DropEntry>> drops = new HashMap<Integer, List<DropEntry>>();
    private static final Logger log = LoggerFactory.getLogger(MapleMonsterInformationProvider.class);

    private MapleMonsterInformationProvider() {
    }

    public static MapleMonsterInformationProvider getInstance() {
        if (instance == null) instance = new MapleMonsterInformationProvider();
        return instance;
    }

    public List<DropEntry> retrieveDropChances(int monsterId) {
        if (drops.containsKey(monsterId)) return drops.get(monsterId);
        List<DropEntry> ret = new LinkedList<DropEntry>();
        try {
            Connection con = DatabaseConnection.getConnection();
            PreparedStatement ps = con.prepareStatement("SELECT itemid, chance, monsterid FROM monsterdrops WHERE (monsterid = ? AND chance >= 0) OR (monsterid <= 0)");
            ps.setInt(1, monsterId);
            ResultSet rs = ps.executeQuery();
            MapleMonster theMonster = null;
            while (rs.next()) {
                int rowMonsterId = rs.getInt("monsterid");
                int chance = rs.getInt("chance");
                if (rowMonsterId != monsterId && rowMonsterId != 0) {
                    if (theMonster == null) {
                        theMonster = MapleLifeFactory.getMonster(monsterId);
                    }
                    chance += theMonster.getLevel() * rowMonsterId;
                }
                ret.add(new DropEntry(rs.getInt("itemid"), chance));
            }
            rs.close();
            ps.close();
        } catch (Exception e) {
            log.error("Error retrieving drop", e);
        }
        drops.put(monsterId, ret);
        return ret;
    }

    public void clearDrops() {
        drops.clear();
    }
      
    public static ArrayList<Pair<Integer, String>> getMobsIDsFromName(String search)
    {
            MapleDataProvider dataProvider = MapleDataProviderFactory.getDataProvider(new File("wz/String.wz"));
            ArrayList<Pair<Integer, String>> retMobs = new ArrayList<Pair<Integer, String>>();
            MapleData data = dataProvider.getData("Mob.img");
            List<Pair<Integer, String>> mobPairList = new LinkedList<Pair<Integer, String>>();
            for (MapleData mobIdData : data.getChildren()) {
                int mobIdFromData = Integer.parseInt(mobIdData.getName());
                String mobNameFromData = MapleDataTool.getString(mobIdData.getChildByPath("name"), "NO-NAME");
                mobPairList.add(new Pair<Integer, String>(mobIdFromData, mobNameFromData));
            }
            for (Pair<Integer, String> mobPair : mobPairList) {
                if (mobPair.getRight().toLowerCase().contains(search.toLowerCase())) {
                    retMobs.add(mobPair);
                }
            }
            return retMobs;
    }
    public static String getMobNameFromID(int id)
    {
        try
        {
            return MapleLifeFactory.getMonster(id).getName();
        } catch (Exception e)
        {
            return null; //nonexistant mob
        }
    }
    
    public int getDropQuest(int monsterId) {        PreparedStatement ps = null;
        ResultSet rs = null;
        int quest = 0;
        try {
            ps = DatabaseConnection.getConnection().prepareStatement("SELECT questid FROM drop_data where dropperid = ?");
            ps.setInt(1, monsterId);
            rs = ps.executeQuery();
            while (rs.next()) {
                quest = rs.getInt("questid");
            }
        } catch (SQLException e) {
            
        }
        return quest;
    }
    
    public int getDropChance(int monsterId, int itemId) {
        PreparedStatement ps = null;
        ResultSet rs = null;
        int chance = 0;
        try {
            ps = DatabaseConnection.getConnection().prepareStatement("SELECT chance FROM monsterdrops WHERE monsterid = ? AND itemid = ?");
            ps.setInt(1, monsterId);
            ps.setInt(2, itemId);
            rs = ps.executeQuery();
            while (rs.next()) {
                chance = rs.getInt("chance");
            }
        } catch (SQLException e) {
            
        }
        return chance;
    }
   public List<Integer> getMobByItem(final int itemId) {
       final List<Integer> mobs = new LinkedList<Integer>() {};
       PreparedStatement ps = null;
       ResultSet rs = null;
       try {
           ps = DatabaseConnection.getConnection().prepareStatement("SELECT monsterid FROM monsterdrops WHERE itemid = ?");
           ps.setInt(1, itemId);
           rs = ps.executeQuery();
           int mobid;
           while (rs.next()) {
               mobid = rs.getInt("montserid");
               if (!mobs.contains(mobid))
               mobs.add(mobid);
           }
       } catch (SQLException e) {
           e.printStackTrace();
           } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
                if (rs != null) {
                    rs.close();
                }
            } catch (SQLException ignore) {
                return null;
            }
       }
       return mobs;
   }
}