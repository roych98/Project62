package net.sf.odinms.net.channel.handler.damageHandlers;

import java.util.concurrent.ScheduledFuture;
import net.sf.odinms.client.ISkill;
import net.sf.odinms.client.MapleBuffStat;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleCharacter.CancelCooldownAction;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.MapleJob;
import net.sf.odinms.client.MapleStat;
import net.sf.odinms.client.SkillFactory;
import net.sf.odinms.net.MaplePacket;
import net.sf.odinms.server.MapleStatEffect;
import net.sf.odinms.server.TimerManager;
import net.sf.odinms.server.maps.FakeCharacter;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;
import net.sf.odinms.tools.Randomizer;

public class MeleeHandler extends AbstractDealDamageHandler {

    private boolean isFinisher(int skillId) {
        return skillId > 1111002 && skillId < 1111007;
    }

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        AttackInfo attack = parseDamage(slea, false, c.getPlayer());
        MapleCharacter player = c.getPlayer();
        player.resetAfkTime();
        MaplePacket packet = MaplePacketCreator.closeRangeAttack(player.getId(), attack.skill, attack.stance, attack.numAttackedAndDamage, attack.allDamage, attack.speed);
        player.getMap().broadcastMessage(player, packet, false, true);
        int numFinisherOrbs = 0;
        Integer comboBuff = player.getBuffedValue(MapleBuffStat.COMBO);
        if (isFinisher(attack.skill)) {
            if (comboBuff != null) {
                try {
                numFinisherOrbs = comboBuff.intValue() - 1;
                } catch (ArrayIndexOutOfBoundsException e) {
                  e.printStackTrace(); //this should only happen if you're a GM I hope lol
                }
            }
            player.handleOrbconsume();
        } else if (attack.numAttacked > 0) {
            // Handle combo orbgain.
            if (comboBuff != null) {
                if (attack.skill != 1111008) { // Shout should not give orbs.
                    player.handleOrbgain();
                }
            } else if ((player.getJob().equals(MapleJob.BUCCANEER) || player.getJob().equals(MapleJob.MARAUDER)) && player.getSkillLevel(SkillFactory.getSkill(5110001)) > 0) {
                for (int i = 0; i < attack.numAttacked; i++) {
                    player.handleEnergyChargeGain();
                }
            }
        }

        // Handle sacrifice hp loss.
        if (attack.numAttacked > 0 && attack.skill == 1311005) {
            int totDamageToOneMonster = attack.allDamage.get(0).getRight().get(0).intValue(); // sacrifice attacks only 1 mob with 1 attack
            int remainingHP = player.getHp() - totDamageToOneMonster * attack.getAttackEffect(player).getX() / 100;
            if (remainingHP > 1) {
                player.setHp(remainingHP);
            } else {
                player.setHp(1);
            }
            player.updateSingleStat(MapleStat.HP, player.getHp());
        }
        // Handle charged blow.
        if (attack.numAttacked > 0 && attack.skill == 1211002) {
            int chargeChance = Randomizer.nextInt(100);//randomizer. % chance for it to fail is skilllevel * 10. level 1=10% and so on. this is an easy way to do it. probably inefficent?
            int charge = player.getSkillLevel(SkillFactory.getSkill(1220010));
            if (charge > 0) {
                if(charge*10 < chargeChance) {
                    player.cancelEffectFromBuffStat(MapleBuffStat.WK_CHARGE);
                }
            } 
        }
        int maxdamage = c.getPlayer().getCurrentMaxBaseDamage();
        int attackCount = 1;
        if (attack.skill != 0) {
            MapleStatEffect effect = attack.getAttackEffect(c.getPlayer());
            attackCount = effect.getAttackCount();
            maxdamage *= effect.getDamage() / 100.0;
            maxdamage *= attackCount;
        }
        maxdamage = Math.min(maxdamage, 99999);
        if (attack.skill == 4211006) {
            maxdamage = 700000;
        } else if (numFinisherOrbs > 0) {
            maxdamage *= numFinisherOrbs;
        } else if (comboBuff != null) {
            ISkill combo = SkillFactory.getSkill(1111002);
            int comboLevel = player.getSkillLevel(combo);
            MapleStatEffect comboEffect = combo.getEffect(comboLevel);
            double comboMod = 1.0 + (comboEffect.getDamage() / 100.0 - 1.0) * (comboBuff.intValue() - 1);
            maxdamage *= comboMod;
        }
        if (numFinisherOrbs == 0 && isFinisher(attack.skill)) {
            return; // Can only happen when lagging.
        }
        if (isFinisher(attack.skill)) {
            maxdamage = 99999;
        }
        if (attack.skill > 0) {
            ISkill skill = SkillFactory.getSkill(attack.skill);
            int skillLevel = c.getPlayer().getSkillLevel(skill);
            MapleStatEffect effect_ = skill.getEffect(skillLevel);
            if (effect_.getCooldown() > 0) {
                if (player.skillisCooling(attack.skill)) {
                    //player.getCheatTracker().registerOffense(CheatingOffense.COOLDOWN_HACK);
                    return;
                } else {
                    c.getSession().write(MaplePacketCreator.skillCooldown(attack.skill, effect_.getCooldown()));
                    ScheduledFuture<?> timer = TimerManager.getInstance().schedule(new CancelCooldownAction(c.getPlayer(), attack.skill), effect_.getCooldown() * 1000);
                    player.addCooldown(attack.skill, System.currentTimeMillis(), effect_.getCooldown() * 1000, timer);
                }
            }
        }
        applyAttack(attack, player, maxdamage, attackCount);
    }
}