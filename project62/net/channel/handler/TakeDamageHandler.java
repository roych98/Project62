package net.sf.odinms.net.channel.handler;

import java.util.Collections;
import net.sf.odinms.client.ISkill;
import net.sf.odinms.client.MapleBuffStat;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleStat;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.SkillFactory;
import net.sf.odinms.client.status.MonsterStatus;
import net.sf.odinms.client.status.MonsterStatusEffect;
import net.sf.odinms.client.MapleInventoryType;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.server.AutobanManager;
import net.sf.odinms.server.life.MapleMonster;
import net.sf.odinms.server.life.MobAttackInfo;
import net.sf.odinms.server.life.MobAttackInfoFactory;
import net.sf.odinms.server.life.MobSkill;
import net.sf.odinms.server.life.MobSkillFactory;
import net.sf.odinms.server.maps.MapleMapObject;
import net.sf.odinms.server.maps.MapleMapObjectType;
import net.sf.odinms.server.maps.MapleMist;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class TakeDamageHandler extends AbstractMaplePacketHandler {

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        try {
            if(c == null) return;
        MapleCharacter player = c.getPlayer();
        if(player == null) c.getSession().close();
        slea.readInt();
        int damagefrom = slea.readByte();
        slea.readByte();
        int damage = slea.readInt();
        if (damage != 0) {
            player.setLastHitTime(System.currentTimeMillis());
        }
        int oid = 0;
        int monsteridfrom = 0;
        int pgmr = 0;
        int direction = 0;
        int pos_x = 0;
        int pos_y = 0;
        int fake = 0;
        boolean is_pgmr = false;
        boolean is_pg = true;
        int mpattack = 0;
        MapleMonster attacker = null;
                if (damagefrom != -2) {
            monsteridfrom = slea.readInt();
            oid = slea.readInt();
            if (monsteridfrom != 0) {
                attacker = (MapleMonster) player.getMap().getMapObject(monsteridfrom);
            } else {
                attacker = (MapleMonster) player.getMap().getMapObject(oid);
            }
            direction = slea.readByte();
        } else if (damagefrom <= -2){
            int debuffLevel = slea.readByte();
            int debuffId = slea.readByte();
            if (debuffId == 125) {
                debuffLevel = debuffLevel - 1;
            }
            MobSkill skill = MobSkillFactory.getMobSkill(debuffId, debuffLevel);
            if (skill != null) {
                skill.applyEffect(player, attacker, false);
            }
        }
        if (damagefrom != -1 && damagefrom != -2 && attacker != null) {
            MobAttackInfo attackInfo = MobAttackInfoFactory.getMobAttackInfo(attacker, damagefrom);
            if (damage != -1) {
                if (attackInfo.isDeadlyAttack()) {
                    mpattack = player.getMp() - 1;
                } else {
                    mpattack += attackInfo.getMpBurn();
                }
                if (mpattack - player.getMp() < 0) {
                    mpattack = player.getMp();
                }
            }
            MobSkill skill = MobSkillFactory.getMobSkill(attackInfo.getDiseaseSkill(), attackInfo.getDiseaseLevel());
            if (skill != null && damage > 0) {
                skill.applyEffect(player, attacker, false);
            }
            if (attacker != null) {
                attacker.setMp(attacker.getMp() - attackInfo.getMpCon());
            }
        }
        if (damage > 0 && !player.isHidden()) {
           if (monsteridfrom != 0) {
                if (damagefrom == 0 && player.getBuffedValue(MapleBuffStat.MANA_REFLECTION) != null) {
                    attacker = (MapleMonster) player.getMap().getMapObject(oid);
                    int jobid = player.getJob().getId();
                    if (jobid == 212 || jobid == 222 || jobid == 232) {
                        int id = jobid * 10000 + 1002;
                        if (player.isBuffFrom(MapleBuffStat.MANA_REFLECTION, SkillFactory.getSkill(id)) && player.getSkillLevel(SkillFactory.getSkill(id)) > 0 && SkillFactory.getSkill(id).getEffect(player.getSkillLevel(SkillFactory.getSkill(id))).makeChanceResult()) {
                            int bouncedamage = (int) (damage * SkillFactory.getSkill(id).getEffect(player.getSkillLevel(SkillFactory.getSkill(id))).getX() / 100.0);
                            if (bouncedamage > attacker.getMaxHp() / 5) {
                                bouncedamage = attacker.getMaxHp() / 5;
                            }
                            player.getMap().damageMonster(player, attacker, bouncedamage);
                            player.getMap().broadcastMessage(player, MaplePacketCreator.damageMonster(oid, bouncedamage), true);
                            player.getClient().getSession().write(MaplePacketCreator.showOwnBuffEffect(id, 5));
                            player.getMap().broadcastMessage(player, MaplePacketCreator.showBuffeffect(player.getId(), id, 5,(byte) 3), false);
                        }
                    }
                }
            } 
        }
        if (damage == -1) {
            int job = (int) (player.getJob().getId() / 10 - 40);
            fake = 4020002 + (job * 100000);
            if (damagefrom == -1 && damagefrom != -2 && player.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -10) != null) {
                int[] guardianSkillId = {1120005, 1220006};
                for (int guardian : guardianSkillId) {
                    ISkill guardianSkill = SkillFactory.getSkill(guardian);
                    if (player.getSkillLevel(guardianSkill) > 0 && attacker != null) {
                        MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.STUN, 1), guardianSkill, false);
                        attacker.applyStatus(player, monsterStatusEffect, false, 2 * 1000);
                    }
                }
            }
        }
        if (damage < -1 || damage > 100000) {
            AutobanManager.getInstance().autoban
            (c,"[AUTOBAN]" + player.getName() + " took " + damage + " of damage.");
        } else if (damage > 60000) {
            c.disconnect();
            return;
        }
        player.getCheatTracker().checkTakeDamage();
       if (damage > 0 && !player.isHidden()) {
            if (monsteridfrom != 0) {
                if (damagefrom == -1 && player.getBuffedValue(MapleBuffStat.POWERGUARD) != null) {
                    attacker = (MapleMonster) player.getMap().getMapObject(oid);
                    int bouncedamage = (int) (damage * (player.getBuffedValue(MapleBuffStat.POWERGUARD).doubleValue() / 100));
                    if(attacker == null) {
                        c.getSession().write(MaplePacketCreator.enableActions());
                        return;
                    }
                    bouncedamage = Math.min(bouncedamage, attacker.getMaxHp() / 10);
                    player.getMap().damageMonster(player, attacker, bouncedamage);
                    damage -= bouncedamage;
                    player.getMap().broadcastMessage(player, MaplePacketCreator.damageMonster(oid, bouncedamage), false, true);
                    player.checkMonsterAggro(attacker);
                }
            }
            if (damagefrom != -2) {
                int achilles = 0;
                ISkill achilles1 = null;
                int jobid = player.getJob().getId();
                if (jobid < 200 && jobid % 10 == 2) {
                    achilles1 = SkillFactory.getSkill(jobid * 10000 + jobid == 112 ? 4 : 5);
                    achilles = player.getSkillLevel(SkillFactory.getSkill(achilles));
                }
                if (achilles != 0 && achilles1 != null) {
                    damage *= (int) (achilles1.getEffect(achilles).getX() / 1000.0 * damage);
                }
            }
            Integer mesoguard = player.getBuffedValue(MapleBuffStat.MESOGUARD);
            if (player.getBuffedValue(MapleBuffStat.MAGIC_GUARD) != null && mpattack == 0) {
                int mploss = (int) (damage * (player.getBuffedValue(MapleBuffStat.MAGIC_GUARD).doubleValue() / 100.0));
                int hploss = damage - mploss;
                if (mploss > player.getMp()) {
                    hploss += mploss - player.getMp();
                    mploss = player.getMp();
                }
                player.addMPHP(-hploss, -mploss);
            } else if (mesoguard != null) {
                damage = Math.round(damage / 2);
                int mesoloss = (int) (damage * (mesoguard.doubleValue() / 100.0));
                if (player.getMeso() < mesoloss) {
                    player.gainMeso(-player.getMeso(), false);
                    player.cancelBuffStats(MapleBuffStat.MESOGUARD);
                } else {
                    player.gainMeso(-mesoloss, false);
                }
                player.addMPHP(-damage, -mpattack);
            } else if (player.getBuffedValue(MapleBuffStat.MONSTER_RIDING) != null) {
                if (player.getBuffedValue(MapleBuffStat.MONSTER_RIDING) == 5221999) {
                    player.decreaseBattleshipHp(damage);
                } else {
                    player.addMPHP(-damage, -mpattack);
                }
            } else {
                player.addMPHP(-damage, -mpattack);
            } 
        }
        if (!player.isHidden()) {
            player.getMap().broadcastMessage(player, MaplePacketCreator.damagePlayer(damagefrom, monsteridfrom, player.getId(), damage, fake, direction, is_pgmr, pgmr, is_pg, oid, pos_x, pos_y), false);
            player.updateSingleStat(MapleStat.HP, player.getHp());
            player.updateSingleStat(MapleStat.MP, player.getMp());
            player.checkBerserk();
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
    }
}