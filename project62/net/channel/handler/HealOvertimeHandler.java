package net.sf.odinms.net.channel.handler;

import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.server.AutobanManager;
import net.sf.odinms.tools.FilePrinter;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class HealOvertimeHandler extends AbstractMaplePacketHandler {

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {   
        //c.getPlayer().resetAfkTime();
        int noChance = slea.readInt();
        if (noChance != 5120) {
            FilePrinter.printError(FilePrinter.ImproperHeal + c.getPlayer().getName() + ".txt", 
                    "Player didn't have 5120 for the first int. Probably cheating! \nMapID was " + c.getPlayer().getMapId() + "\nValue was " + noChance + "\n");
        }
        int healHP = slea.readShort();
        if (healHP != 0) {
            if (healHP > 1000) {
                AutobanManager.getInstance().autoban
                (c.getPlayer().getClient(), "[AUTOBAN]" + c.getPlayer().getName() + " healed for " + healHP + "/HP in map: " + c.getPlayer().getMapId() + ".");
                return;
            }
            c.getPlayer().addHP(healHP);
        }
        int healMP = slea.readShort();
        if (healMP != 0) {
            if (healMP > 1000) {
                AutobanManager.getInstance().autoban
                (c.getPlayer().getClient(), "[AUTOBAN]" + c.getPlayer().getName() + " healed for " + healMP + "/MP in map: " + c.getPlayer().getMapId() + ".");
                return;
            }
            c.getPlayer().addMP(healMP);
        }
        byte healByte = slea.readByte();
        //0 normal heal
        //1 ladder heal
        //2 chair heal
    }
}