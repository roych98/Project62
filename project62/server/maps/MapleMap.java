package net.sf.odinms.server.maps;

import java.awt.Point;
import java.awt.Rectangle;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Calendar;
import net.sf.odinms.client.Equip;
import net.sf.odinms.client.IItem;
import net.sf.odinms.client.Item;
import net.sf.odinms.client.MapleBuffStat;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.MapleInventoryType;
import net.sf.odinms.client.MapleJob;
import net.sf.odinms.client.MaplePet;
import net.sf.odinms.client.SkillFactory;
import net.sf.odinms.client.messages.MessageCallback;
import net.sf.odinms.client.status.MonsterStatus;
import net.sf.odinms.client.status.MonsterStatusEffect;
import net.sf.odinms.net.MaplePacket;
import net.sf.odinms.net.channel.ChannelServer;
import net.sf.odinms.net.world.MaplePartyCharacter;
import net.sf.odinms.scripting.event.EventInstanceManager;
import net.sf.odinms.server.MapleItemInformationProvider;
import net.sf.odinms.server.MaplePortal;
import net.sf.odinms.server.MapleStatEffect;
import net.sf.odinms.server.TimerManager;
import net.sf.odinms.server.life.MapleLifeFactory;
import net.sf.odinms.server.life.MapleMonster;
import net.sf.odinms.server.life.MapleNPC;
import net.sf.odinms.server.life.SpawnPoint;
import net.sf.odinms.server.maps.pvp.PvPLibrary;
import net.sf.odinms.tools.FilePrinter;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.Pair;
import net.sf.odinms.tools.Randomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapleMap {

    private static final int MAX_OID = 2147000000;
    private static final List<MapleMapObjectType> rangedMapobjectTypes = Arrays.asList(MapleMapObjectType.ITEM, MapleMapObjectType.MONSTER, MapleMapObjectType.DOOR, MapleMapObjectType.SUMMON, MapleMapObjectType.REACTOR);
    private Map<Integer, MapleMapObject> mapobjects = new LinkedHashMap<Integer, MapleMapObject>();
    private Collection<SpawnPoint> monsterSpawn = new LinkedList<SpawnPoint>();
    private AtomicInteger spawnedMonstersOnMap = new AtomicInteger(0);
    private Collection<MapleCharacter> characters = new LinkedHashSet<MapleCharacter>();
    private Map<Integer, MaplePortal> portals = new HashMap<Integer, MaplePortal>();
    private List<Rectangle> areas = new ArrayList<Rectangle>();
    private MapleFootholdTree footholds = null;
    private int mapid;
    private int runningOid = 100;
    private int returnMapId;
    private int channel;
    private float monsterRate;
    private boolean dropsDisabled = false;
    private boolean clock;
    private boolean boat;
    private boolean docked;
    private String mapName;
    private String streetName;
    private MapleMapEffect mapEffect = null;
    private boolean everlast = false;
    private int forcedReturnMap = 999999999;
    private int timeLimit;
    private static Logger log = LoggerFactory.getLogger(MapleMap.class);
    private MapleMapTimer mapTimer = null;
    private int dropLife = 180000;
    private int decHP = 0;
    private int protectItem = 0;
    private boolean town;
    private boolean showGate = false;
    private boolean disableChat = false;
    public long cTimestamp = 0, cDuration;  
    private int chance;
    private LinkedHashMap<MapleCharacter, Long> sortedChrsByTime = new LinkedHashMap<>();
    private Pair<MapleCharacter, Long> mapowner;
    private ScheduledFuture<?> HPQMobDelay = null;

    public MapleMap(int mapid, int channel, int returnMapId, float monsterRate) {
        this.mapid = mapid;
        this.channel = channel;
        this.returnMapId = returnMapId;
        if (monsterRate > 0) {
            this.monsterRate = monsterRate;
            boolean greater1 = monsterRate > 1.0;
            this.monsterRate = (float) Math.abs(1.0 - this.monsterRate);
            this.monsterRate = this.monsterRate / 2.0f;
            if (greater1) {
                this.monsterRate = 1.0f + this.monsterRate;
            } else {
                this.monsterRate = 1.0f - this.monsterRate;
            }
            TimerManager.getInstance().register(new RespawnWorker(), 10000);
        }
    }

    public void toggleDrops() {
        dropsDisabled = !dropsDisabled;
    }

    public int getId() {
        return mapid;
    }

    public MapleMap getReturnMap() {
        return ChannelServer.getInstance(channel).getMapFactory().getMap(returnMapId);
    }

    public int getReturnMapId() {
        return returnMapId;
    }

    public int getForcedReturnId() {
        return forcedReturnMap;
    }

    public MapleMap getForcedReturnMap() {
        return ChannelServer.getInstance(channel).getMapFactory().getMap(forcedReturnMap);
    }

    public void setForcedReturnMap(int map) {
        this.forcedReturnMap = map;
    }

    public int getTimeLimit() {
        return timeLimit;
    }

    public void setTimeLimit(int timeLimit) {
        this.timeLimit = timeLimit;
    }

    public int getCurrentPartyId() {
        for (MapleCharacter chr : this.getCharacters()) {
            if (chr.getPartyId() != -1) {
                return chr.getPartyId();
            }
        }
        return -1;
    }

    public void addMapObject(MapleMapObject mapobject) {
        synchronized (this.mapobjects) {
            mapobject.setObjectId(runningOid);
            this.mapobjects.put(Integer.valueOf(runningOid), mapobject);
            incrementRunningOid();
        }
    }

    private void spawnAndAddRangedMapObject(MapleMapObject mapobject, DelayedPacketCreation packetbakery, SpawnCondition condition) {
        synchronized (this.mapobjects) {
            mapobject.setObjectId(runningOid);
            synchronized (characters) {
                for (MapleCharacter chr : characters) {
                    if (condition == null || condition.canSpawn(chr)) {
                        if (chr.getPosition().distanceSq(mapobject.getPosition()) <= MapleCharacter.MAX_VIEW_RANGE_SQ && !chr.isFake()) {
                            packetbakery.sendPackets(chr.getClient());
                            chr.addVisibleMapObject(mapobject);
                        }
                    }
                }
            }
            this.mapobjects.put(Integer.valueOf(runningOid), mapobject);
            incrementRunningOid();
        }
    }

    private void incrementRunningOid() {
        runningOid++;
        for (int numIncrements = 1; numIncrements < MAX_OID; numIncrements++) {
            if (runningOid > MAX_OID) {
                runningOid = 100;
            }
            if (this.mapobjects.containsKey(Integer.valueOf(runningOid))) {
                runningOid++;
            } else {
                return;
            }
        }
        throw new RuntimeException("Out of OIDs on map " + mapid + " (channel: " + channel + ")");
    }

    public void removeMapObject(int num) {
        synchronized (this.mapobjects) {
            if (mapobjects.containsKey(num)) {
                this.mapobjects.remove(Integer.valueOf(num));
            }
        }
    }

    public void removeMapObject(MapleMapObject obj) {
        removeMapObject(obj.getObjectId());
    }

    private Point calcPointBelow(Point initial) {
        MapleFoothold fh = footholds.findBelow(initial);
        if (fh == null) {
            return null;
        }
        int dropY = fh.getY1();
        if (!fh.isWall() && fh.getY1() != fh.getY2()) {
            double s1 = Math.abs(fh.getY2() - fh.getY1());
            double s2 = Math.abs(fh.getX2() - fh.getX1());
            double s4 = Math.abs(initial.x - fh.getX1());
            double alpha = Math.atan(s2 / s1);
            double beta = Math.atan(s1 / s2);
            double s5 = Math.cos(alpha) * (s4 / Math.cos(beta));
            if (fh.getY2() < fh.getY1()) {
                dropY = fh.getY1() - (int) s5;
            } else {
                dropY = fh.getY1() + (int) s5;
            }
        }
        return new Point(initial.x, dropY);
    }

    private Point calcDropPos(Point initial, Point fallback) {
        Point ret = calcPointBelow(new Point(initial.x, initial.y - 99));
        if (ret == null) {
            return fallback;
        }
        return ret;
    }

    private void dropFromMonster(MapleCharacter dropOwner, MapleMonster monster) {
        if (dropsDisabled || monster.dropsDisabled()) {
            return;
        }
        int maxDrops;
        int maxMesos;
        int droppedMesos = 0;
        boolean dropBoss = false;
        final boolean explosive = monster.isExplosive();
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        ChannelServer cserv = dropOwner.getClient().getChannelServer();
        double showdown = 100.0;
        if (monster.isTaunted()) {
            if (monster.getTaunter().getJob().getId() == 422) {
                showdown += (monster.getTaunter().getSkillLevel(SkillFactory.getSkill(4221003)) + 10);
            } else if (monster.getTaunter().getJob().getId() == 412) {
                showdown += (monster.getTaunter().getSkillLevel(SkillFactory.getSkill(4121003)) + 10);
            }
        }
        if (explosive) {
            maxDrops = (int)(10 * cserv.getBossDropRate() * (showdown / 100.0));
            maxMesos = 1;
        } else {
            maxDrops = (int)(2 * cserv.getDropRate() * (showdown / 100.0));
            maxMesos = (new Random().nextInt(10000) >= 2500) ? 1 : 0;
        }
        switch (monster.getId()) {
            case 8800002://Zakum3
                maxDrops = 90;
                maxMesos = 7;
                dropBoss = true;
                break;
            case 9400014://crow 
            case 6130101://mushmom
            case 6300005://zmm
            case 9400205://bmm
                maxDrops = 15;
                break;
            case 8510000://pianus right
            case 8520000://pianus left
            case 8500002://Pap clock 2nd form
                maxDrops = 35;
                maxMesos = 7;
                dropBoss = true;
                break;
            case 2220000://Mano
            case 3220000://Stumpy
            case 3220001://Deo
            case 4220000://Clam thing?
            case 5220002://Faust
            case 5220000://King Clang
            case 5220003://Timer
            case 6220000://Dyle
            case 6220001://Zeta 
            case 7220000://Tae Roon
            case 7220001://Nine-tailed fox
            case 7220002://King Sage Cat
            case 8220000://Eliza
            case 8220001://Snowman
            case 8220002://Chimera
                maxDrops = 35;
                maxMesos = 5;
                dropBoss = true;
                break;
            case 8220003://Leviathan
                maxDrops = 35;
                maxMesos = 2;
                dropBoss = true;
                break;
        }
        List<Integer> toDrop = new ArrayList<Integer>();
        for (int i = 0; i < maxDrops; i++) {
            toDrop.add(monster.getDrop());
            int chance = Randomizer.nextInt(18000);
            
            /*if (chance >= 0 && chance <= 5) { 
                toDrop.add(5220000);
            }
            if (chance >= 6 && chance <= 15) { 
                toDrop.add(4031531);
            }
            if (chance >= 16 && chance <= 25) {  
                toDrop.add(4031530);
            }
            if (chance >= 17700 && chance <= 18000) {
                toDrop.add(4031875);
            }*/
        }
        for (int i = 0; i < maxDrops; i++) {
            toDrop.add(monster.getDrop());
        //legit the dumbest way to do quest drops but it seems to fix that error with quest drops...
        int chance = Randomizer.nextInt(100000);
        if (dropOwner.getQuestStatus(3608) == 1) {
            toDrop.add(4031223);
        }
            if (dropOwner.getQuestStatus(2071) == 1) {
                if (chance >= 85000 && chance <= 100000 && monster.getId() == 2230102) {
                    toDrop.add(4031155);
                }
            }
            if (dropOwner.getQuestStatus(8142) == 1) {
                if (chance >= 1 && chance <= 100000 && monster.getId() == 9409000){
                    toDrop.add(4000300);
                }
                if (chance >= 1 && chance <= 100000 && monster.getId() == 9409001){
                    toDrop.add(4000301);
                }
            }
            if (dropOwner.getQuestStatus(3810) == 1) {
                if (chance >= 99500 && chance <= 100000 && monster.getId() == 5120506) {
                    toDrop.add(4031432);
                }
            }
            if (dropOwner.getQuestStatus(4917) == 1) {
                if (chance >= 95000 && chance <= 100000 && monster.getId() == 4230113) {
                    toDrop.add(4031675);
                }
            }

            if (dropOwner.getQuestStatus(2096) == 1) {
                if (chance >= 85000 && chance <= 100000 && monster.getId() == 4230100) {
                    toDrop.add(4031212);
                }
            }
if (dropOwner.getQuestStatus(6120) == 1) {
    if (chance >= 98000 && chance <= 100000 && (monster.getId() == 8150300 || monster.getId() == 8150301 || monster.getId() == 8150302)) {
        toDrop.add(4031449);
    }
}
if (dropOwner.getQuestStatus(6121) == 1) {
    if (chance >= 98000 && chance <= 100000 && (monster.getId() == 8150300 || monster.getId() == 8150301 || monster.getId() == 8150302)) {
        toDrop.add(4031482);
    }
}
if (dropOwner.getQuestStatus(6122) == 1) {
    if (chance >= 98000 && chance <= 100000 && (monster.getId() == 8150300 || monster.getId() == 8150301 || monster.getId() == 8150302)) {
        toDrop.add(4031483);
    }
}
if (dropOwner.getQuestStatus(6123) == 1) {
    if (chance >= 98000 && chance <= 100000 && (monster.getId() == 8150300 || monster.getId() == 8150301 || monster.getId() == 8150302)) {
        toDrop.add(4031484);
    }
}
if (dropOwner.getQuestStatus(6124) == 1) {
    if (chance >= 98000 && chance <= 100000 && (monster.getId() == 8150300 || monster.getId() == 8150301 || monster.getId() == 8150302)) {
        toDrop.add(4031485);
    }
}

if (dropOwner.getQuestStatus(2001) == 1) {
    if (chance >= 99920 && chance <= 100000 && monster.getId() == 3230100) { //8% chance
        toDrop.add(4001004);
    }
}
if (dropOwner.getQuestStatus(2084) == 1) {
    if (chance >= 80000 && chance <= 100000 && monster.getId() == 9500108) {
        toDrop.add(4031164);
    }
}
if (dropOwner.getQuestStatus(3231) == 1) {
    if (chance >= 94000 && chance <= 100000 && monster.getId() == 4230113) {
        toDrop.add(4031098);
    }
}
if (dropOwner.getQuestStatus(2047) == 1) {
    if (chance >= 99900 && chance <= 100000 && monster.getId() == 5130102) {
        toDrop.add(4001005);
    }
}
if (dropOwner.getQuestStatus(2045) == 1) {
    if (chance >= 99900 && chance <= 100000 && monster.getId() == 5130102) {
        toDrop.add(4001005);
    }
}
if (dropOwner.getQuestStatus(2065) == 1) {
    if (chance >= 50000 && chance <= 100000 && (monster.getId() == 1110100 || monster.getId() == 9500105)) {
        toDrop.add(4031146);
    }
    if (chance >= 50000 && chance <= 100000 && monster.getId() == 1130100) {
        toDrop.add(4031147);
    }
}
if (dropOwner.getQuestStatus(3093) == 1) {
    if (chance >= 80000 && chance <= 100000 && monster.getId() == 5300000) {
        toDrop.add(4031311);
    }
}
if (dropOwner.getQuestStatus(2017) == 1) {
    if (chance >= 60000 && chance <= 100000 && (monster.getId() == 3210100 || monster.getId() == 9420503)) {
        toDrop.add(4031405);
    }
}
if (dropOwner.getQuestStatus(3448) == 1) {
    if (chance >= 60000 && chance <= 100000 && monster.getId() == 6230300) {
        toDrop.add(4031189);
    }
}
if (dropOwner.getQuestStatus(3449) == 1) {
    if (chance >= 70000 && chance <= 100000 && monster.getId() == 6230300) {
        toDrop.add(4031195);
    }
}
if (dropOwner.getQuestStatus(2173) == 1) {
    if (chance >= 60000 && chance <= 100000 && (monster.getId() == 1210100 || monster.getId() == 130101)) {
        toDrop.add(4031846);
    }
}
if (dropOwner.getQuestStatus(3712) == 1) {
    if (chance >= 90000 && chance <= 100000 && monster.getId() == 7130501) {
        toDrop.add(4031412);
    }
    if (chance >= 90000 && chance <= 100000 && monster.getId() == 8140111) {
        toDrop.add(4031413);
    }
    if (chance >= 90000 && chance <= 100000 && monster.getId() == 8150201) {
        toDrop.add(4031414);
    }
    if (chance >= 90000 && chance <= 100000 && monster.getId() == 8150302) {
        toDrop.add(4031415);
    }
}
if (dropOwner.getQuestStatus(3078) == 1) {
    if (chance >= 40000 && chance <= 47000 && monster.getId() == 8150100) {
        toDrop.add(4031255);
    }
    if (chance >= 94000 && chance <= 100000 && monster.getId() == 8150100) {
        toDrop.add(4031254);
    }
    if (chance >= 80000 && chance <= 90000 && monster.getId() == 8150100) {
        toDrop.add(4031252);
    }
    if (chance >= 40000 && chance <= 47000 && monster.getId() == 8150101) {
        toDrop.add(4031255);
    }
    if (chance >= 94000 && chance <= 100000 && monster.getId() == 8150101) {
        toDrop.add(4031254);
    }
    if (chance >= 80000 && chance <= 90000 && monster.getId() == 8150101) {
        toDrop.add(4031252);
    }
}
if (dropOwner.getQuestStatus(3080) == 1) {
    if (chance >= 97000 && chance <= 100000 && monster.getId() == 2230105) {
        toDrop.add(4031259);
    }
    if (chance >= 80000 && chance <= 83000 && monster.getId() == 2230106) {
        toDrop.add(4031260);
    }
    if (chance >= 84000 && chance <= 87000 && monster.getId() == 2230108) {
        toDrop.add(4031261);
    }
}
if (dropOwner.getQuestStatus(3081) == 1) {
    if (chance >= 96000 && chance <= 100000 && monster.getId() == 2230200) {
        toDrop.add(4031262);
    }
    if (chance >= 70000 && chance <= 74000 && monster.getId() == 2230111) {
        toDrop.add(4031263);
    }
    if (chance >= 84000 && chance <= 88000 && monster.getId() == 2230109) {
        toDrop.add(4031264);
    }
}
if (dropOwner.getQuestStatus(3082) == 1) {
    if (chance >= 94000 && chance <= 100000 && monster.getId() == 4230200) {
        toDrop.add(4031265);
    }
    if (chance >= 70000 && chance <= 76000 && monster.getId() == 4230123) {
        toDrop.add(4031266);
    }
    if (chance >= 84000 && chance <= 90000 && monster.getId() == 4230124) {
        toDrop.add(4031267);
    }
}
if (dropOwner.getQuestStatus(2070) == 1) {
    if (chance >= 90000 && chance <= 100000 && monster.getId() == 2130100) {
        toDrop.add(4031153);
    }
}
if (dropOwner.getQuestStatus(3911) == 1) {
    if (chance >= 85000 && chance <= 100000 && monster.getId() == 2100108) {
        toDrop.add(4031568);
    }
}
if (dropOwner.getQuestStatus(8848) == 1) {
    if (chance >= 99000 && chance <= 100000 && monster.getId() == 9400509) {
        toDrop.add(4031523);
    }
}
if (dropOwner.getQuestStatus(8849) == 1) {
    if (chance >= 80000 && chance <= 100000 && monster.getId() == 3110101) {
        toDrop.add(4031527);
    }
}
if (dropOwner.getQuestStatus(3413) == 1) {
    if (chance >= 70000 && chance <= 100000 && (monster.getId() == 4240000 || monster.getId() == 9500122)) {
        toDrop.add(4031102);
    }
}
if (dropOwner.getQuestStatus(3414) == 1) {
    if (chance >= 98000 && chance <= 100000 && monster.getId() == 4230116) {
        toDrop.add(4031103);
    }
    if (chance >= 94000 && chance <= 97000 && monster.getId() == 4230117) {
        toDrop.add(4031104);
    }
    if (chance >= 89000 && chance <= 93000 && monster.getId() == 4230118) {
        toDrop.add(4031105);
    }
    if (chance >= 80000 && chance <= 85000 && (monster.getId() == 4240000 || monster.getId() == 9500122)) {
        toDrop.add(4031106);
    }
}
if (dropOwner.getQuestStatus(3088) == 1) {
    if (chance >= 94000 && chance <= 100000 && monster.getId() == 3230200) {
        toDrop.add(4031309);
    }
    if (chance >= 70000 && chance <= 79000 && monster.getId() == 4230106) {
        toDrop.add(4031309);
    }
    if (chance >= 84000 && chance <= 96000 && monster.getId() == 5120000) {
        toDrop.add(4031309);
    }
}
if (dropOwner.getQuestStatus(3076) == 1) {
    if (chance >= 50000 && chance <= 75000 && monster.getId() == 8140600) {
        toDrop.add(4031251);
    }
    if (chance >= 90000 && chance <= 97000 && monster.getId() == 8140600) {
        toDrop.add(4031256);
    }
    if (chance >= 1 && chance <= 100000 && (monster.getId() == 8520000 || monster.getId() == 8510000)) {
        toDrop.add(4031253);
    }
}
if (dropOwner.getQuestStatus(7301) == 1) {
    if (chance >= 30000 && chance <= 35000 && (monster.getId() == 8150200 || monster.getId() == 8150201)) {
        toDrop.add(4001075);
    }
    if (chance >= 1 && chance <= 100000 && monster.getId() == 8180000) {
        toDrop.add(4001076);
    }
    if (chance >= 80000 && chance <= 100000 && monster.getId() == 8150200) {
        toDrop.add(4001078);
    }
    if (chance >= 40000 && chance <= 46000 && monster.getId() == 8150300) {
        toDrop.add(4001077);
    }
    if (chance >= 94000 && chance <= 100000 && monster.getId() == 8150301) {
        toDrop.add(4001077);
    }
    if (chance >= 80000 && chance <= 86000 && monster.getId() == 8150302) {
        toDrop.add(4001077);
    }
}
if (dropOwner.getQuestStatus(7303) == 1) {
    if (chance >= 30000 && chance <= 35000 && (monster.getId() == 8150200 || monster.getId() == 8150201)) {
        toDrop.add(4001103);
    }
    if (chance >= 1 && chance <= 100000 && monster.getId() == 8180000) {
        toDrop.add(4001104);
    }
    if (chance >= 80000 && chance <= 100000 && monster.getId() == 8150200) {
        toDrop.add(4001078);
    }
    if (chance >= 40000 && chance <= 46000 && monster.getId() == 8150300) {
        toDrop.add(4001105);
    }
    if (chance >= 94000 && chance <= 100000 && monster.getId() == 8150301) {
        toDrop.add(4001105);
    }
    if (chance >= 80000 && chance <= 86000 && monster.getId() == 8150302) {
        toDrop.add(4001105);
    }
}
if (dropOwner.getQuestStatus(3438) == 1) {
    if (chance >= 93000 && chance <= 100000 && (monster.getId() == 3230400 || monster.getId() == 9500107)) {
        toDrop.add(4031135);
    }
}
if (dropOwner.getQuestStatus(3440) == 1) {
    if (chance >= 93000 && chance <= 100000 && (monster.getId() == 3230400 || monster.getId() == 9500107)) {
        toDrop.add(4031140);
    }
}
if (dropOwner.getQuestStatus(4916) == 1) {
    if (chance >= 95000 && chance <= 100000 && monster.getId() == 4230116) {
        toDrop.add(4031674);
    }
}
if (dropOwner.getQuestStatus(4916) == 1) {
    if (chance >= 95000 && chance <= 100000 && monster.getId() == 4230116) {
        toDrop.add(4031674);
    }
}
if (dropOwner.getQuestStatus(2048) == 1) {
    if (chance >= 99940 && chance <= 100000 && monster.getId() == 3210200) {
        toDrop.add(4001006);
    }
    if (chance >= 99940 && chance <= 100000 && monster.getId() == 3210201) {
        toDrop.add(4001006);
    }
    if (chance >= 99900 && chance <= 100000 && monster.getId() == 6130100) {
        toDrop.add(4001006);
    }
    if (chance >= 99900 && chance <= 100000 && monster.getId() == 9420511) {
        toDrop.add(4001006);
    }
}
if (dropOwner.getQuestStatus(2048) == 1) {
    if (chance >= 99940 && chance <= 100000 && monster.getId() == 3210200) {
        toDrop.add(4001006);
    }
    if (chance >= 99940 && chance <= 100000 && monster.getId() == 3210201) {
        toDrop.add(4001006);
    }
    if (chance >= 99900 && chance <= 100000 && monster.getId() == 6130100) {
        toDrop.add(4001006);
    }
    if (chance >= 99900 && chance <= 100000 && monster.getId() == 9420511) {
        toDrop.add(4001006);
    }
}
if (dropOwner.getQuestStatus(3611) == 1) {
    if (chance >= 1 && chance <= 100000 && monster.getId() == 7130400) {
        toDrop.add(4031232);
    }
    if (chance >= 1 && chance <= 100000 && (monster.getId() == 7130401 || monster.getId() == 9500130)) {
        toDrop.add(4031233);
    }
    if (chance >= 1 && chance <= 100000 && monster.getId() == 7130402) {
        toDrop.add(4031234);
    }
}
if (dropOwner.getQuestStatus(4914) == 1) {
    if (chance >= 95000 && chance <= 100000 && monster.getId() == 9400543) {
        toDrop.add(4031680);
    }
}
if (dropOwner.getQuestStatus(4915) == 1) {
    if (chance >= 95000 && chance <= 100000 && monster.getId() == 9400546) {
        toDrop.add(4031681);
    }
}
if (dropOwner.getQuestStatus(7100) == 1) {
    if (chance >= 50000 && chance <= 100000 && (monster.getId() == 6230400 || monster.getId() == 6130200 || monster.getId() == 6230300 || monster.getId() == 6230500)) {
        toDrop.add(4031170);
    }
}
if (dropOwner.getQuestStatus(7101) == 1) {
    if (chance >= 60000 && chance <= 100000 &&
            (monster.getId() == 7160000 
            || monster.getId() == 7130300)) {
        toDrop.add(4031171);
    }
    if (chance >= 50000 && chance <= 100000 &&
            (monster.getId() == 6230400 
            || monster.getId() == 6130200 
            || monster.getId() == 6230300 
            || monster.getId() == 6230500
            )) {
        toDrop.add(4031175);
    }
}
if (dropOwner.getQuestStatus(7103) == 1) {
    if (chance >= 95000 && chance <= 100000 && (monster.getId() == 8160000 || monster.getId() == 8170000)) {
        toDrop.add(4031172);
    }
}
if (dropOwner.getQuestStatus(3803) == 1) {
    if (chance >= 96000 && chance <= 100000 && monster.getId() == 5120506) {
        toDrop.add(4000299);
    }
}
if (dropOwner.getQuestStatus(3804) == 1) {
    if (chance >= 95000 && chance <= 100000 && monster.getId() == 6130209) {
        toDrop.add(4031433);
    }
}
if (dropOwner.getQuestStatus(2074) == 1) {
    if (chance >= 92000 && chance <= 100000 && monster.getId() == 3230306) {
        toDrop.add(4031159);
    }
}
if (dropOwner.getQuestStatus(3706) == 1) {
    if (chance >= 1 && chance <= 100000 && monster.getId() == 8810018) {
        toDrop.add(4001094);
    }
}
if (dropOwner.getQuestStatus(3227) == 1) {
    if (chance >= 92000 && chance <= 100000 && monster.getId() == 4230119) {
        toDrop.add(4031090);
    }
}
if (dropOwner.getQuestStatus(3814) == 1) {
    if (chance >= 50000 && chance <= 100000 && monster.getId() == 9300119) {
        toDrop.add(4001118);
    }
}
if (dropOwner.getQuestStatus(8868) == 1) {
    if (chance >= 99000 && chance <= 100000 && monster.getId() == 3230308) {
        toDrop.add(4031525);
    }
}
if (dropOwner.getQuestStatus(3441) == 1) {
    if (chance >= 80000 && chance <= 100000 && monster.getId() == 4230119) {
        toDrop.add(4031206);
    }
}
if (dropOwner.getQuestStatus(3223) == 1) {
    if (chance >= 95000 && chance <= 100000 && monster.getId() == 3230302) {
        toDrop.add(4031089);
    }
}
if (dropOwner.getQuestStatus(8848) == 1) {
    if (chance >= 99000 && chance <= 100000 && monster.getId() == 3210201) {
        toDrop.add(4031524);
    }
}
if (dropOwner.getQuestStatus(3631) == 1) {
    if (chance >= 97000 && chance <= 100000 && monster.getId() == 2230106) {
        toDrop.add(4031269);
    }
    if (chance >= 90000 && chance <= 100000 && monster.getId() == 5100003) {
        toDrop.add(4031268);
    }
}
if (dropOwner.getQuestStatus(3416) == 1) {
    if (chance >= 92000 && chance <= 100000 && monster.getId() == 4230112) {
        toDrop.add(4031115);
    }
}
if (dropOwner.getQuestStatus(3632) == 1) {
    if (chance >= 90000 && chance <= 100000 && monster.getId() == 3210208) {
        toDrop.add(4031279);
    }
}
if (dropOwner.getQuestStatus(3621) == 1) {
    if (chance >= 80000 && chance <= 100000 && monster.getId() == 6130202) {
        toDrop.add(4031222);
    }
}
if (dropOwner.getQuestStatus(3048) == 1) {
    if (chance >= 90000 && chance <= 100000 && monster.getId() == 4230105) {
        toDrop.add(4031200);
    }
}
if (dropOwner.getQuestStatus(2097) == 1) {
    if (chance >= 85000 && chance <= 100000 &&
            (monster.getId() == 6230100 || monster.getId() == 9200014)) {
        toDrop.add(4031213);
    }
    if (chance >= 88000 && chance <= 100000 && monster.getId() == 7130100) {
        toDrop.add(4031214);
    }
    if (chance >= 88000 && chance <= 100000 &&
            (monster.getId() == 7130101 || monster.getId() == 9500129)) {
        toDrop.add(4031215);
    }
}
if (dropOwner.getQuestStatus(3071) == 1) {
    if (chance >= 99000 && chance <= 100000 &&
            (monster.getId() == 5130107 || monster.getId() == 5130108)) {
        toDrop.add(4031218);
    }
}
if (dropOwner.getQuestStatus(2145) == 1) {
    if (chance >= 85000 && chance <= 100000 &&
            (monster.getId() == 130100 
            || monster.getId() == 1110101
            || monster.getId() == 1130100
            || monster.getId() == 2130100)) {
        toDrop.add(4031773);
    }
}
if (dropOwner.getQuestStatus(3606) == 1) {
    if (chance >= 80000 && chance <= 100000 &&
            monster.getId() == 4230300) {
        toDrop.add(4031241);
    }
}
if (dropOwner.getQuestStatus(3844) == 1) {
    if (chance >= 50000 && chance <= 100000 &&
            monster.getId() == 7220002) {
        toDrop.add(4031789);
    }
}
if (dropOwner.getQuestStatus(2099) == 1) {
    if (chance >= 65000 && chance <= 100000 &&
            monster.getId() == 3230100) {
        toDrop.add(4031239);
    }
}
if (dropOwner.getQuestStatus(3629) == 1) {
    if (chance >= 90000 && chance <= 100000 &&
            monster.getId() == 5100003) {
        toDrop.add(4031268);
    }
    if (chance >= 87000 && chance <= 90000 &&
            (monster.getId() == 2230106 || monster.getId() == 9410019)) {
        toDrop.add(4031269);
    }
}
if (dropOwner.getQuestStatus(2017) == 1) {
    if (chance >= 99920 && chance <= 100000 &&
            monster.getId() == 3210100) {
        toDrop.add(4001000);
    }
}
        }


        Set<Integer> alreadyDropped = new HashSet<Integer>();
        byte htpendants = 0, htstones = 0, mesos = 0;
        for (int i = 0; i < toDrop.size(); i++) {
            if (toDrop.get(i) == -1) {
                if (!this.isPQMap()) {
                    if (alreadyDropped.contains(-1)) {
                        if (!explosive) {
                            toDrop.remove(i);
                            i--;
                        } else {
                            if (mesos < 9) {
                                mesos++;
                            } else {
                                toDrop.remove(i);
                                i--;
                            }
                        }
                    } else {
                        alreadyDropped.add(-1);
                    }
                }
            } else {
                if (alreadyDropped.contains(toDrop.get(i)) && !explosive) {
                    toDrop.remove(i);
                    i--;
                } else {
                    if (toDrop.get(i) == 2041200) { // Stone
                        if (htstones > 2) {
                            toDrop.remove(i);
                            i--;
                            continue;
                        } else {
                            htstones++;
                        }
                    } else if (toDrop.get(i) == 1122000) { // Pendant
                        if (htstones > 2) {
                            toDrop.remove(i);
                            i--;
                            continue;
                        } else {
                            htpendants++;
                        }
                    }
                    alreadyDropped.add(toDrop.get(i));
                }
            }
        }
        if (toDrop.size() > maxDrops) {
            toDrop = toDrop.subList(0, maxDrops);
        }
        if (mesos < 7 && explosive) {
            for (int i = mesos; i < 7; i++) {
                toDrop.add(-1);
            }
        }
        if (mesos < maxMesos && dropBoss) {
            for (int i = mesos; i < maxMesos; i++) {
                toDrop.add(-1);
            }
        }
        int shiftDirection = 0;
        int shiftCount = 0;
        int curX = Math.min(Math.max(monster.getPosition().x - 25 * (toDrop.size() / 2), footholds.getMinDropX() + 25), footholds.getMaxDropX() - toDrop.size() * 25);
        int curY = Math.max(monster.getPosition().y, footholds.getY1());
        while (shiftDirection < 3 && shiftCount < 1000) {
            if (shiftDirection == 1) {
                curX += 25;
            } else if (shiftDirection == 2) {
                curX -= 25;
            }
            for (int i = 0; i < toDrop.size(); i++) {
                MapleFoothold wall = footholds.findWall(new Point(curX, curY), new Point(curX + toDrop.size() * 25, curY));
                if (wall != null) {
                    if (wall.getX1() < curX) {
                        shiftDirection = 1;
                        shiftCount++;
                        break;
                    } else if (wall.getX1() == curX) {
                        if (shiftDirection == 0) {
                            shiftDirection = 1;
                        }
                        shiftCount++;
                        break;
                    } else {
                        shiftDirection = 2;
                        shiftCount++;
                        break;
                    }
                } else if (i == toDrop.size() - 1) {
                    shiftDirection = 3;
                }
                final Point dropPos = calcDropPos(new Point(curX + i * 25, curY), new Point(monster.getPosition()));
                final int drop = toDrop.get(i);
                if (drop == -1) {
                    if (droppedMesos < maxMesos) {
                    if (monster.isBoss()) {
                        final int cc = ChannelServer.getInstance(dropOwner.getClient().getChannel()).getMesoRate() + 25;
                        final MapleMonster dropMonster = monster;
                        Random r = new Random();
                        double mesoDecrease = Math.pow(0.93, monster.getExp() / 300.0);
                        if (mesoDecrease > 1.0) {
                            mesoDecrease = 1.0;
                        } else if (mesoDecrease < 0.001) {
                            mesoDecrease = 0.005;
                        }
                        int tempmeso = Math.min(30000, (int) (mesoDecrease * (monster.getExp()) * (1.0 + r.nextInt(20)) / 10.0));
                        if(dropOwner.getBuffedValue(MapleBuffStat.MESOUP) != null) {
                            tempmeso = (int) (tempmeso * dropOwner.getBuffedValue(MapleBuffStat.MESOUP).doubleValue() / 100.0);
                        }
                        final int dmesos = tempmeso;
                        if (dmesos > 0) {
                            final int mesosR = dmesos * cc;
                            final MapleCharacter dropChar = dropOwner;
                            final boolean publicLoott = this.isPQMap();
                            TimerManager.getInstance().schedule(new Runnable() {
                                public void run() {
                                    spawnMesoDrop(mesosR, mesosR, dropPos, dropMonster, dropChar, explosive || publicLoott);
                                }
                            }, monster.getAnimationTime("die1"));
                        }
                        droppedMesos++;
                    } else {
                        final int mesoRate = ChannelServer.getInstance(dropOwner.getClient().getChannel()).getMesoRate();
                        Random r = new Random();
                        double mesoDecrease = Math.pow(0.93, monster.getExp() / 300.0);
                        if (mesoDecrease > 1.0) {
                            mesoDecrease = 1.0;
                        }
                        int tempmeso = Math.min(30000, (int) (mesoDecrease * (monster.getExp()) * (1.0 + r.nextInt(20)) / 10.0));
                        if (dropOwner.getBuffedValue(MapleBuffStat.MESOUP) != null) {
                            tempmeso = (int) (tempmeso * dropOwner.getBuffedValue(MapleBuffStat.MESOUP).doubleValue() / 100.0);
                        }
                        if (monster.isBoss() || monster.getId() == 6130101) {
                            tempmeso *= 2;
                        }
                        if (monster.getId() == 9400509) {//Sakura Cellion
                            tempmeso *= 3;
                        }
                            //LMPQ monsters don't drop mesos
                        if (monster.getId() >= 9400209 && monster.getId() <= 9400218 || monster.getId() == 9300001 || monster.getId() == 9300094 || monster.getId() == 9300095) {
                        tempmeso = 0;
                         }
                        if(monster.getEventInstance() != null)
                            tempmeso = 0;
                        
                        final int meso = tempmeso;
                        if (meso > 0) {
                            final int mesoR = meso*mesoRate;
                            final MapleMonster dropMonster = monster;
                            final MapleCharacter dropChar = dropOwner;
                            final boolean publicLoott = this.isPQMap();
                            TimerManager.getInstance().schedule(new Runnable() {
                                public void run() {
                                    spawnMesoDrop(mesoR, mesoR, dropPos, dropMonster, dropChar, explosive || publicLoott);
                                }
                            }, monster.getAnimationTime("die1"));
                        }
                        droppedMesos++;
                    }
                    }
                } else {
                    IItem idrop;
                    MapleInventoryType type = ii.getInventoryType(drop);
                    if (type.equals(MapleInventoryType.EQUIP)) {
                        Equip nEquip = ii.randomizeStats(dropOwner.getClient(), (Equip) ii.getEquipById(drop));
                        idrop = nEquip;
                    } else {
                        idrop = new Item(drop, (byte) 0, (short) 1);
                        if (ii.isArrowForBow(drop) || ii.isArrowForCrossBow(drop)) {
                            if (dropOwner.getJob().getId() / 100 == 3) {
                                idrop.setQuantity((short) (1 + 100 * Math.random()));
                            }
                        } else if (ii.isThrowingStar(drop) || ii.isBullet(drop)) {
                            idrop.setQuantity((short) (1));
                        }
                    }
                    if (monster.getId() == 9400218 && idrop.getItemId() == 4001106) {
                        double klm = Math.random();
                        if (klm < 0.7) // 7/10 chance that a Tauromacis will drop 50 tickets! :D
                        {
                            idrop.setQuantity((short) (50)); // 50 Tickets from a Tauromacis in LMPQ
                        }
                    }
                    final MapleMapItem mdrop = new MapleMapItem(idrop, dropPos, monster, dropOwner);
                    final MapleMapObject dropMonster = monster;
                    final MapleCharacter dropChar = dropOwner;
                    final TimerManager tMan = TimerManager.getInstance();
                    tMan.schedule(new Runnable() {
                        public void run() {
                            spawnAndAddRangedMapObject(mdrop, new DelayedPacketCreation() {
                                public void sendPackets(MapleClient c) {
                                    c.getSession().write(MaplePacketCreator.dropItemFromMapObject(drop, mdrop.getObjectId(), dropMonster.getObjectId(), explosive ? 0 : dropChar.getId(), dropMonster.getPosition(), dropPos, (byte) 1));
                                }
                            }, null);
                            tMan.schedule(new ExpireMapItemJob(mdrop), dropLife);
                        }
                    }, monster.getAnimationTime("die1"));

                }
            }
        }
    }

    public boolean damageMonster(MapleCharacter chr, MapleMonster monster, int damage) {
        if (monster.getId() == 8800000) {
            Collection<MapleMapObject> objects = chr.getMap().getMapObjects();
            for (MapleMapObject object : objects) {
                MapleMonster mons = chr.getMap().getMonsterByOid(object.getObjectId());
                if (mons != null) {
                    if (mons.getId() >= 8800003 && mons.getId() <= 8800010) {
                        return true;
                    }
                }
            }
        }
        if (monster.isAlive()) {
            synchronized (monster) {
                if (!monster.isAlive()) {
                    return false;
                }
                if (damage > 0) {
                    int monsterhp = monster.getHp();
                    monster.damage(chr, damage, true);
                    if (!monster.isAlive()) {
                        killMonster(monster, chr, true);
                        if (monster.getId() >= 8810002 && monster.getId() <= 8810009) {
                            Collection<MapleMapObject> objects = chr.getMap().getMapObjects();
                            for (MapleMapObject object : objects) {
                                MapleMonster mons = chr.getMap().getMonsterByOid(object.getObjectId());
                                if (mons != null) {
                                    if (mons.getId() == 8810018) {
                                        damageMonster(chr, mons, monsterhp);
                                    }
                                }
                            }
                        }
                    } else {
                        if (monster.getId() >= 8810002 && monster.getId() <= 8810009) {
                            Collection<MapleMapObject> objects = chr.getMap().getMapObjects();
                            for (MapleMapObject object : objects) {
                                MapleMonster mons = chr.getMap().getMonsterByOid(object.getObjectId());
                                if (mons != null) {
                                    if (mons.getId() == 8810018) {
                                        damageMonster(chr, mons, damage);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    public void killMonster(final MapleMonster monster, final MapleCharacter chr, final boolean withDrops) {
        killMonster(monster, chr, withDrops, false, 1);
    }

    public void killMonster(final MapleMonster monster, final MapleCharacter chr, final boolean withDrops, final boolean secondTime) {
        killMonster(monster, chr, withDrops, secondTime, 1);
    }

    @SuppressWarnings("static-access")
    public void killMonster(final MapleMonster monster, final MapleCharacter chr, final boolean withDrops, final boolean secondTime, int animation) {
        if (monster.getId() == 8810018 && !secondTime) {
            TimerManager.getInstance().schedule(new Runnable() {

                @Override
                public void run() {
                    killMonster(monster, chr, withDrops, true, 1);
                    killAllMonsters(false);
                }
            }, 3000);
            return;
        }
        if (monster.getBuffToGive() > -1) {
            broadcastMessage(MaplePacketCreator.showOwnBuffEffect(monster.getBuffToGive(), 11));
            MapleItemInformationProvider mii = MapleItemInformationProvider.getInstance();
            MapleStatEffect statEffect = mii.getItemEffect(monster.getBuffToGive());
            synchronized (this.characters) {
                for (MapleCharacter character : this.characters) {
                    if (character.isAlive()) {
                        statEffect.applyTo(character);
                        broadcastMessage(MaplePacketCreator.showBuffeffect(character.getId(), monster.getBuffToGive(), 11, (byte) 1));
                    }
                }
            }
        }
        if (monster.getId() == 8810018) {
            try {
                chr.getClient().getChannelServer().getWorldInterface().broadcastMessage(chr.getName(), MaplePacketCreator.serverNotice(6, "To the crew that have finally conquered Horned Tail after numerous attempts, I salute thee! You are the true heroes of Leafre!!").getBytes());
            } catch (RemoteException e) {
                chr.getClient().getChannelServer().reconnectWorld();
            }
        }
        spawnedMonstersOnMap.decrementAndGet();
        monster.setHp(0);
        broadcastMessage(MaplePacketCreator.killMonster(monster.getObjectId(), animation), monster.getPosition());
        removeMapObject(monster);
        if (monster.getId() >= 8800003 && monster.getId() <= 8800010) {
            boolean makeZakReal = true;
            Collection<MapleMapObject> objects = getMapObjects();
            for (MapleMapObject object : objects) {
                MapleMonster mons = getMonsterByOid(object.getObjectId());
                if (mons != null) {
                    if (mons.getId() >= 8800003 && mons.getId() <= 8800010) {
                        makeZakReal = false;
                    }
                }
            }
            if (makeZakReal) {
                for (MapleMapObject object : objects) {
                    MapleMonster mons = chr.getMap().getMonsterByOid(object.getObjectId());
                    if (mons != null) {
                        if (mons.getId() == 8800000) {
                            makeMonsterReal(mons);
                            updateMonsterController(mons);
                        }
                    }
                }
            }
        }
        MapleCharacter dropOwner = monster.killBy(chr);
        if (withDrops && !monster.dropsDisabled()) {
            if (dropOwner == null) {
                dropOwner = chr;
            }
            dropFromMonster(dropOwner, monster);
        }
    }

    public void killAllMonsters(boolean drop) {
        List<MapleMapObject> players = null;
        if (drop) {
            players = getAllPlayer();
        }
        List<MapleMapObject> monsters = getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapleMapObjectType.MONSTER));
        for (MapleMapObject monstermo : monsters) {
            MapleMonster monster = (MapleMonster) monstermo;
            spawnedMonstersOnMap.decrementAndGet();
            monster.setHp(0);
            broadcastMessage(MaplePacketCreator.killMonster(monster.getObjectId(), true), monster.getPosition());
            removeMapObject(monster);
            if (drop) {
                int random = (int) Math.random() * (players.size());
                dropFromMonster((MapleCharacter) players.get(random), monster);
            }
        }
    }

    public void killMonster(int monsId) {
        for (MapleMapObject mmo : getMapObjects()) {
            if (mmo instanceof MapleMonster) {
                if (((MapleMonster) mmo).getId() == monsId) {
                    this.killMonster((MapleMonster) mmo, (MapleCharacter) getAllPlayer().get(0), false);
                }
            }
        }
    }

    public List<MapleMapObject> getAllPlayer() {
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapleMapObjectType.PLAYER));
    }

    public void destroyReactor(int oid) {
        synchronized (this.mapobjects) {
            final MapleReactor reactor = getReactorByOid(oid);
            TimerManager tMan = TimerManager.getInstance();
            broadcastMessage(MaplePacketCreator.destroyReactor(reactor));
            reactor.setAlive(false);
            removeMapObject(reactor);
            reactor.setTimerActive(false);
            if (reactor.getDelay() > 0) {
                tMan.schedule(new Runnable() {

                    @Override
                    public void run() {
                        respawnReactor(reactor);
                    }
                }, reactor.getDelay());
            }
        }
    }

    public void resetReactors() {
        synchronized (this.mapobjects) {
            for (MapleMapObject o : mapobjects.values()) {
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    ((MapleReactor) o).setState((byte) 0);
                    ((MapleReactor) o).setTimerActive(false);
                    broadcastMessage(MaplePacketCreator.triggerReactor((MapleReactor) o, 0));
                }
            }
        }
    }

    public void shuffleReactors() {
        List<Point> points = new ArrayList<Point>();
        synchronized (this.mapobjects) {
            for (MapleMapObject o : mapobjects.values()) {
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    points.add(((MapleReactor) o).getPosition());
                }
            }
            Collections.shuffle(points);
            for (MapleMapObject o : mapobjects.values()) {
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    ((MapleReactor) o).setPosition(points.remove(points.size() - 1));
                }
            }
        }
    }

    public void updateMonsterController(MapleMonster monster) {
        synchronized (monster) {
            if (!monster.isAlive()) {
                return;
            }
            if (monster.getController() != null) {
                if (monster.getController().getMap() != this) {
                    log.warn("Monstercontroller wasn't on same map");
                    monster.getController().stopControllingMonster(monster);
                } else {
                    return;
                }
            }
            int mincontrolled = -1;
            MapleCharacter newController = null;
            synchronized (characters) {
                for (MapleCharacter chr : characters) {
                    if (!chr.isHidden() && (chr.getControlledMonsters().size() < mincontrolled || mincontrolled == -1)) {
                        if (!chr.getName().equals("FaekChar")) { // TODO remove me for production release
                            mincontrolled = chr.getControlledMonsters().size();
                            newController = chr;
                        }
                    }
                }
            }
            if (newController != null) {
                if (monster.isFirstAttack()) {
                    newController.controlMonster(monster, true);
                    monster.setControllerHasAggro(true);
                    monster.setControllerKnowsAboutAggro(true);
                } else {
                    newController.controlMonster(monster, false);
                }
            }
        }
    }

    public Collection<MapleMapObject> getMapObjects() {
        return Collections.unmodifiableCollection(mapobjects.values());
    }

    public boolean containsNPC(int npcid) {
        synchronized (mapobjects) {
            for (MapleMapObject obj : mapobjects.values()) {
                if (obj.getType() == MapleMapObjectType.NPC) {
                    if (((MapleNPC) obj).getId() == npcid) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public MapleMapObject getMapObject(int oid) {
        return mapobjects.get(oid);
    }

    public MapleMonster getMonsterByOid(int oid) {
        MapleMapObject mmo = getMapObject(oid);
        if (mmo == null) {
            return null;
        }
        if (mmo.getType() == MapleMapObjectType.MONSTER) {
            return (MapleMonster) mmo;
        }
        return null;
    }

    public MapleReactor getReactorByOid(int oid) {
        MapleMapObject mmo = getMapObject(oid);
        if (mmo == null) {
            return null;
        }
        if (mmo.getType() == MapleMapObjectType.REACTOR) {
            return (MapleReactor) mmo;
        }
        return null;
    }

    public MapleReactor getReactorByName(String name) {
        synchronized (mapobjects) {
            for (MapleMapObject obj : mapobjects.values()) {
                if (obj.getType() == MapleMapObjectType.REACTOR) {
                    if (((MapleReactor) obj).getName().equals(name)) {
                        return (MapleReactor) obj;
                    }
                }
            }
        }
        return null;
    }

    public void spawnMonsterOnGroudBelow(MapleMonster mob, Point pos) {
        spawnMonsterOnGroundBelow(mob, pos);
    }
    
    public void spawnMonsterOnGroundBelow(MapleMonster mob, int x, int y) {
        spawnMonsterOnGroundBelow(mob, new Point(x, y));
    }   

    public void spawnMonsterOnGroundBelow(MapleMonster mob, Point pos) {
        Point spos = getGroundBelow(pos);
        mob.setPosition(spos);
        spawnMonster(mob);
    }

    public void spawnFakeMonsterOnGroundBelow(MapleMonster mob, Point pos) {
        Point spos = getGroundBelow(pos);
        mob.setPosition(spos);
        spawnFakeMonster(mob);
    }

    public Point getGroundBelow(Point pos) {
        Point spos = new Point(pos.x, pos.y - 1);
        spos = calcPointBelow(spos);
        spos.y -= 1;
        return spos;
    }

    public void spawnRevives(final MapleMonster monster) {
        monster.setMap(this);
        synchronized (this.mapobjects) {
            spawnAndAddRangedMapObject(monster, new DelayedPacketCreation() {
                public void sendPackets(MapleClient c) {
                    c.getSession().write(MaplePacketCreator.spawnMonster(monster, false));
                }
            }, null);
            updateMonsterController(monster);
        }
        spawnedMonstersOnMap.incrementAndGet();
    }

    public void spawnMonster(final MapleMonster monster) {
        monster.setMap(this);
        synchronized (this.mapobjects) {
            spawnAndAddRangedMapObject(monster, new DelayedPacketCreation() {
                public void sendPackets(MapleClient c) {
                    c.getSession().write(MaplePacketCreator.spawnMonster(monster, true));
                    if (monster.getId() == 9300166) {
                        TimerManager.getInstance().schedule(new Runnable() {

                            @Override
                            public void run() {
                                killMonster(monster, (MapleCharacter) getAllPlayer().get(0), false, false, 3);
                            }
                        }, new Random().nextInt(4500 + 500));
                    }
                }
            }, null);
            updateMonsterController(monster);
        }
        spawnedMonstersOnMap.incrementAndGet();
    }

    public void spawnMonsterWithEffect(final MapleMonster monster, final int effect, Point pos) {
        try {
            monster.setMap(this);
            Point spos = new Point(pos.x, pos.y - 1);
            spos = calcPointBelow(spos);
            spos.y -= 1;
            monster.setPosition(spos);
            monster.disableDrops();
            synchronized (this.mapobjects) {
                spawnAndAddRangedMapObject(monster, new DelayedPacketCreation() {
                    public void sendPackets(MapleClient c) {
                        c.getSession().write(MaplePacketCreator.spawnMonster(monster, true, effect));
                    }
                }, null);
                updateMonsterController(monster);
            }
            spawnedMonstersOnMap.incrementAndGet();
        } catch (Exception e) {
        }
    }

    public void spawnFakeMonster(final MapleMonster monster) {
        monster.setMap(this);
        monster.setFake(true);
        synchronized (this.mapobjects) {
            spawnAndAddRangedMapObject(monster, new DelayedPacketCreation() {

                public void sendPackets(MapleClient c) {
                    c.getSession().write(MaplePacketCreator.spawnFakeMonster(monster, 0));
                }
            }, null);
        }
        spawnedMonstersOnMap.incrementAndGet();
    }

    public void makeMonsterReal(final MapleMonster monster) {
        monster.setFake(false);
        broadcastMessage(MaplePacketCreator.makeMonsterReal(monster));
    }

    public void spawnReactor(final MapleReactor reactor) {
        reactor.setMap(this);
        synchronized (this.mapobjects) {
            spawnAndAddRangedMapObject(reactor, new DelayedPacketCreation() {

                public void sendPackets(MapleClient c) {
                    c.getSession().write(reactor.makeSpawnData());
                }
            }, null);
        }
    }

    private void respawnReactor(final MapleReactor reactor) {
        reactor.setState((byte) 0);
        reactor.setAlive(true);
        spawnReactor(reactor);
    }

    public void spawnDoor(final MapleDoor door) {
        synchronized (this.mapobjects) {
            spawnAndAddRangedMapObject(door, new DelayedPacketCreation() {
                public void sendPackets(MapleClient c) {
                    c.getSession().write(MaplePacketCreator.spawnDoor(door.getOwner().getId(), door.getTargetPosition(), false));
                    if (door.getOwner().getParty() != null && (door.getOwner() == c.getPlayer() || door.getOwner().getParty().containsMembers(new MaplePartyCharacter(c.getPlayer())))) {
                        c.getSession().write(MaplePacketCreator.partyPortal(door.getTown().getId(), door.getTarget().getId(), door.getTargetPosition()));
                    }
                    c.getSession().write(MaplePacketCreator.spawnPortal(door.getTown().getId(), door.getTarget().getId(), door.getTargetPosition()));
                    c.getSession().write(MaplePacketCreator.enableActions());
                }
            }, new SpawnCondition() {
                public boolean canSpawn(MapleCharacter chr) {
                    return chr.getMapId() == door.getTarget().getId() ||
                            chr == door.getOwner() && chr.getParty() == null;
                }
            });
        }
    }

    public void spawnSummon(final MapleSummon summon) {
        spawnAndAddRangedMapObject(summon, new DelayedPacketCreation() {
            public void sendPackets(MapleClient c) {
                int skillLevel = summon.getOwner().getSkillLevel(SkillFactory.getSkill(summon.getSkill()));
                c.getSession().write(MaplePacketCreator.spawnSpecialMapObject(summon, skillLevel, true));
            }
        }, null);
    }

    public void spawnMist(final MapleMist mist, final int duration, boolean poison, boolean fake) {
        addMapObject(mist);
        broadcastMessage(fake ? mist.makeFakeSpawnData(30) : mist.makeSpawnData());
        TimerManager tMan = TimerManager.getInstance();
        final ScheduledFuture<?> poisonSchedule;
        if (poison) {
            Runnable poisonTask = new Runnable() {
                @Override
                public void run() {
                    List<MapleMapObject> affectedMonsters = getMapObjectsInRect(mist.getBox(), Collections.singletonList(MapleMapObjectType.MONSTER));
                    for (MapleMapObject mo : affectedMonsters) {
                        if (mist.makeChanceResult()) {
                            MonsterStatusEffect poisonEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), mist.getSourceSkill(), false);
                            ((MapleMonster) mo).applyStatus(mist.getOwner(), poisonEffect, true, duration);
                        }
                    }
                }
            };
            poisonSchedule = tMan.register(poisonTask, 2000, 2500);
        } else {
            poisonSchedule = null;
        }
        tMan.schedule(new Runnable() {
            @Override
            public void run() {
                removeMapObject(mist);
                if (poisonSchedule != null) {
                    poisonSchedule.cancel(false);
                }
                broadcastMessage(mist.makeDestroyData());
            }
        }, duration);
    }

    public void disappearingItemDrop(final MapleMapObject dropper, final MapleCharacter owner, final IItem item, Point pos) {
        final Point droppos = calcDropPos(pos, pos);
        final MapleMapItem drop = new MapleMapItem(item, droppos, dropper, owner);
        broadcastMessage(MaplePacketCreator.dropItemFromMapObject(item.getItemId(), drop.getObjectId(), 0, 0, dropper.getPosition(), droppos, (byte) 3), drop.getPosition());
    }

    public void spawnItemDrop(final MapleMapObject dropper, final MapleCharacter owner, final IItem item, Point pos, final boolean ffaDrop, final boolean expire) {
        TimerManager tMan = TimerManager.getInstance();
        final Point droppos = calcDropPos(pos, pos);
        final MapleMapItem drop = new MapleMapItem(item, droppos, dropper, owner);
        spawnAndAddRangedMapObject(drop, new DelayedPacketCreation() {
            public void sendPackets(MapleClient c) {
                c.getSession().write(MaplePacketCreator.dropItemFromMapObject(item.getItemId(), drop.getObjectId(), 0, ffaDrop ? 0 : owner.getId(),
                        dropper.getPosition(), droppos, (byte) 1));
            }
        }, null);
        broadcastMessage(MaplePacketCreator.dropItemFromMapObject(item.getItemId(), drop.getObjectId(), 0, ffaDrop ? 0
                : owner.getId(), dropper.getPosition(), droppos, (byte) 0), drop.getPosition());
        if (expire) {
            tMan.schedule(new ExpireMapItemJob(drop), dropLife);
        }
        activateItemReactors(drop);
    }

    public List<MapleMapObject> getAllMonster() { //added by ryan the server breaker
        return getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapleMapObjectType.MONSTER));
    }
    
    private class TimerDestroyWorker implements Runnable {

        @Override
        public void run() {
            if (mapTimer != null) {
                int warpMap = mapTimer.warpToMap();
                int minWarp = mapTimer.minLevelToWarp();
                int maxWarp = mapTimer.maxLevelToWarp();
                mapTimer = null;
                if (warpMap != -1) {
                    MapleMap map2wa2 = ChannelServer.getInstance(channel).getMapFactory().getMap(warpMap);
                    String warpmsg = "You will now be warped to " + map2wa2.getStreetName() + " : " + map2wa2.getMapName();
                    broadcastMessage(MaplePacketCreator.serverNotice(6, warpmsg));
                    for (MapleCharacter chr : getCharacters()) {
                        try {
                            if (chr.getLevel() >= minWarp && chr.getLevel() <= maxWarp) {
                                chr.changeMap(map2wa2, map2wa2.getPortal(0));
                            } else {
                                chr.getClient().getSession().write(MaplePacketCreator.serverNotice(5, "You are not at least level " + minWarp + " or you are higher than level " + maxWarp + "."));
                            }
                        } catch (Exception ex) {
                            String errormsg = "There was a problem warping you. Please contact a GM";
                            chr.getClient().getSession().write(MaplePacketCreator.serverNotice(5, errormsg));
                        }
                    }
                }
            }
        }
    }

    public void addMapTimer(int duration) {
        ScheduledFuture<?> sf0f = TimerManager.getInstance().schedule(new TimerDestroyWorker(), duration * 1000);
        mapTimer = new MapleMapTimer(sf0f, duration, -1, -1, -1);
        broadcastMessage(mapTimer.makeSpawnData());
    }

    public void addMapTimer(int duration, int mapToWarpTo) {
        ScheduledFuture<?> sf0f = TimerManager.getInstance().schedule(new TimerDestroyWorker(), duration * 1000);
        mapTimer = new MapleMapTimer(sf0f, duration, mapToWarpTo, 0, 256);
        broadcastMessage(mapTimer.makeSpawnData());
    }

    public void addMapTimer(int duration, int mapToWarpTo, int minLevelToWarp) {
        ScheduledFuture<?> sf0f = TimerManager.getInstance().schedule(new TimerDestroyWorker(), duration * 1000);
        mapTimer = new MapleMapTimer(sf0f, duration, mapToWarpTo, minLevelToWarp, 256);
        broadcastMessage(mapTimer.makeSpawnData());
    }

    public void addMapTimer(int duration, int mapToWarpTo, int minLevelToWarp, int maxLevelToWarp) {
        ScheduledFuture<?> sf0f = TimerManager.getInstance().schedule(new TimerDestroyWorker(), duration * 1000);
        mapTimer = new MapleMapTimer(sf0f, duration, mapToWarpTo, minLevelToWarp, maxLevelToWarp);
        broadcastMessage(mapTimer.makeSpawnData());
    }

    public void clearMapTimer() {
        if (mapTimer != null) {
            mapTimer.getSF0F().cancel(true);
        }
        mapTimer = null;
    }

    private void activateItemReactors(MapleMapItem drop) {
        IItem item = drop.getItem();
        final TimerManager tMan = TimerManager.getInstance();
        for (MapleMapObject o : mapobjects.values()) {
            if (o.getType() == MapleMapObjectType.REACTOR) {
                if (((MapleReactor) o).getReactorType() == 100) {
                    if (((MapleReactor) o).getReactItem().getLeft() == item.getItemId() && ((MapleReactor) o).getReactItem().getRight() <= item.getQuantity()) {
                        Rectangle area = ((MapleReactor) o).getArea();

                        if (area.contains(drop.getPosition())) {
                            MapleClient ownerClient = null;
                            if (drop.getOwner() != null) {
                                ownerClient = drop.getOwner().getClient();
                            }
                            MapleReactor reactor = (MapleReactor) o;
                            if (!reactor.isTimerActive()) {
                                tMan.schedule(new ActivateItemReactor(drop, reactor, ownerClient), 5000);
                                reactor.setTimerActive(true);
                            }
                        }
                    }
                }
            }
        }
    }

    public void AriantPQStart() {
        int i = 1;
        for (MapleCharacter chars2 : this.getCharacters()) {
            broadcastMessage(MaplePacketCreator.updateAriantPQRanking(chars2.getName(), 0, false));
            broadcastMessage(MaplePacketCreator.serverNotice(0, MaplePacketCreator.updateAriantPQRanking(chars2.getName(), 0, false).toString()));
            if (this.getCharacters().size() > i) {
                broadcastMessage(MaplePacketCreator.updateAriantPQRanking(null, 0, true));
                broadcastMessage(MaplePacketCreator.serverNotice(0, MaplePacketCreator.updateAriantPQRanking(chars2.getName(), 0, true).toString()));
            }
            i++;
        }
    }

    public void spawnMesoDrop(final int meso, final int displayMeso, Point position, final MapleMapObject dropper, final MapleCharacter owner, final boolean ffaLoot) {
        TimerManager tMan = TimerManager.getInstance();
        final Point droppos = calcDropPos(position, position);
        final MapleMapItem mdrop = new MapleMapItem(meso, displayMeso, droppos, dropper, owner);
        spawnAndAddRangedMapObject(mdrop, new DelayedPacketCreation() {
            public void sendPackets(MapleClient c) {
                c.getSession().write(MaplePacketCreator.dropMesoFromMapObject(displayMeso, mdrop.getObjectId(), dropper.getObjectId(),
                        ffaLoot ? 0 : owner.getId(), dropper.getPosition(), droppos, (byte) 1));
            }
        }, null);
        tMan.schedule(new ExpireMapItemJob(mdrop), dropLife);
    }
    public void displayClock(final MapleCharacter chr, int time) {
        broadcastMessage(MaplePacketCreator.getClock(time));
        //chr.getClient().getSession().write(MaplePacketCreator.getClock(time));
        //chr.getMap().broadcastMessage(MaplePacketCreator.getClock(time));
        TimerManager.getInstance().schedule(new Runnable() {
            @Override
            public void run() {
                broadcastMessage(MaplePacketCreator.destroyClock());
                //chr.getMap().broadcastMessage(MaplePacketCreator.destroyClock());
                //chr.getClient().getSession().write(MaplePacketCreator.destroyClock());
            }
        }, time * 1000);
    } 
    public void destroyClock(MapleCharacter chr) {
        chr.getMap().broadcastMessage(MaplePacketCreator.destroyClock());
    }
    public void startMapEffect(String msg, int itemId) {
        if (mapEffect != null) {
            return;
        }
        mapEffect = new MapleMapEffect(msg, itemId);
        broadcastMessage(mapEffect.makeStartData());
        TimerManager tMan = TimerManager.getInstance();
        tMan.schedule(new Runnable() {
            @Override
            public void run() {
                broadcastMessage(mapEffect.makeDestroyData());
                mapEffect = null;
            }
        }, 30000);
    }

    public void addPlayer(MapleCharacter chr) {
        synchronized (sortedChrsByTime) {
            if(sortedChrsByTime.isEmpty()) {
                mapowner = new Pair(chr, System.currentTimeMillis());
            }
            this.sortedChrsByTime.put(chr, System.currentTimeMillis());
        }
        synchronized (characters) {
            this.characters.add(chr);
        }
        synchronized (this.mapobjects) {
            if (!chr.isHidden()) {
                MaplePet[] pets = chr.getPets();
                for (int i = 0; i < chr.getPets().length; i++) {
                    if (pets[i] != null) {
                        try {
                        pets[i].setPos(getGroundBelow(chr.getPosition()));
                        broadcastMessage(chr, MaplePacketCreator.showPet(chr, pets[i], false, false), false);
                        } catch (NullPointerException e) {
                            FilePrinter.printError(FilePrinter.EXCEPTION_CAUGHT + chr.getName() + ".txt", chr.getName() + " exceptioned in addPlayer of MapleMap. \n");
                        }
                    } else {
                        break;
                    }
                }
                broadcastMessage(chr, (MaplePacketCreator.spawnPlayerMapobject(chr)), false);
                if (chr.getChalkboard() != null) {
                    broadcastMessage(chr, (MaplePacketCreator.useChalkboard(chr, false)), false);
                }
            } else {
                MaplePet[] pets = chr.getPets();
                for (int i = 0; i < chr.getPets().length; i++) {
                    if (pets[i] != null) {
                        pets[i].setPos(getGroundBelow(chr.getPosition()));
                        broadcastGMMessage(chr, MaplePacketCreator.showPet(chr, pets[i], false, false), false);
                    } else {
                        break;
                    }
                }
                broadcastGMMessage(chr, (MaplePacketCreator.spawnPlayerMapobject(chr)), false);
                if (chr.getChalkboard() != null) {
                    broadcastGMMessage(chr, (MaplePacketCreator.useChalkboard(chr, false)), false);
                }
            }
            sendObjectPlacement(chr.getClient());
            switch (getId()) {
                case 1:
                case 2:
                case 809000101:
                case 809000201:
                    chr.getClient().getSession().write(MaplePacketCreator.showEquipEffect());
            }
            MaplePet[] pets = chr.getPets(); // test
            for (int i = 0; i < pets.length; i++) {
                if (pets[i] != null) {
                     try {
                    pets[i].setPos(getGroundBelow(chr.getPosition()));
                    chr.getClient().getSession().write(MaplePacketCreator.showPet(chr, pets[i], false, false));
                    } catch (NullPointerException e) {
                        FilePrinter.printError(FilePrinter.EXCEPTION_CAUGHT + chr.getName() + ".txt", chr.getName() + " exceptioned in addPlayer of MapleMap. \n");
                    }
                }
            }
            if (chr.getChalkboard() != null) {
                chr.getClient().getSession().write((MaplePacketCreator.useChalkboard(chr, false)));
            }
            this.mapobjects.put(Integer.valueOf(chr.getObjectId()), chr);
        }
        MapleStatEffect summonStat = chr.getStatForBuff(MapleBuffStat.SUMMON);
        if (summonStat != null) {
            MapleSummon summon = chr.getSummons().get(summonStat.getSourceId());
            summon.setPosition(getGroundBelow(chr.getPosition()));
            chr.getMap().spawnSummon(summon);
            updateMapObjectVisibility(chr, summon);
        }
        if (mapEffect != null) {
            mapEffect.sendStartData(chr.getClient());
        }
        if (MapleTVEffect.active) {
            if (hasMapleTV() && MapleTVEffect.packet != null) {
                chr.getClient().getSession().write(MapleTVEffect.packet);
            }
        }
        if (getTimeLimit() > 0 && getForcedReturnMap() != null) {
            chr.getClient().getSession().write(MaplePacketCreator.getClock(getTimeLimit()));
            chr.startMapTimeLimitTask(this, this.getForcedReturnMap());
        }
        if (chr.getEventInstance() != null && chr.getEventInstance().isTimerStarted()) {
            chr.getClient().getSession().write(MaplePacketCreator.getClock((int) (chr.getEventInstance().getTimeLeft() / 1000)));
        }
        if (hasClock()) {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            int min = cal.get(Calendar.MINUTE);
            int second = cal.get(Calendar.SECOND);
            chr.getClient().getSession().write((MaplePacketCreator.getClockTime(hour, min, second)));
        }  
        if (hasBoat() == 2) {
            chr.getClient().getSession().write((MaplePacketCreator.boatPacket(true)));
        } else if (hasBoat() == 1 && (chr.getMapId() != 200090000 || chr.getMapId() != 200090010)) {
            chr.getClient().getSession().write(MaplePacketCreator.boatPacket(false));
        }
        chr.receivePartyMemberHP();
    }

    public void removePlayer(MapleCharacter chr) {
        synchronized (sortedChrsByTime) {
            sortedChrsByTime.remove(chr);
            if(mapowner != null) {
                if(mapowner.getLeft().getId() == chr.getId())
                    mapowner = null;
            }
            LinkedHashMap<MapleCharacter, Long> ref = sortedChrsByTime;
            if(ref.keySet().iterator().hasNext()) {
                MapleCharacter newOwner = ref.keySet().iterator().next();
                mapowner = new Pair(newOwner, System.currentTimeMillis());
            }
        }
        synchronized (characters) {
            characters.remove(chr);
        }
        removeMapObject(Integer.valueOf(chr.getObjectId()));
        broadcastMessage(MaplePacketCreator.removePlayerFromMap(chr.getId()));
        for (MapleMonster monster : chr.getControlledMonsters()) {
            monster.setController(null);
            monster.setControllerHasAggro(false);
            monster.setControllerKnowsAboutAggro(false);
            updateMonsterController(monster);
        }
        chr.leaveMap();
        chr.cancelMapTimeLimitTask();
        for (MapleSummon summon : chr.getSummons().values()) {
            if (summon.isPuppet()) {
                chr.cancelBuffStats(MapleBuffStat.PUPPET);
            } else {
                removeMapObject(summon);
            }
        }
    }

    public void broadcastMessage(MaplePacket packet) {
        broadcastMessage(null, packet, Double.POSITIVE_INFINITY, null);
    }

    public void broadcastMessage(MapleCharacter source, MaplePacket packet, boolean repeatToSource) {
        broadcastMessage(repeatToSource ? null : source, packet, Double.POSITIVE_INFINITY, source.getPosition());
    }

    public void broadcastMessage(MapleCharacter source, MaplePacket packet, boolean repeatToSource, boolean ranged) {
        broadcastMessage(repeatToSource ? null : source, packet, ranged ? MapleCharacter.MAX_VIEW_RANGE_SQ : Double.POSITIVE_INFINITY, source.getPosition());
    }

    public void broadcastMessage(MaplePacket packet, Point rangedFrom) {
        broadcastMessage(null, packet, MapleCharacter.MAX_VIEW_RANGE_SQ, rangedFrom);
    }

    public void broadcastMessage(MapleCharacter source, MaplePacket packet, Point rangedFrom) {
        broadcastMessage(source, packet, MapleCharacter.MAX_VIEW_RANGE_SQ, rangedFrom);
    }

    private void broadcastMessage(MapleCharacter source, MaplePacket packet, double rangeSq, Point rangedFrom) {
        synchronized (characters) {
            for (MapleCharacter chr : characters) {
                if (chr != source && !chr.isFake()) {
                    if (rangeSq < Double.POSITIVE_INFINITY) {
                        if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                            chr.getClient().getSession().write(packet);
                        }
                    } else {
                        chr.getClient().getSession().write(packet);
                    }
                }
            }
        }
    }

    public void broadcastGMMessage(MaplePacket packet) {
        broadcastGMMessage(null, packet, Double.POSITIVE_INFINITY, null);
    }

    public void broadcastGMMessage(MapleCharacter source, MaplePacket packet, boolean repeatToSource) {
        broadcastGMMessage(repeatToSource ? null : source, packet, Double.POSITIVE_INFINITY, source.getPosition());
    }

    private void broadcastGMMessage(MapleCharacter source, MaplePacket packet, double rangeSq, Point rangedFrom) {
        synchronized (characters) {
            for (MapleCharacter chr : characters) {
                if (chr != source && !chr.isFake() && chr.isGM()) {
                    if (rangeSq < Double.POSITIVE_INFINITY) {
                        if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                            chr.getClient().getSession().write(packet);
                        }
                    } else {
                        chr.getClient().getSession().write(packet);
                    }
                }
            }
        }
    }

    public void broadcastNONGMMessage(MaplePacket packet) {
        broadcastNONGMMessage(null, packet, Double.POSITIVE_INFINITY, null);
    }

    public void broadcastNONGMMessage(MapleCharacter source, MaplePacket packet, boolean repeatToSource) {
        broadcastNONGMMessage(repeatToSource ? null : source, packet, Double.POSITIVE_INFINITY, source.getPosition());
    }

    private void broadcastNONGMMessage(MapleCharacter source, MaplePacket packet, double rangeSq, Point rangedFrom) {
        synchronized (characters) {
            for (MapleCharacter chr : characters) {
                if (chr != source && !chr.isFake() && !chr.isGM()) {
                    if (rangeSq < Double.POSITIVE_INFINITY) {
                        if (rangedFrom.distanceSq(chr.getPosition()) <= rangeSq) {
                            chr.getClient().getSession().write(packet);
                        }
                    } else {
                        chr.getClient().getSession().write(packet);
                    }
                }
            }
        }
    }

    private boolean isNonRangedType(MapleMapObjectType type) {
        switch (type) {
            case NPC:
            case PLAYER:
            case HIRED_MERCHANT:
            case MIST:
            case PLAYER_NPC:
                return true;
        }
        return false;
    }

    private void sendObjectPlacement(MapleClient mapleClient) {
        for (MapleMapObject o : mapobjects.values()) {
            if (isNonRangedType(o.getType())) {
                o.sendSpawnData(mapleClient);
            } else if (o.getType() == MapleMapObjectType.MONSTER) {
                updateMonsterController((MapleMonster) o);
            }
        }
        MapleCharacter chr = mapleClient.getPlayer();

        if (chr != null) {
            for (MapleMapObject o : getMapObjectsInRange(chr.getPosition(), MapleCharacter.MAX_VIEW_RANGE_SQ, rangedMapobjectTypes)) {
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    if (((MapleReactor) o).isAlive()) {
                        o.sendSpawnData(chr.getClient());
                        chr.addVisibleMapObject(o);
                    }
                } else {
                    o.sendSpawnData(chr.getClient());
                    chr.addVisibleMapObject(o);
                }
            }
        } else {
            log.info("sendObjectPlacement invoked with null char");
        }
    }

    public List<MapleMapObject> getMapObjectsInRange(Point from, double rangeSq, List<MapleMapObjectType> types) {
        List<MapleMapObject> ret = new LinkedList<MapleMapObject>();
        synchronized (mapobjects) {
            for (MapleMapObject l : mapobjects.values()) {
                if (types.contains(l.getType())) {
                    if (from.distanceSq(l.getPosition()) <= rangeSq) {
                        ret.add(l);
                    }
                }
            }
        }
        return ret;
    }

    public List<MapleMapObject> getItemsInRange(Point from, double rangeSq) {
        List<MapleMapObject> ret = new LinkedList<MapleMapObject>();
        synchronized (mapobjects) {
            for (MapleMapObject l : mapobjects.values()) {
                if (l.getType() == MapleMapObjectType.ITEM) {
                    if (from.distanceSq(l.getPosition()) <= rangeSq) {
                        ret.add(l);
                    }
                }
            }
        }
        return ret;
    }

    public List<MapleMapObject> getMapObjectsInRect(Rectangle box, List<MapleMapObjectType> types) {
        List<MapleMapObject> ret = new LinkedList<MapleMapObject>();
        synchronized (mapobjects) {
            for (MapleMapObject l : mapobjects.values()) {
                if (types.contains(l.getType())) {
                    if (box.contains(l.getPosition())) {
                        ret.add(l);
                    }
                }
            }
        }
        return ret;
    }

    public List<MapleCharacter> getPlayersInRect(Rectangle box, List<MapleCharacter> chr) {
        List<MapleCharacter> character = new LinkedList<MapleCharacter>();
        synchronized (characters) {
            for (MapleCharacter a : characters) {
                if (chr.contains(a.getClient().getPlayer())) {
                    if (box.contains(a.getPosition())) {
                        character.add(a);
                    }
                }
            }
        }
        return character;
    }

    public void addPortal(MaplePortal myPortal) {
        portals.put(myPortal.getId(), myPortal);
    }

    public MaplePortal getPortal(String portalname) {
        for (MaplePortal port : portals.values()) {
            if (port.getName().equals(portalname)) {
                return port;
            }
        }
        return null;
    }

    public MaplePortal getPortal(int portalid) {
        return portals.get(portalid);
    }

    public void addMapleArea(Rectangle rec) {
        areas.add(rec);
    }

    public List<Rectangle> getAreas() {
        return new ArrayList<Rectangle>(areas);
    }

    public Rectangle getArea(int index) {
        return areas.get(index);
    }

    public void setFootholds(MapleFootholdTree footholds) {
        this.footholds = footholds;
    }

    public MapleFootholdTree getFootholds() {
        return footholds;
    }

    public void addMonsterSpawn(MapleMonster monster, int mobTime) {
        Point newpos = calcPointBelow(monster.getPosition());
        newpos.y -= 1;
        SpawnPoint sp = new SpawnPoint(monster, newpos, mobTime);
        monsterSpawn.add(sp);
        if (sp.shouldSpawn() || mobTime == -1) {
            sp.spawnMonster(this);
        }
    }

    public float getMonsterRate() {
        return monsterRate;
    }

    public Collection<MapleCharacter> getCharacters() {
        return Collections.unmodifiableCollection(this.characters);
    }

    public MapleCharacter getCharacterById(int id) {
        for (MapleCharacter c : this.characters) {
            if (c.getId() == id) {
                return c;
            }
        }
        return null;
    }

    private void updateMapObjectVisibility(MapleCharacter chr, MapleMapObject mo) {
        if (chr.isFake()) {
            return;
        }
        if (!chr.isMapObjectVisible(mo)) {
            if (mo.getType() == MapleMapObjectType.SUMMON || mo.getPosition().distanceSq(chr.getPosition()) <= MapleCharacter.MAX_VIEW_RANGE_SQ) {
                chr.addVisibleMapObject(mo);
                mo.sendSpawnData(chr.getClient());
            }
        } else {
            if (mo.getType() != MapleMapObjectType.SUMMON && mo.getPosition().distanceSq(chr.getPosition()) > MapleCharacter.MAX_VIEW_RANGE_SQ) {
                chr.removeVisibleMapObject(mo);
                mo.sendDestroyData(chr.getClient());
            }
        }
    }

    public void moveMonster(MapleMonster monster, Point reportedPos) {
        monster.setPosition(reportedPos);
        synchronized (characters) {
            for (MapleCharacter chr : characters) {
                updateMapObjectVisibility(chr, monster);
            }
        }
    }

    public void movePlayer(MapleCharacter player, Point newPosition) {
        if (player.isFake()) {
            return;
        }
        player.setPosition(newPosition);
        Collection<MapleMapObject> visibleObjects = player.getVisibleMapObjects();
        MapleMapObject[] visibleObjectsNow = visibleObjects.toArray(new MapleMapObject[visibleObjects.size()]);
        for (MapleMapObject mo : visibleObjectsNow) {
            if (mapobjects.get(mo.getObjectId()) == mo) {
                updateMapObjectVisibility(player, mo);
            } else {
                player.removeVisibleMapObject(mo);
            }
        }
        for (MapleMapObject mo : getMapObjectsInRange(player.getPosition(), MapleCharacter.MAX_VIEW_RANGE_SQ,
                rangedMapobjectTypes)) {
            if (!player.isMapObjectVisible(mo)) {
                mo.sendSpawnData(player.getClient());
                player.addVisibleMapObject(mo);
            }
        }
    }

    public MaplePortal findClosestSpawnpoint(Point from) {
        MaplePortal closest = null;
        double shortestDistance = Double.POSITIVE_INFINITY;
        for (MaplePortal portal : portals.values()) {
            double distance = portal.getPosition().distanceSq(from);
            if (portal.getType() >= 0 && portal.getType() <= 2 && distance < shortestDistance && portal.getTargetMapId() == 999999999) {
                closest = portal;
                shortestDistance = distance;
            }
        }
        return closest;
    }

    public void spawnDebug(MessageCallback mc) {
        mc.dropMessage("Spawndebug...");
        synchronized (mapobjects) {
            mc.dropMessage("Mapobjects in map: " + mapobjects.size() + " \"spawnedMonstersOnMap\": " +
                    spawnedMonstersOnMap + " spawnpoints: " + monsterSpawn.size() +
                    " maxRegularSpawn: " + getMaxRegularSpawn());
            int numMonsters = 0;
            for (MapleMapObject mo : mapobjects.values()) {
                if (mo instanceof MapleMonster) {
                    numMonsters++;
                }
            }
            mc.dropMessage("actual monsters: " + numMonsters);
        }
    }

    private int getMaxRegularSpawn() {
        return (int) (monsterSpawn.size() / monsterRate);
    }

    public Collection<MaplePortal> getPortals() {
        return Collections.unmodifiableCollection(portals.values());
    }

    public String getMapName() {
        return mapName;
    }

    public void setMapName(String mapName) {
        this.mapName = mapName;
    }

    public String getStreetName() {
        return streetName;
    }

    public void setClock(boolean hasClock) {
        this.clock = hasClock;
    }

    public boolean hasClock() {
        return clock;
    }

    public void setTown(boolean isTown) {
        this.town = isTown;
    }

    public boolean isTown() {
        return town;
    }

    public void setStreetName(String streetName) {
        this.streetName = streetName;
    }

    public void setEverlast(boolean everlast) {
        this.everlast = everlast;
    }

    public boolean getEverlast() {
        return everlast;
    }

    public int getSpawnedMonstersOnMap() {
        return spawnedMonstersOnMap.get();
    }

    public Collection<MapleCharacter> getNearestPvpChar(Point attacker, double maxRange, double maxHeight, Collection<MapleCharacter> chr) {
        Collection<MapleCharacter> character = new LinkedList<MapleCharacter>();
        for (MapleCharacter a : characters) {
            if (chr.contains(a.getClient().getPlayer())) {
                Point attackedPlayer = a.getPosition();
                MaplePortal Port = a.getMap().findClosestSpawnpoint(a.getPosition());
                Point nearestPort = Port.getPosition();
                double safeDis = attackedPlayer.distance(nearestPort);
                double distanceX = attacker.distance(attackedPlayer.getX(), attackedPlayer.getY());
                if (PvPLibrary.isLeft) {
                    if (attacker.x > attackedPlayer.x && distanceX < maxRange && distanceX > 2 &&
                            attackedPlayer.y >= attacker.y - maxHeight && attackedPlayer.y <= attacker.y + maxHeight && safeDis > 2) {
                        character.add(a);
                    }
                }
                if (PvPLibrary.isRight) {
                    if (attacker.x < attackedPlayer.x && distanceX < maxRange && distanceX > 2 &&
                            attackedPlayer.y >= attacker.y - maxHeight && attackedPlayer.y <= attacker.y + maxHeight && safeDis > 2) {
                        character.add(a);
                    }
                }
            }
        }
        return character;
    }

    private class ExpireMapItemJob implements Runnable {
        private MapleMapItem mapitem;
        public ExpireMapItemJob(MapleMapItem mapitem) {
            this.mapitem = mapitem;
        }
        @Override
        public void run() {
            if (mapitem != null && mapitem == getMapObject(mapitem.getObjectId())) {
                synchronized (mapitem) {
                    if (mapitem.isPickedUp()) {
                        return;
                    }
                    MapleMap.this.broadcastMessage(MaplePacketCreator.removeItemFromMap(mapitem.getObjectId(), 0, 0), mapitem.getPosition());
                    MapleMap.this.removeMapObject(mapitem);
                    mapitem.setPickedUp(true);
                }
            }
        }
    }

    private class ActivateItemReactor implements Runnable {
        private MapleMapItem mapitem;
        private MapleReactor reactor;
        private MapleClient c;
        public ActivateItemReactor(MapleMapItem mapitem, MapleReactor reactor, MapleClient c) {
            this.mapitem = mapitem;
            this.reactor = reactor;
            this.c = c;
        }
        @Override
        public void run() {
            if (mapitem != null && mapitem == getMapObject(mapitem.getObjectId())) {
                synchronized (mapitem) {
                    TimerManager tMan = TimerManager.getInstance();
                    if (mapitem.isPickedUp()) {
                        return;
                    }
                    MapleMap.this.broadcastMessage(MaplePacketCreator.removeItemFromMap(mapitem.getObjectId(), 0, 0), mapitem.getPosition());
                    MapleMap.this.removeMapObject(mapitem);
                    reactor.hitReactor(c);
                    reactor.setTimerActive(false);
                    if (reactor.getDelay() > 0) {
                        tMan.schedule(new Runnable() {

                            @Override
                            public void run() {
                                reactor.setState((byte) 0);
                                broadcastMessage(MaplePacketCreator.triggerReactor(reactor, 0));
                            }
                        }, reactor.getDelay());
                    }
                }
            }
        }
    }

    private class RespawnWorker implements Runnable {
        @Override
        public void run() {
            int playersOnMap = characters.size();
            if (playersOnMap == 0) {
                return;
            }
            int ispawnedMonstersOnMap = spawnedMonstersOnMap.get();
            double getMaxSpawn = getMaxRegularSpawn() * 1;
            if (mapid == 610020002 || mapid == 610020004) {
                getMaxSpawn *= 2;
            }
            double numShouldSpawn = getMaxSpawn - ispawnedMonstersOnMap;
            if (mapid == 610020002 || mapid == 610020004) {
                numShouldSpawn *= 2;
            }
            if (numShouldSpawn + ispawnedMonstersOnMap >= getMaxSpawn) {
                numShouldSpawn = getMaxSpawn - ispawnedMonstersOnMap;
            } 
            if (numShouldSpawn <= 0) {
                return;
            }
            List<SpawnPoint> randomSpawn = new ArrayList<SpawnPoint>(monsterSpawn);
            Collections.shuffle(randomSpawn);
            int spawned = 0;
            for (SpawnPoint spawnPoint : randomSpawn) {
                if (spawnPoint.shouldSpawn()) {
                    spawnPoint.spawnMonster(MapleMap.this);
                    spawned++;
                }
                if (spawned >= numShouldSpawn) {
                    break;
                }
            }
        }
    }

    private static interface DelayedPacketCreation {
        void sendPackets(MapleClient c);
    }

    private static interface SpawnCondition {
        boolean canSpawn(MapleCharacter chr);
    }

    public int getHPDec() {
        return decHP;
    }

    public void setHPDec(int delta) {
        decHP = delta;
    }

    public int getHPDecProtect() {
        return this.protectItem;
    }

    public void setHPDecProtect(int delta) {
        this.protectItem = delta;
    }

    public int hasBoat() {
        if (boat && docked) {
            return 2;
        } else if (boat) {
            return 1;
        } else {
            return 0;
        }
    }

    public void setBoat(boolean hasBoat) {
        this.boat = hasBoat;
    }
    
     public boolean setDisableChat(boolean v)
    {
        this.disableChat = v;
        return disableChat;
    }

    public boolean getDisableChat()
    {
        return this.disableChat;
    }

    public void setDocked(boolean isDocked) {
        this.docked = isDocked;
    }
    
    public void addBotPlayer(MapleCharacter chr) {
        synchronized (characters) {
            this.characters.add(chr);
        }
        synchronized (this.mapobjects) {
            if (!chr.isHidden()) {
                broadcastMessage(chr, (MaplePacketCreator.spawnPlayerMapobject(chr)), false);
            } else {
                broadcastGMMessage(chr, (MaplePacketCreator.spawnPlayerMapobject(chr)), false);
            }
            this.mapobjects.put(Integer.valueOf(chr.getObjectId()), chr);
        }
    }

    public int playerCount() {
        List<MapleMapObject> players = getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapleMapObjectType.PLAYER));
        return players.size();
    }

    public int mobCount() {
        List<MapleMapObject> mobsCount = getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapleMapObjectType.MONSTER));
        return mobsCount.size();
    }

    public void setReactorState() {
        synchronized (this.mapobjects) {
            for (MapleMapObject o : mapobjects.values()) {
                if (o.getType() == MapleMapObjectType.REACTOR) {
                    ((MapleReactor) o).setState((byte) 1);
                    broadcastMessage(MaplePacketCreator.triggerReactor((MapleReactor) o, 1));
                }
            }
        }
    }

    public void setShowGate(boolean gate) {
        this.showGate = gate;
    }

    public boolean hasShowGate() {
        return showGate;
    }

    public boolean hasMapleTV() {
        int tvIds[] = {9250042, 9250043, 9250025, 9250045, 9250044, 9270001, 9270002, 9250023, 9250024, 9270003, 9270004, 9250026, 9270006, 9270007, 9250046, 9270000, 9201066, 9270005, 9270008, 9270009, 9270010, 9270011, 9270012, 9270013, 9270014, 9270015, 9270016, 9270040};
        for (int id : tvIds) {
            if (containsNPC(id)) {
                return true;
            }
        }
        return false;
    }

    public void removeMonster(MapleMonster mons) {
        spawnedMonstersOnMap.decrementAndGet();
        broadcastMessage(MaplePacketCreator.killMonster(mons.getObjectId(), true), mons.getPosition());
        removeMapObject(mons);
    }

    public boolean isPQMap() {
        switch (getId()) {
            case 103000800:
            case 103000804:
            case 922010100:
            case 922010200:
            case 922010201:
            case 922010300:
            case 922010400:
            case 922010401:
            case 922010402:
            case 922010403:
            case 922010404:
            case 922010405:
            case 922010500:
            case 922010600:
            case 922010700:
            case 922010800:
                return true;
            default:
                return false;
        }
    }

    public boolean isMiniDungeonMap() {
        switch (mapid) {
           // case 100020000:
          // case 105040304:
           // case 105050100:
          //  case 221023400:
          //      return true;
            default:
                return false;
        }
    }
    private final class warpAll implements Runnable {

        private MapleMap toGo;
        private MapleMap from;

        public warpAll(MapleMap toGoto, MapleMap from) {
            this.toGo = toGoto;
            this.from = from;
        }

        @Override
        public void run() {
            synchronized (toGo) {
                for (MapleCharacter ppp : characters) {
                    if (ppp.getMap().equals(from)) {
                        ppp.changeMap(toGo, toGo.getPortal(0));
                        if (ppp.getEventInstance() != null) {
                            ppp.getEventInstance().unregisterPlayer(ppp);
                        }
                    }
                }
            }
        }
    }
     public void warpAllToNearestTown(String reason) {
        this.broadcastMessage(MaplePacketCreator.serverNotice(5, reason));
        int rid = this.forcedReturnMap == 999999999 ? this.returnMapId : this.forcedReturnMap;
        new warpAll(ChannelServer.getInstance(this.channel).getMapFactory().getMap(rid), this).run();
    }

    public void dcAllPlayers() {

        int rid = this.forcedReturnMap == 999999999 ? this.returnMapId : this.forcedReturnMap;
        new warpAll(ChannelServer.getInstance(this.channel).getMapFactory().getMap(rid), this).run();
    }

    public void warpAllToCashShop(String reason) {
        MaplePacket x = MaplePacketCreator.serverNotice(1, reason);
        for (MapleCharacter mc : getCharacters()) {

            mc.warpToCashShop();
            mc.getClient().getSession().write(x);
        }

    }
    public void killAllBoogies() {
        List<MapleMapObject> monsters = getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapleMapObjectType.MONSTER));
        for (MapleMapObject monstermo : monsters) {
            MapleMonster monster = (MapleMonster) monstermo;
            if (monster.getId() == 3230300 || monster.getId() == 3230301 || monster.getName().toLowerCase().contains("boogie")) {
                spawnedMonstersOnMap.decrementAndGet();
                monster.setHp(0);
                broadcastMessage(MaplePacketCreator.killMonster(monster.getObjectId(), true), monster.getPosition());
                removeMapObject(monster);
            }
        }
        this.broadcastMessage(MaplePacketCreator.serverNotice(6, "As the rock crumbled, Jr. Boogie fell in great pain and disappeared."));
    }
    public void scheduleWarp(MapleMap toGoto, MapleMap frm, long time) {
        TimerManager tMan = TimerManager.getInstance();
        tMan.schedule(new warpAll(toGoto, frm), time);
    }
    
    public void setMonsterRate(float monsterRate) {
        this.monsterRate = monsterRate;
    }
    
    public MapleCharacter getMapOwner() {
        if(mapowner != null)
            return mapowner.getLeft();
        return null;
    }
    
    public long getMapOwnerSinceTime() {
        if(mapowner != null)
            return mapowner.getRight();
        return 0;
    }
    
    public MapleCharacter getMapOwnerCandidate() {
        LinkedHashMap<MapleCharacter, Long> ref = sortedChrsByTime;
        if(ref.keySet().toArray().length > 1)
            return (MapleCharacter)ref.keySet().toArray()[1];
        return null;
    }
    
    public void switchMapleMapOwner() {
        MapleCharacter candidate = getMapOwnerCandidate();
        MapleCharacter currMapOwner = mapowner.getLeft();
        if(candidate != null) {
            mapowner = new Pair(candidate, System.currentTimeMillis());
            synchronized (sortedChrsByTime) {
                sortedChrsByTime.remove(currMapOwner);
                sortedChrsByTime.put(currMapOwner, System.currentTimeMillis());
            }
            //broadcastMessage(MaplePacketCreator.serverNotice(5, "[Map Owner] " + currMapOwner.getName() + " was afk for too long, " + candidate.getName() + " is the new map owner."));
            candidate.resetAfkTime();
        }
    }
    
    public void HPQSpawnDelay(MapleCharacter c) {
        if(this.getId() == 910010000) {
            if(c.getEventInstance() != null) {
                final MapleMap tehMap = c.getEventInstance().getMapInstance(910010000);
                final MapleMonster mob = MapleLifeFactory.getMonster(9300083);
                final MapleMonster mob1 = MapleLifeFactory.getMonster(9300082);
                final MapleMonster mob2 = MapleLifeFactory.getMonster(9300082);
                final MapleMonster mob3 = MapleLifeFactory.getMonster(9300081);
                final MapleMonster mob4 = MapleLifeFactory.getMonster(9300081);
                final MapleMonster mob5 = MapleLifeFactory.getMonster(9300063);
                final MapleMonster mob6 = MapleLifeFactory.getMonster(9300063);
                final MapleMonster mob7 = MapleLifeFactory.getMonster(9300062);
                final MapleMonster mob8 = MapleLifeFactory.getMonster(9300064);
                final MapleMonster mob9 = MapleLifeFactory.getMonster(9300064);
                final EventInstanceManager eim = c.getEventInstance();
                HPQMobDelay = TimerManager.getInstance().schedule(new Runnable() {
                    @Override
                    public void run() {
                        eim.registerMonster(mob);
		                        tehMap.spawnMonsterOnGroundBelow(mob, -901, -558);
		                        eim.registerMonster(mob1);
		                        tehMap.spawnMonsterOnGroundBelow(mob1, -888, -655);
		                        eim.registerMonster(mob2);
		                        tehMap.spawnMonsterOnGroundBelow(mob2, -609, -442);
		                        eim.registerMonster(mob3);
		                        tehMap.spawnMonsterOnGroundBelow(mob3, -653, -836);
		                        eim.registerMonster(mob4);
		                        tehMap.spawnMonsterOnGroundBelow(mob4, -958, -242);
		                        eim.registerMonster(mob5);
		                        tehMap.spawnMonsterOnGroundBelow(mob5, 587, -263);
		                        eim.registerMonster(mob6);
		                        tehMap.spawnMonsterOnGroundBelow(mob6, -947, -387);
		                        eim.registerMonster(mob7);
		                        tehMap.spawnMonsterOnGroundBelow(mob7, 494, -757);
		                        eim.registerMonster(mob8);
		                        tehMap.spawnMonsterOnGroundBelow(mob8, 177, -836);
		                        eim.registerMonster(mob9);
		                        tehMap.spawnMonsterOnGroundBelow(mob9, 562, -597);
                    }
                }, 2000);
            }
        }
    }
}