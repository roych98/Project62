package net.sf.odinms.net.channel.handler;

import java.awt.Point;
import java.util.concurrent.ScheduledFuture;
import net.sf.odinms.client.ISkill;
import net.sf.odinms.client.MapleCharacter.CancelCooldownAction;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.MapleStat;
import net.sf.odinms.client.SkillFactory;
import net.sf.odinms.client.messages.ServernoticeMapleClientMessageCallback;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.server.MapleStatEffect;
import net.sf.odinms.server.TimerManager;
import net.sf.odinms.server.life.MapleMonster;
import net.sf.odinms.server.maps.FakeCharacter;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class SpecialMoveHandler extends AbstractMaplePacketHandler {

    private static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SpecialMoveHandler.class);

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        c.getPlayer().resetAfkTime();
        long time = slea.readInt();
        if (c.getPlayer().getLastRequestTime() > time || c.getPlayer().getLastRequestTime() == time) { 
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        c.getPlayer().setLastRequestTime(time);
        int skillid = slea.readInt();
        Point pos = null;
        int __skillLevel = slea.readByte();
        ISkill skill = SkillFactory.getSkill(skillid);
        int skillLevel = c.getPlayer().getSkillLevel(skill);
        MapleStatEffect effect = skill.getEffect(skillLevel);

        if (skill.isGMSkill() && !c.getPlayer().isGM()) {
            c.disconnect();
            c.getSession().close();
            return;
        }
        if (effect.getCooldown() > 0 && skillid != 5221006) {
            if (c.getPlayer().skillisCooling(skillid)) {
                c.getSession().write(MaplePacketCreator.enableActions());
                return;
            } else {
                c.getSession().write(MaplePacketCreator.skillCooldown(skillid, effect.getCooldown()));
                ScheduledFuture<?> timer = TimerManager.getInstance().schedule(new CancelCooldownAction(c.getPlayer(), skillid), effect.getCooldown() * 1000);
                c.getPlayer().addCooldown(skillid, System.currentTimeMillis(), effect.getCooldown() * 1000, timer);
            }
        } else if (skillid == 5101005) {// MP Recovery
            ISkill s = SkillFactory.getSkill(skillid);
            MapleStatEffect ef = s.getEffect(c.getPlayer().getSkillLevel(s));
            int lose = c.getPlayer().getMaxHp() / ef.getX();
            c.getPlayer().setHp(c.getPlayer().getHp() - lose);
            c.getPlayer().updateSingleStat(MapleStat.HP, c.getPlayer().getHp());
            int gain = lose * (ef.getY() / 100);
            c.getPlayer().setMp(c.getPlayer().getMp() + gain);
            c.getPlayer().updateSingleStat(MapleStat.MP, c.getPlayer().getMp());
        }
        //monster magnet
        try {
            switch (skillid) {
                case 1121001:
                case 1221001:
                case 1321001:
                    int num = slea.readInt();
                    int mobId;
                    byte success;
                    for (int i = 0; i < num; i++) {
                        mobId = slea.readInt();
                        success = slea.readByte();
                        c.getPlayer().getMap().broadcastMessage(c.getPlayer(), MaplePacketCreator.showMagnet(mobId, success), false);
                        MapleMonster monster = c.getPlayer().getMap().getMonsterByOid(mobId);
                        if (monster != null) {
                            monster.switchController(c.getPlayer(), monster.isControllerHasAggro());
                        }
                    }
                    byte direction = slea.readByte();
                    c.getPlayer().getMap().broadcastMessage(c.getPlayer(), MaplePacketCreator.showBuffeffect(c.getPlayer().getId(), skillid, 1, direction), false);
                    c.getSession().write(MaplePacketCreator.enableActions());
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to handle monster magnet.", e);
        }
        if (slea.available() == 5) {
            pos = new Point(slea.readShort(), slea.readShort());
        }
        if (skillLevel == 0 || skillLevel != __skillLevel) {
            log.warn(c.getPlayer().getName() + " is using a move skill he doesn't have. ID: " + skill.getId());
            c.disconnect();
            return;
        } else {
            if (c.getPlayer().isAlive()) {
                if (skill.getId() != 2311002 || c.getPlayer().canDoor()) {
                    skill.getEffect(skillLevel).applyTo(c.getPlayer(), pos);
                } else {
                    new ServernoticeMapleClientMessageCallback(5, c).dropMessage("Please wait 5 seconds before casting Mystic Door again.");
                    c.getSession().write(MaplePacketCreator.enableActions());
                }
            } else {
                c.getSession().write(MaplePacketCreator.enableActions());
            }
        }
    }
}