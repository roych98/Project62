package net.sf.odinms.net.channel.handler.npcHandlers;

import net.sf.odinms.client.MapleClient;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.scripting.npc.NPCConversationManager;
import net.sf.odinms.scripting.npc.NPCScriptManager;
import net.sf.odinms.scripting.quest.QuestScriptManager;
import net.sf.odinms.tools.FilePrinter;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class MoreTalkHandler extends AbstractMaplePacketHandler {

    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        if (c.getPlayer().getLastSelectNPCTime()+100 > System.currentTimeMillis() ){
            c.getSession().write(MaplePacketCreator.enableActions());
            return;
        }
        c.getPlayer().setLastSelectNPCTime(System.currentTimeMillis());
        c.getPlayer().resetAfkTime();
        byte lastMsg = slea.readByte(); // Last message type.
        byte action = slea.readByte(); // 00 = end chat, 01 == follow
        final NPCConversationManager cm = NPCScriptManager.getInstance().getCM(c);
        if (lastMsg == 2) {
            if (action != 0) {
                String returnText = slea.readMapleAsciiString();
                if (c.getQM() != null) {
                    c.getQM().setGetText(returnText);
                    if (c.getQM().isStart()) {
                        QuestScriptManager.getInstance().start(c, action, lastMsg, -1);
                    } else {
                        QuestScriptManager.getInstance().end(c, action, lastMsg, -1);
                    }
                } else {
                    c.getCM().setGetText(returnText);
                    NPCScriptManager.getInstance().action(c, action, lastMsg, -1);
                }
            } else {
                if (c.getQM() != null) {
                    c.getQM().dispose();
                } else {
                    c.getCM().dispose();
                }
            }
        } else {
            int selection = -1;
            if (slea.available() >= 4) {
                selection = slea.readInt();
            } else if (slea.available() > 0) {
                selection = slea.readByte();
            }
            if (lastMsg == 4 && selection == -1) {
                FilePrinter.printError(FilePrinter.MoreTalkLog + c.getPlayer().getName() + ".txt", "[MoreTalkLog]"+c.getPlayer().getName() +  
                " seems to be trying to exploit NPCs in MAPID " + c.getPlayer().getMapId() + " on NPC " + c.getCM()
                + "\r\nPacket sent was " + lastMsg + " " + action + " " +  selection + " \r\n");
                cm.dispose();
                return;//dirty dirty item generation makers
            }
            if (c.getQM() != null) {
                if (c.getQM().isStart()) {
                    QuestScriptManager.getInstance().start(c, action, lastMsg, selection);
                } else {
                    QuestScriptManager.getInstance().end(c, action, lastMsg, selection);
                }
            } else if (c.getCM() != null) {
                NPCScriptManager.getInstance().action(c, action, lastMsg, selection);
            }
        }
    }
}