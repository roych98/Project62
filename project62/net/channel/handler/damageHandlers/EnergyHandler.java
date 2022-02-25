package net.sf.odinms.net.channel.handler.damageHandlers;

import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class EnergyHandler extends AbstractDealDamageHandler {

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        MapleCharacter player = c.getPlayer();
        player.resetAfkTime();
        if (player.getEnergy() == 10000) {
            AttackInfo attack = parseDamage(slea, false, player);
            applyAttack(attack, player, 999999, 1);
            player.getMap().broadcastMessage(player, MaplePacketCreator.closeRangeAttack(player.getId(), attack.skill, attack.stance, attack.numAttackedAndDamage, attack.allDamage, attack.speed), false, true);
        }
    }
}