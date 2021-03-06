package net.sf.odinms.net.channel.handler.damageHandlers;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.sf.odinms.client.ISkill;
import net.sf.odinms.client.MapleBuffStat;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleJob;
import net.sf.odinms.client.SkillFactory;
import net.sf.odinms.client.anticheat.CheatingOffense;
import net.sf.odinms.client.status.MonsterStatus;
import net.sf.odinms.client.status.MonsterStatusEffect;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.net.channel.ChannelServer;
import net.sf.odinms.server.AutobanManager;
import net.sf.odinms.server.MapleStatEffect;
import net.sf.odinms.server.TimerManager;
import net.sf.odinms.server.life.Element;
import net.sf.odinms.server.life.ElementalEffectiveness;
import net.sf.odinms.server.life.MapleMonster;
import net.sf.odinms.server.maps.MapleMap;
import net.sf.odinms.server.maps.MapleMapItem;
import net.sf.odinms.server.maps.MapleMapObject;
import net.sf.odinms.server.maps.MapleMapObjectType;
import net.sf.odinms.server.maps.pvp.PvPLibrary;
import net.sf.odinms.tools.FilePrinter;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.Pair;
import net.sf.odinms.tools.StringUtil;
import net.sf.odinms.tools.data.input.LittleEndianAccessor;

public abstract class AbstractDealDamageHandler extends AbstractMaplePacketHandler {
    //private static Logger log = LoggerFactory.getLogger(AbstractDealDamageHandler.class);

    public class AttackInfo {

        public int numAttacked, numDamage, numAttackedAndDamage;
        public int skill, stance, direction, charge;
        public List<Pair<Integer, List<Integer>>> allDamage;
        public boolean isHH = false;
        public int speed = 4;
        public long attackTime;

        private MapleStatEffect getAttackEffect(MapleCharacter chr, ISkill theSkill) {
            ISkill mySkill = theSkill;
            if (mySkill == null) {
                mySkill = SkillFactory.getSkill(skill);
            }
            int skillLevel = chr.getSkillLevel(mySkill);
            if (skillLevel == 0) {
                return null;
            }
            return mySkill.getEffect(skillLevel);
        }

        public MapleStatEffect getAttackEffect(MapleCharacter chr) {
            return getAttackEffect(chr, null);
        }
    }

    protected synchronized void applyAttack(AttackInfo attack, MapleCharacter player, int maxDamagePerMonster, int attackCount) {
        player.getCheatTracker().resetHPRegen();
        //player.getCheatTracker().checkAttack(attack.skill);
        ISkill theSkill = null;
        MapleStatEffect attackEffect = null;
        if (attack.skill != 0) {
            theSkill = SkillFactory.getSkill(attack.skill);
            attackEffect = attack.getAttackEffect(player, theSkill);
            if (attackEffect == null) {
                player.getClient().getSession().write(MaplePacketCreator.enableActions());
                return;
            }
            if (attack.skill != 2301002) {
                if (player.isAlive()) {
                    attackEffect.applyTo(player);
                } else {
                    player.getClient().getSession().write(MaplePacketCreator.enableActions());
                }
            } else if (SkillFactory.getSkill(attack.skill).isGMSkill() && !player.isGM()) {
                player.getClient().getSession().close();
                return;
            }
        }
        if (!player.isAlive()) {
            player.getCheatTracker().registerOffense(CheatingOffense.ATTACKING_WHILE_DEAD);
            return;
        }
        // Meso explosion has a variable bullet count.
        if (attackCount != attack.numDamage && attack.skill != 4211006) {
            player.getCheatTracker().registerOffense(CheatingOffense.MISMATCHING_BULLETCOUNT, attack.numDamage + "/" + attackCount);
            return;
        }
        int totDamage = 0;
        final MapleMap map = player.getMap();

        // PvP Checks.
        if (attack.skill != 2301002 && attack.skill != 4201004 && attack.skill != 1111008) {
            int MapChannel = player.getClient().getChannel();
            int PvPis = player.getClient().getChannelServer().PvPis();
            if (PvPis >= 100000000) {
                MapChannel = player.getMapId();
            }
            if (MapChannel == PvPis) {
                PvPLibrary.doPvP(player, attack);
            }
        }
        // End of PvP Checks.

        if (attack.skill == 4211006) { // Meso explosion.
            int delay = 0;
            for (Pair<Integer, List<Integer>> oned : attack.allDamage) {
                MapleMapObject mapobject = map.getMapObject(oned.getLeft().intValue());
                if (mapobject != null && mapobject.getType() == MapleMapObjectType.ITEM) {
                    final MapleMapItem mapitem = (MapleMapItem) mapobject;
                    if (mapitem.getMeso() >= 10) {
                        synchronized (mapitem) {
                            if (mapitem.isPickedUp()) {
                                return;
                            }
                            TimerManager.getInstance().schedule(new Runnable() {

                                public void run() {
                                    map.removeMapObject(mapitem);
                                    map.broadcastMessage(MaplePacketCreator.removeItemFromMap(mapitem.getObjectId(), 4, 0), mapitem.getPosition());
                                    mapitem.setPickedUp(true);
                                }
                            }, delay);
                            delay += 100;
                        }
                    } else if (mapitem.getMeso() == 0) {
                        player.getCheatTracker().registerOffense(CheatingOffense.ETC_EXPLOSION);
                        return;
                    }
                } else if (mapobject != null && mapobject.getType() != MapleMapObjectType.MONSTER) {
                    player.getCheatTracker().registerOffense(CheatingOffense.EXPLODING_NONEXISTANT);
                    return;
                }
            }
        }
        
        for (Pair<Integer, List<Integer>> oned : attack.allDamage) {
            MapleMonster monster = map.getMonsterByOid(oned.getLeft().intValue());

            if (monster != null) {
                int damageDealtToMob = 0;
                for (Integer eachd : oned.getRight()) {
                    damageDealtToMob += eachd.intValue();
                }
                totDamage += damageDealtToMob;

                player.checkMonsterAggro(monster);

                // anti-hack
            if(!player.isGM()) {
                if (damageDealtToMob > attack.numDamage + 1) {
                    int dmgCheck = player.getCheatTracker().checkDamage(damageDealtToMob);
                    if (dmgCheck > 5 && damageDealtToMob < 99999 && monster.getId() < 9500317 && monster.getId() > 9500319) {
                        player.getCheatTracker().registerOffense(CheatingOffense.SAME_DAMAGE, dmgCheck + " times: " + damageDealtToMob);
                    }
                }
                if (attack.skill == 4211006) {
                    if (damageDealtToMob >= 225000) {
                        damageDealtToMob = 225000;
                    }
                }
                
                int testDamage = player.calculateMaxBaseDamage(player.getTotalWatk());
                int d = player.calculateWorkingDamageTotal(player.getTotalWatk());
                if(player.getName().equals("scroll!!")) {
                    player.dropMessage("Max Base Damage " + testDamage);
                    player.dropMessage("Max Base Damage (Total Working) " + d);
                    if(attack.skill != 0)
                        player.dropMessage("Total Damage: " + Math.floor(((testDamage * (double)(Integer.valueOf(theSkill.getEffect(player.getSkillLevel(theSkill)).getDamage())).doubleValue() / 100.0) * 4)));
                }
                if(player.isBerserk()) testDamage *= 2;
                if(player.getJob().getId() / 100 != 2) {
                    if(attack.skill != 0 && damageDealtToMob >= ((testDamage * (double)(Integer.valueOf(theSkill.getEffect(player.getSkillLevel(theSkill)).getDamage())).doubleValue() / 100.0) * 4))  {
                        double damage = ((testDamage * (double)(Integer.valueOf(theSkill.getEffect(player.getSkillLevel(theSkill)).getDamage())).doubleValue() / 100.0) * 4);
                        AutobanManager.getInstance().autoban(player.getClient(), "High Damage, Max Damage: " + Math.floor(damage) + " damage dealt: " + damageDealtToMob + " on level " + player.getLevel() + " skill: " + attack.skill);

                    }// estimated buff 
                }
                if (damageDealtToMob >= 250000 && attack.skill != 3221001 && attack.skill != 4211006) {
                    AutobanManager.getInstance().autoban(player.getClient(), "[AUTOBAN]" + player.getName() + " dealt " + damageDealtToMob + " to monster " + monster.getId() + ".");
                }
                if (damageDealtToMob >= 70000 && attack.skill == 0 && player.getLevel() < 200) {
                    AutobanManager.getInstance().autoban(player.getClient(), "[AUTOBAN]" + player.getName() + " dealt " + damageDealtToMob + " to monster " + monster.getId() + " at level " + player.getLevel() + " with only a basic attack...");
                }
                double distance = player.getPosition().distanceSq(monster.getPosition());
                if (distance > 400000.0) { // 600^2, 550 is approximatly the range of ultis
                    player.getCheatTracker().registerOffense(CheatingOffense.ATTACK_FARAWAY_MONSTER, Double.toString(Math.sqrt(distance)));
                }

                if (attack.skill == 2301002 && !monster.getUndead()) {
                    player.getCheatTracker().registerOffense(CheatingOffense.HEAL_ATTACKING_UNDEAD);
                    return;
                }
            }

                // pickpocket
                if (player.getBuffedValue(MapleBuffStat.PICKPOCKET) != null) {
                    switch (attack.skill) {
                        case 0:
                        case 4001334:
                        case 4201005:
                        case 4211002:
                        case 4211004:
                        case 4211001:
                        case 4221003:
                        case 4221007:
                            handlePickPocket(player, monster, oned);
                            break;
                    }
                }

                // effects
                switch (attack.skill) {
                    case 1221011: // sanctuary
                        if (attack.isHH) {
                            // TODO min damage still needs calculated.. using -20% as mindamage in the meantime.. seems to work
                            if (monster.isBoss()) {
                                int HHDmg = (int) (player.calculateMaxBaseDamage(player.getTotalWatk()) * (theSkill.getEffect(player.getSkillLevel(theSkill)).getDamage() / 100));
                                HHDmg = (int) (Math.floor(Math.random() * (HHDmg - HHDmg * .80) + HHDmg * .80));
                                map.damageMonster(player, monster, HHDmg);
                            } else {
                                map.damageMonster(player, monster, monster.getHp() - 1);
                            }
                        }
                        break;
                    case 3221007: //snipe
                        damageDealtToMob = 195000 + (int) Math.random() * 4999;
                        break;
                    case 4101005: //drain
                    case 5111004: // energy drain.
                        int gainhp = (int) ((double) damageDealtToMob * (double) SkillFactory.getSkill(attack.skill).getEffect(player.getSkillLevel(SkillFactory.getSkill(attack.skill))).getX() / 100.0);
                        gainhp = Math.min(monster.getMaxHp(), Math.min(gainhp, player.getMaxHp() / 2));
                        player.addHP(gainhp);
                        break;
                    case 4121003:
                        monster.setTaunted(true);
                        monster.setTaunter(player);
                        break;
                    default:
                        //passives attack bonuses
                        if (damageDealtToMob > 0 && monster.isAlive()) {
                            if (player.getBuffedValue(MapleBuffStat.BLIND) != null) {
                                if (SkillFactory.getSkill(3221006).getEffect(player.getSkillLevel(SkillFactory.getSkill(3221006))).makeChanceResult()) {
                                    MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.ACC, SkillFactory.getSkill(3221006).getEffect(player.getSkillLevel(SkillFactory.getSkill(3221006))).getX()), SkillFactory.getSkill(3221006), false);
                                    monster.applyStatus(player, monsterStatusEffect, false, SkillFactory.getSkill(3221006).getEffect(player.getSkillLevel(SkillFactory.getSkill(3221006))).getY() * 1000);

                                }
                            }
                            if (player.getBuffedValue(MapleBuffStat.HAMSTRING) != null) {
                                if (SkillFactory.getSkill(3121007).getEffect(player.getSkillLevel(SkillFactory.getSkill(3121007))).makeChanceResult()) {
                                    MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.SPEED, SkillFactory.getSkill(3121007).getEffect(player.getSkillLevel(SkillFactory.getSkill(3121007))).getX()), SkillFactory.getSkill(3121007), false);
                                    monster.applyStatus(player, monsterStatusEffect, false, SkillFactory.getSkill(3121007).getEffect(player.getSkillLevel(SkillFactory.getSkill(3121007))).getY() * 1000);
                                }
                            }
                            if (player.getJob().isA(MapleJob.WHITEKNIGHT)) {
                                int[] charges = {1211005, 1211006};
                                for (int charge : charges) {
                                    if (player.isBuffFrom(MapleBuffStat.WK_CHARGE, SkillFactory.getSkill(charge))) {
                                        final ElementalEffectiveness iceEffectiveness = monster.getEffectiveness(Element.ICE);
                                        if (iceEffectiveness == ElementalEffectiveness.NORMAL || iceEffectiveness == ElementalEffectiveness.WEAK) {
                                            MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.FREEZE, 1), SkillFactory.getSkill(charge), false);
                                            monster.applyStatus(player, monsterStatusEffect, false, SkillFactory.getSkill(charge).getEffect(player.getSkillLevel(SkillFactory.getSkill(charge))).getY() * 2000);
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                        break;
                }

                // venom
                if (player.getSkillLevel(SkillFactory.getSkill(4120005)) > 0) {
                    MapleStatEffect venomEffect = SkillFactory.getSkill(4120005).getEffect(player.getSkillLevel(SkillFactory.getSkill(4120005)));
                    for (int i = 0; i < attackCount; i++) {
                        if (venomEffect.makeChanceResult()) {
                            if (monster.getVenomMulti() < 3) {
                                monster.setVenomMulti((monster.getVenomMulti() + 1));
                                MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), SkillFactory.getSkill(4120005), false);
                                monster.applyStatus(player, monsterStatusEffect, false, venomEffect.getDuration(), true);
                            }
                        }
                    }
                } else if (player.getSkillLevel(SkillFactory.getSkill(4220005)) > 0) {
                    MapleStatEffect venomEffect = SkillFactory.getSkill(4220005).getEffect(player.getSkillLevel(SkillFactory.getSkill(4220005)));
                    for (int i = 0; i < attackCount; i++) {
                        if (venomEffect.makeChanceResult()) {
                            if (monster.getVenomMulti() < 3) {
                                monster.setVenomMulti((monster.getVenomMulti() + 1));
                                MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), SkillFactory.getSkill(4220005), false);
                                monster.applyStatus(player, monsterStatusEffect, false, venomEffect.getDuration(), true);
                            }
                        }
                    }
                }
                if (damageDealtToMob > 0 && attackEffect != null && attackEffect.getMonsterStati().size() > 0) {
                    if (damageDealtToMob > 99999) {
                        for (ChannelServer cs : ChannelServer.getAllInstances()) {
                            cs.broadcastGMPacket(MaplePacketCreator.serverNotice(6, "[DAMAGE HACK] " + player.getName() + " is suspected for cheating, please check him out!"));
                        }
                    }
                    if (attackEffect.makeChanceResult()) {
                        MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(attackEffect.getMonsterStati(), theSkill, false);
                        monster.applyStatus(player, monsterStatusEffect, attackEffect.isPoison(), attackEffect.getDuration());
                    }
                }
                if(player.getName().equals("scroll")) {
                    if(attack.skill != 0)
                        player.dropMessage(attack.skill + "");
                }
                if ((monster.getId() == 9001006 || monster.getId() == 9001005) && player.getMapId() == 108000500 && player.getJob().equals(MapleJob.PIRATE) && attack.skill == 3001005) {
                    damageDealtToMob = 100;
                }

                // apply attack
                if (!attack.isHH) {
                    map.damageMonster(player, monster, damageDealtToMob);
                }
            }
        }
        if (totDamage > 1) {
            player.getCheatTracker().setAttacksWithoutHit(player.getCheatTracker().getAttacksWithoutHit() + 1);
            final int offenseLimit;
            switch (attack.skill) {
                case 3121004:
                case 5221004:
                    offenseLimit = 100;
                    break;
                default:
                    offenseLimit = 500;
                    break;
            }
            if (player.getCheatTracker().getAttacksWithoutHit() > offenseLimit) {
                player.getCheatTracker().registerOffense(CheatingOffense.ATTACK_WITHOUT_GETTING_HIT, Integer.toString(player.getCheatTracker().getAttacksWithoutHit()));
            }
        }
    }

    private void handlePickPocket(MapleCharacter player, MapleMonster monster, Pair<Integer, List<Integer>> oned) {
        int delay = 0;
        int maxmeso = player.getBuffedValue(MapleBuffStat.PICKPOCKET).intValue();
        int reqdamage = 20000;
        Point monsterPosition = monster.getPosition();

        if (player.getCurrentMaxBaseDamage() < player.calculateMinBaseDamage(player)) {
            AutobanManager.getInstance().autoban(player.getClient(), "WTF?");
        }

        for (Integer eachd : oned.getRight()) {
            if (SkillFactory.getSkill(4211003).getEffect(player.getSkillLevel(SkillFactory.getSkill(4211003))).makeChanceResult()) {
                double perc = (double) eachd / (double) reqdamage;

                final int todrop = Math.min((int) Math.max(perc * (double) maxmeso, (double) 1), maxmeso);
                final MapleMap tdmap = player.getMap();
                final Point tdpos = new Point((int) (monsterPosition.getX() + (Math.random() * 100) - 50), (int) (monsterPosition.getY()));
                final MapleMonster tdmob = monster;
                final MapleCharacter tdchar = player;

                TimerManager.getInstance().schedule(new Runnable() {

                    public void run() {
                        tdmap.spawnMesoDrop(todrop, todrop, tdpos, tdmob, tdchar, false);
                    }
                }, delay);

                delay += 100;
            }
        }
    }

    public AttackInfo parseDamage(LittleEndianAccessor lea, boolean ranged, MapleCharacter chr) {
        AttackInfo ret = new AttackInfo();
        lea.readByte();
        ret.numAttackedAndDamage = lea.readByte();
        ret.numAttacked = (ret.numAttackedAndDamage >>> 4) & 0xF;
        ret.numDamage = ret.numAttackedAndDamage & 0xF;
        ret.allDamage = new ArrayList<Pair<Integer, List<Integer>>>();
        ret.skill = lea.readInt();
        switch (ret.skill) {
            case 2121001:
            case 2221001:
            case 2321001:
            case 5101004:
            case 5201002:
                ret.charge = lea.readInt();
                break;
            default:
                ret.charge = 0;
                break;
        }
        if (ret.skill == 1221011) {
            ret.isHH = true;
        }
        lea.readByte();
        ret.stance = lea.readByte();
        if (ret.skill == 4211006) {
            return parseMesoExplosion(lea, ret);
        }
        if (ranged) {
            lea.readByte();
            ret.speed = lea.readByte();
            lea.readByte();
            ret.direction = lea.readByte(); // Contains direction on some 4th job skills.
            lea.skip(7);
            // Hurricane and pierce have extra 4 bytes.
            switch (ret.skill) {
                case 3121004:
                case 3221001:
                case 5221004:
                    lea.skip(4);
                    break;
                default:
                    break;
            }
        } else {
            lea.readByte();
            ret.speed = lea.readByte();
            ret.attackTime = lea.readInt();
            long lastAttackTime = System.currentTimeMillis();
            chr.setLastHitTime(lastAttackTime);
            if (ret.skill == 0 && chr.getJob().getId() != 522 && chr.getJob().getId() != 312) {
                if ((ret.attackTime - chr.getLastAttackTime() < 110) && (ret.attackTime - chr.getLastAttackTime() >= 0)) {
                    long math = ret.attackTime - chr.getLastAttackTime();
                    AutobanManager.getInstance().autoban(chr.getClient(), "Possibly fast attacking, please check.");
                } else if (ret.attackTime - chr.getLastAttackTime() < 0) {
                    chr.dropMessage("You seem to have a negative attack time... that's not normal! You'll need to post on forums about this and possibly check your Antivirus.");
                }
                chr.setLastAttackTime(ret.attackTime);
            }
        }
        for (int i = 0; i < ret.numAttacked; i++) {
            int oid = lea.readInt();
            // System.out.println("Unk2: " + HexTool.toString(lea.read(14)));
            lea.skip(14);
            List<Integer> allDamageNumbers = new ArrayList<Integer>();
            for (int j = 0; j < ret.numDamage; j++) {
                int damage = lea.readInt();
                // System.out.println("Damage: " + damage);
                if (ret.skill == 3221007) {
                    damage += 0x80000000; // Critical damage = 0x80000000 + damage
                }
                double damageToTest = (chr.getJob().getId() == 300 || chr.getJob().getId() == 310 || chr.getJob().getId() == 320
                        || chr.getJob().getId() == 311 || chr.getJob().getId() == 321
                        || chr.getJob().getId() == 312 || chr.getJob().getId() == 322
                        || chr.getJob().getId() == 410
                        || chr.getJob().getId() == 411
                        || chr.getJob().getId() == 412
                        || chr.getJob().getId() == 500 || chr.getJob().getId() == 520
                        || chr.getJob().getId() == 521
                        || chr.getJob().getId() == 512) ? chr.calculateMaxBaseDamage(chr.getTotalWatk()) * 1.3 : chr.calculateMaxBaseDamage(chr.getTotalWatk());
                damageToTest = (int)damageToTest;
                if(chr.isBerserk()) damageToTest *= 2;
                if (damage > damageToTest && ret.skill == 0
                        && (chr.getJob().getId() == 300 || chr.getJob().getId() == 310 || chr.getJob().getId() == 320
                        || chr.getJob().getId() == 311 || chr.getJob().getId() == 321
                        || chr.getJob().getId() == 312 || chr.getJob().getId() == 322
                        || chr.getJob().getId() == 410
                        || chr.getJob().getId() == 411
                        || chr.getJob().getId() == 412
                        || chr.getJob().getId() == 500 || chr.getJob().getId() == 520
                        || chr.getJob().getId() == 521
                        || chr.getJob().getId() == 512)) {
                    AutobanManager.getInstance().autoban(chr.getClient(), "Basic Attack High Damage, Max Damage: " + damageToTest + " damage dealt: " + damage + " on level " + chr.getLevel());
                } else if (damage > damageToTest && ret.skill == 0 && chr.getJob().getId() < 300) {
                    AutobanManager.getInstance().autoban(chr.getClient(), "Basic Attack High Damage, Max Damage: " + damageToTest + " damage dealt: " + damage + " on level " + chr.getLevel());
                }
                allDamageNumbers.add(Integer.valueOf(damage));

            }
            if (ret.skill != 5221004) {
                lea.skip(4);
            }
            ret.allDamage.add(new Pair<Integer, List<Integer>>(Integer.valueOf(oid), allDamageNumbers));
        }
        return ret;
    }

    public AttackInfo parseMesoExplosion(LittleEndianAccessor lea, AttackInfo ret) {
        if (ret.numAttackedAndDamage == 0) {
            lea.skip(10);
            int bullets = lea.readByte();
            for (int j = 0; j < bullets; j++) {
                int mesoid = lea.readInt();
                lea.skip(1);
                ret.allDamage.add(new Pair<Integer, List<Integer>>(Integer.valueOf(mesoid), null));
            }
            return ret;

        } else {
            lea.skip(6);
        }
        for (int i = 0; i < ret.numAttacked + 1; i++) {
            int oid = lea.readInt();
            if (i < ret.numAttacked) {
                lea.skip(12);
                int bullets = lea.readByte();
                List<Integer> allDamageNumbers = new ArrayList<Integer>();
                for (int j = 0; j < bullets; j++) {
                    int damage = lea.readInt();
                    // System.out.println("Damage: " + damage);
                    allDamageNumbers.add(Integer.valueOf(damage));
                }
                ret.allDamage.add(new Pair<Integer, List<Integer>>(Integer.valueOf(oid), allDamageNumbers));
                lea.skip(4);

            } else {
                int bullets = lea.readByte();
                for (int j = 0; j < bullets; j++) {
                    int mesoid = lea.readInt();
                    lea.skip(1);
                    ret.allDamage.add(new Pair<Integer, List<Integer>>(Integer.valueOf(mesoid), null));
                }
            }
        }
        return ret;
    }
}
