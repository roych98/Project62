package net.sf.odinms.server.maps;

import java.awt.Point;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.anticheat.CheatingOffense;
import net.sf.odinms.net.channel.ChannelServer;
import net.sf.odinms.scripting.portal.PortalScriptManager;
import net.sf.odinms.server.MaplePortal;
import net.sf.odinms.server.fourthjobquests.FourthJobQuestsPortalHandler;
import net.sf.odinms.tools.MaplePacketCreator;

public class MapleGenericPortal implements MaplePortal {

    private String name;
    private String target;
    private Point position;
    private int targetmap;
    private int type;
    private int id;
    private String scriptName;
    private boolean portalState;

    public MapleGenericPortal(int type) {
        this.type = type;
        portalState = OPEN;
    }

    @Override
    public void setPortalState(boolean state) {
        this.portalState = state;
    }

    @Override
    public boolean getPortalState() {
        return portalState;
    }

    @Override
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Point getPosition() {
        return position;
    }

    @Override
    public String getTarget() {
        return target;
    }

    @Override
    public int getTargetMapId() {
        return targetmap;
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public String getScriptName() {
        return scriptName;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setPosition(Point position) {
        this.position = position;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public void setTargetMapId(int targetmapid) {
        this.targetmap = targetmapid;
    }

    @Override
    public void setScriptName(String scriptName) {
        this.scriptName = scriptName;
    }

    @Override
    public void enterPortal(MapleClient c) {
        MapleCharacter player = c.getPlayer();
        double distanceSq = getPosition().distanceSq(player.getPosition());
        if (distanceSq > 22500) {
            player.getCheatTracker().registerOffense(CheatingOffense.USING_FARAWAY_PORTAL, "D" + Math.sqrt(distanceSq));
        }
        final MapleMap currentmap = c.getPlayer().getMap();
        if (getScriptName() != null) {
            if (!FourthJobQuestsPortalHandler.handlePortal(getScriptName(), c.getPlayer())) {
                try {
                    PortalScriptManager.getInstance().executePortalScript(this, c);
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        } else if (getTargetMapId() != 999999999) {
            MapleMap to;
            if (player.getEventInstance() == null) {
                to = ChannelServer.getInstance(c.getChannel()).getMapFactory().getMap(getTargetMapId());
            } else {
                to = player.getEventInstance().getMapInstance(getTargetMapId());
            }
            MaplePortal pto = to.getPortal(getTarget());
            if (pto == null) {
                pto = to.getPortal(0);
            }
            if(c.getPlayer().showportal) c.getPlayer().dropMessage("Registered an attempt to talk through PortalID: " + pto.getId() + " Portal Name: " + pto.getName());
            c.getPlayer().changeMap(to, pto);
        }
        if (c != null && c.getPlayer() != null && c.getPlayer().getMap() == currentmap) { // Character is still on the same map.
            c.getSession().write(MaplePacketCreator.enableActions());
        }
    }
}