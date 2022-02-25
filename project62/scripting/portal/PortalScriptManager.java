package net.sf.odinms.scripting.portal;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.server.MaplePortal;
import net.sf.odinms.tools.FilePrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortalScriptManager {
    private static final Logger log = LoggerFactory.getLogger(PortalScriptManager.class);
    private static PortalScriptManager instance = new PortalScriptManager();

    private Map<String, PortalScript> scripts = new HashMap<String, PortalScript>();
    private ScriptEngineFactory sef;

    private PortalScriptManager() {
        ScriptEngineManager sem = new ScriptEngineManager();
        sef = sem.getEngineByName("javascript").getFactory();
    }

    public static PortalScriptManager getInstance() {
        return instance;
    }

    private PortalScript getPortalScript(String scriptName) {
        if (scripts.containsKey(scriptName)) {
            return scripts.get(scriptName);
        }
        File scriptFile = new File("scripts/portal/" + scriptName + ".js");
        if (!scriptFile.exists()) {
            FilePrinter.printError(FilePrinter.Portal + scriptName + ".txt", "Missing script for portal " + scriptName + ".\n");
            scripts.put(scriptName, null);
            return null;
        }
        if (scriptFile == null) {
            FilePrinter.printError(FilePrinter.Portal + scriptName + ".txt", "Missing script for portal " + scriptName + ".\n");
        }
        FileReader fr = null;
        ScriptEngine portal = sef.getScriptEngine();
        try {
            fr = new FileReader(scriptFile);
            CompiledScript compiled = ((Compilable) portal).compile(fr);
            compiled.eval();
        } catch (ScriptException e) {
            log.error("THROW", e);
        } catch (IOException e) {
            log.error("THROW", e);
        } finally {
            if (fr != null) {
                try {
                    fr.close();
                } catch (IOException e) {
                    log.error("ERROR CLOSING", e);
                }
            }
        }
        PortalScript script = ((Invocable) portal).getInterface(PortalScript.class);
        scripts.put(scriptName, script);
        return script;
    }

    // Rhino is thread safe so this should be fine without synchronisation.
    public final void executePortalScript(final MaplePortal portal, final MapleClient c) {
        PortalScript script = getPortalScript(portal.getScriptName());
        if (script != null) {
            try {
                script.enter(new PortalPlayerInteraction(c, portal));
            } catch (Exception e) {
                System.err.println("Error entering Portalscript: " + portal.getScriptName() + " : " + e.getCause());
                FilePrinter.printError(FilePrinter.Portal + script + ".txt", "Error entering Portalscript: " + portal.getScriptName() + " : " + e.getCause() + "\n");
            }
        } else {
            FilePrinter.printError(FilePrinter.Portal + script + ".txt", "Unhandled portal script " + portal.getScriptName() + " on map " + c.getPlayer().getMapId() + "\n");
        }
    }

    public void clearScripts() {
        scripts.clear();
    }
}