package net.sf.odinms.net.channel.handler;

import java.awt.Point;
import java.util.List;
import java.util.Random;
import net.sf.odinms.client.Item;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.MaplePacket;
import net.sf.odinms.server.life.MapleMonster;
import net.sf.odinms.server.life.MobSkill;
import net.sf.odinms.server.life.MobSkillFactory;
import net.sf.odinms.server.maps.MapleMapObject;
import net.sf.odinms.server.maps.MapleMapObjectType;
import net.sf.odinms.server.movement.LifeMovementFragment;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.Pair;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoveLifeHandler extends AbstractMovementPacketHandler {

    private static Logger log = LoggerFactory.getLogger(MoveLifeHandler.class);

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        try {
        if(slea == null || c == null) return;
        if(c.getPlayer().getMap().getMapOwner() != null) {
            if(c.getPlayer().getMap().getMapOwner().isAfk(300)) {
                c.getPlayer().getMap().switchMapleMapOwner();
            }
        }
        int objectid = slea.readInt();
        short moveid = slea.readShort();
        MapleMapObject mmo = c.getPlayer().getMap().getMapObject(objectid);
        if (mmo == null || mmo.getType() != MapleMapObjectType.MONSTER) {
            /*if (mmo != null) {
            log.warn("[dc] Player {} is trying to move something which is not a monster. It is a {}.", new Object[] {
            c.getPlayer().getName(), c.getPlayer().getMap().getMapObject(objectid).getClass().getCanonicalName() });
            }*/
            return;
        }
        MapleMonster monster = (MapleMonster) mmo;
        List<LifeMovementFragment> res = null;
        int skillByte = slea.readByte();
        int skill = slea.readByte();
        int skill_1 = slea.readByte() & 0xFF;
        int skill_2 = slea.readByte();
        int skill_3 = slea.readByte();
        @SuppressWarnings("unused")
        int skill_4 = slea.readByte();
        MobSkill toUse = null;
        Random rand = new Random();
        if (skillByte == 1 && monster.getNoSkills() > 0) {
            int random = rand.nextInt(monster.getNoSkills());
            Pair<Integer, Integer> skillToUse = monster.getSkills().get(random);
            toUse = MobSkillFactory.getMobSkill(skillToUse.getLeft(), skillToUse.getRight());
            int percHpLeft = (int) ((monster.getHp() / monster.getMaxHp()) * 100);
            if (toUse.getHP() < percHpLeft || !monster.canUseSkill(toUse)) {
                toUse = null;
            }
        }
        if (skill_1 >= 100 && skill_1 <= 200 && monster.hasSkill(skill_1, skill_2)) {
            MobSkill skillData = MobSkillFactory.getMobSkill(skill_1, skill_2);
            if (skillData != null && monster.canUseSkill(skillData)) {
                skillData.applyEffect(c.getPlayer(), monster, true);
            }
        }
        slea.readByte();
        slea.readInt();
        int start_x = slea.readShort();
        int start_y = slea.readShort();
        Point startPos = new Point(start_x, start_y);
        res = parseMovement(slea);
        if (monster.getController() != c.getPlayer()) {
            if (monster.isAttackedBy(c.getPlayer())) { // Aggro and controller change.
                monster.switchController(c.getPlayer(), true);
            } else {
                return;
            }
        } else {
            if (skill == -1 && monster.isControllerKnowsAboutAggro() && !monster.isMobile() && !monster.isFirstAttack()) {
                monster.setControllerHasAggro(false);
                monster.setControllerKnowsAboutAggro(false);
            }
        }
        boolean aggro = monster.isControllerHasAggro();

        if (toUse != null) {
            c.getSession().write(MaplePacketCreator.moveMonsterResponse(objectid, moveid, monster.getMp(), aggro, toUse.getSkillId(), toUse.getSkillLevel()));
        } else {
            c.getSession().write(MaplePacketCreator.moveMonsterResponse(objectid, moveid, monster.getMp(), aggro));
        }
        if (aggro) {
            monster.setControllerKnowsAboutAggro(true);
        }
        if (res != null) {
            if (slea.available() != 9) {
                log.warn("slea.available != 9 (movement parsing error)");
                return;
            }
            MaplePacket packet = MaplePacketCreator.moveMonster(skillByte, skill, skill_1, skill_2, skill_3, objectid, startPos, res);
            c.getPlayer().getMap().broadcastMessage(c.getPlayer(), packet, monster.getPosition());
            updatePosition(res, monster, -1);
            c.getPlayer().getMap().moveMonster(monster, monster.getPosition());
            c.getPlayer().getCheatTracker().checkMoveMonster(monster.getPosition());
        }
        
        if (monster.getId() == 9300061 && monster.getHp() < monster.getMaxHp() / 2 && Math.random() < 0.6) {
            if (monster.getShouldDrop() == true) {
                monster.getMap().broadcastMessage(MaplePacketCreator.serverNotice(6, "[Henesys PQ] The Moon Bunny is feeling sick. Please protect him so he can make delicious rice cakes!"));
                monster.setShouldDrop(false);
                monster.scheduleCanDrop(6000);
            }
        }

        if (monster.getId() == 9300061 && (monster.getShouldDrop() == true || monster.getJustSpawned() == true)) {
            if (monster.getJustSpawned() == true) {
                monster.setJustSpawned(false);
                monster.setDropped(1);
                monster.getMap().broadcastMessage(MaplePacketCreator.serverNotice(6, "[Henesys PQ] The Moon Bunny just made his first cake! Protect him!."));
                Item Item = new Item(4001101, (byte) 0, (short) 1);
                try {
                    monster.getMap().spawnItemDrop(monster, monster.getEventInstance().getPlayers().get(0), Item, monster.getPosition(), false, false);
                } catch (Exception ex) {

                }
            } else {
                if (monster.getShouldDrop() == true) {
                    int d = monster.getDropped() + 1;
                    monster.getMap().broadcastMessage(MaplePacketCreator.serverNotice(6, "[Henesys PQ] The Moon Bunny made  " + d + " rice cake(s)."));
                    monster.setDropped(d);
                    monster.setShouldDrop(false);
                    monster.scheduleCanDrop(10000);
                    Item Item = new Item(4001101, (byte) 0, (short) 1);
                    try {
                        monster.getMap().spawnItemDrop(monster, monster.getEventInstance().getPlayers().get(0), Item, monster.getPosition(), false, false);  
                    } catch (NullPointerException ex) {

                    }
                }
                
            }
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
    }
}