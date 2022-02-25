package net.sf.odinms.scripting.quest;

import java.util.HashMap;
import java.util.Map;
import javax.script.Invocable;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.scripting.AbstractScriptManager;
import net.sf.odinms.tools.FilePrinter;

public class QuestScriptManager extends AbstractScriptManager {

    private Map<MapleClient,QuestActionManager> qms = new HashMap<MapleClient,QuestActionManager>();
    private Map<MapleClient,QuestScript> scripts = new HashMap<MapleClient,QuestScript>();
    private static QuestScriptManager instance = new QuestScriptManager();

    public synchronized static QuestScriptManager getInstance() {
        return instance;
    }

    public void start(MapleClient c, int npc, int quest) {
        try {
            QuestActionManager qm = new QuestActionManager(c, npc, quest, true);
            if (qms.containsKey(c)) {
                return;
            }
            qms.put(c, qm);
            Invocable iv = getInvocable("quest/" + quest + ".js", c);
            if (iv == null) {
                FilePrinter.printError(FilePrinter.Quest + quest + "start.txt", "Quest is uncoded for start. NPC with quest is " + npc + ".\n");
                qm.dispose();
                return;
            }
            engine.put("qm", qm);
            QuestScript qs = iv.getInterface(QuestScript.class);
            scripts.put(c, qs);
            qs.start((byte)1, (byte)0, 0);
        } catch (Exception e) {
            FilePrinter.printError(FilePrinter.Quest + quest + "ExceptionStart1.txt", "Error executing Quest Script " + quest + ".\nException thrown was " + e.getCause() + "\n");
            dispose(c);
        }
    }

    public void start(MapleClient c, byte mode, byte type, int selection) {
        QuestScript qs = scripts.get(c);
        if (qs != null) {
            try {
                qs.start(mode, type, selection);
            } catch (Exception e) {
                FilePrinter.printError(FilePrinter.Quest + c.getQM().getQuest() + "ExceptionStart2.txt", "Error executing Quest Script " + c.getQM().getQuest() + ".\nException thrown was " + e.getCause()  + "\n");
                dispose(c);
            }
        }
    }

    public void end(MapleClient c, int npc, int quest) {
        try {
            QuestActionManager qm = new QuestActionManager(c, npc, quest, false);
            if (qms.containsKey(c)) {
                return;
            }
            qms.put(c, qm);
            Invocable iv = getInvocable("quest/" + quest + ".js", c);
            if (iv == null) {
                FilePrinter.printError(FilePrinter.Quest + quest + "end.txt", "Quest is uncoded for end. NPC with quest is " + npc + ".\n");
                qm.dispose();
                return;
            }
            engine.put("qm", qm);
            QuestScript qs = iv.getInterface(QuestScript.class);
            scripts.put(c, qs);
            qs.end((byte) 1, (byte) 0, 0);
        } catch (Exception e) {
            FilePrinter.printError(FilePrinter.Quest + quest + "Exception1End.txt", "Error executing Quest Script " + quest + ".\nException thrown was " + e.getCause() + "\n");
            dispose(c);
        }
    }

    public void end(MapleClient c, byte mode, byte type, int selection) {
        QuestScript qs = scripts.get(c);
        if (qs != null) {
            try {
                qs.end(mode, type, selection);
            } catch (Exception e) {
                FilePrinter.printError(FilePrinter.Quest + c.getQM().getQuest() + "Exception1End.txt", "Error executing Quest Script " + c.getQM().getQuest() + ".\nException thrown was " + e.getCause() + "\n");
                dispose(c);
            }
        }
    }

    public void dispose(QuestActionManager qm, MapleClient c) {
        qms.remove(c);
        scripts.remove(c);
        resetContext("quest/" + qm.getQuest() + ".js", c);
    }

    public void dispose(MapleClient c) {
        QuestActionManager qm = qms.get(c);
        if (qm != null) {
            dispose(qm, c);
        }
    }

    public QuestActionManager getQM(MapleClient c) {
        return qms.get(c);
    }
}