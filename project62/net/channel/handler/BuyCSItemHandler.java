package net.sf.odinms.net.channel.handler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import net.sf.odinms.client.MapleCharacter;
import net.sf.odinms.client.MapleClient;
import net.sf.odinms.client.MapleInventoryType;
import net.sf.odinms.client.MaplePet;
import net.sf.odinms.client.MapleRing;
import net.sf.odinms.database.DatabaseConnection;
import net.sf.odinms.net.AbstractMaplePacketHandler;
import net.sf.odinms.server.AutobanManager;
import net.sf.odinms.server.CashItemFactory;
import net.sf.odinms.server.CashItemInfo;
import net.sf.odinms.server.MapleInventoryManipulator;
import net.sf.odinms.tools.MaplePacketCreator;
import net.sf.odinms.tools.data.input.SeekableLittleEndianAccessor;

public class BuyCSItemHandler extends AbstractMaplePacketHandler {

    private void updateInformation(MapleClient c, int item) {
        CashItemInfo Item = CashItemFactory.getItem(item);
        c.getSession().write(MaplePacketCreator.showBoughtCSItem(Item.getId()));
        updateInformation(c);
    }

    private void updateInformation(MapleClient c) {
        c.getSession().write(MaplePacketCreator.showNXMapleTokens(c.getPlayer()));
        c.getSession().write(MaplePacketCreator.enableCSUse0());
        c.getSession().write(MaplePacketCreator.enableCSUse1());
        c.getSession().write(MaplePacketCreator.enableCSUse2());
        c.getSession().write(MaplePacketCreator.enableCSUse3());
        c.getSession().write(MaplePacketCreator.enableActions());
    }

    @Override
    public void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        int action = slea.readByte();
        if (action == 3) {
            slea.skip(1);
            int useNX = slea.readInt();
            int snCS = slea.readInt();
            CashItemInfo item = CashItemFactory.getItem(snCS);
            int itemID = item.getId();
            // C6 00 03 00 03 00 00 00 21 b4 c4 04 00 00 00 00 -- exploit
            if(!c.getPlayer().inCS()) {
                AutobanManager.getInstance().autoban(c, "Player has tried to buy in cash shop, when not in cash shop.");
                System.out.println("Tried to exploit cash shop");
                c.getSession().write(MaplePacketCreator.enableActions());
                c.getSession().close();
                return;
            }
            if(itemID == 2022282 || itemID == 2022002) {
                AutobanManager.getInstance().autoban(c, "Tried to exploit cash shop, itemid: " + itemID + " item price: " + item.getPrice());
                System.out.println("Tried to exploit cash shop, itemid: " + itemID + " item price: " + item.getPrice());
                c.getSession().write(MaplePacketCreator.enableActions());
                c.getSession().close();
                return;
            }
            if(itemID == 1812006 || itemID >= 5211004 && itemID <= 5211008 || itemID >= 5211009 && itemID <= 5211013 || itemID >= 5211014 && itemID <= 5211018 || itemID >= 5211037 && itemID <= 5211049 || itemID == 5140000 || isRing(itemID)){
                c.getSession().write(MaplePacketCreator.enableActions());
                c.getPlayer().dropMessage(1, "You may not purchase this item."); 
                return;
            }
            if (itemID/1000000 == 5 && !c.getPlayer().canHold(5000000)) {
                c.getSession().write(MaplePacketCreator.enableActions());
                c.getPlayer().dropMessage(1, "You seem to be lacking slots for these items, clear some stuff out. "); 
                return;
            }
            if (itemID/1000000 == 4 && !c.getPlayer().canHold(4000000)) {
                c.getSession().write(MaplePacketCreator.enableActions());
                c.getPlayer().dropMessage(1, "You seem to be lacking slots for these items, clear some stuff out. "); 
                return;
            }
            if (itemID/1000000 == 3 && !c.getPlayer().canHold(3000000)) {
                c.getSession().write(MaplePacketCreator.enableActions());
                c.getPlayer().dropMessage(1, "You seem to be lacking slots for these items, clear some stuff out. "); 
                return;
            }
            if (itemID/1000000 == 2 && !c.getPlayer().canHold(2000000)) {
                c.getSession().write(MaplePacketCreator.enableActions());
                c.getPlayer().dropMessage(1, "You seem to be lacking slots for these items, clear some stuff out. "); 
                return;
            }
            if (itemID/1000000 == 1 && !c.getPlayer().canHold(1000000)) {
                c.getSession().write(MaplePacketCreator.enableActions());
                c.getPlayer().dropMessage(1, "You seem to be lacking slots for these items, clear some stuff out. "); 
                return;
            }
            if(item.getPrice() < 0) {
                AutobanManager.getInstance().autoban(c, "Suspected to exploit cash shop, call roy. [Negative Price]");
                c.getPlayer().dropMessage(1, "A message was sent to staff, exploit err cs1");
                return;
            }
            if (c.getPlayer().getCSPoints(useNX) >= item.getPrice()) {
                c.getPlayer().modifyCSPoints(useNX, -item.getPrice());
            } else {
                c.getSession().write(MaplePacketCreator.enableActions());
                AutobanManager.getInstance().autoban(c, "Trying to purchase from the CS when they have no NX.");
                return;
            }
            if (itemID >= 5000000 && itemID <= 5000100) {
                int petId = MaplePet.createPet(itemID);
                if (petId == -1) {
                    c.getSession().write(MaplePacketCreator.enableActions());
                    return;
                }
                MapleInventoryManipulator.addById(c, itemID, (short) 1, null, petId);
            } else {
                MapleInventoryManipulator.addById(c, itemID, (short) item.getCount());
            }
            updateInformation(c, snCS);
        } else if (action == 5) {
            try {
                Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("DELETE FROM wishlist WHERE charid = ?");
                ps.setInt(1, c.getPlayer().getId());
                ps.executeUpdate();
                ps.close();

                int i = 10;
                while (i > 0) {
                    int sn = slea.readInt();
                    if (sn != 0) {
                        ps = con.prepareStatement("INSERT INTO wishlist(charid, sn) VALUES(?, ?) ");
                        ps.setInt(1, c.getPlayer().getId());
                        ps.setInt(2, sn);
                        ps.executeUpdate();
                        ps.close();
                    }
                    i--;
                }
            } catch (SQLException se) {
            }
            c.getSession().write(MaplePacketCreator.sendWishList(c.getPlayer().getId(), true));
        } else if (action == 7) {
            slea.skip(1);
            byte toCharge = slea.readByte();
            int toIncrease = slea.readInt();
            if (c.getPlayer().getCSPoints(toCharge) >= 4000 && c.getPlayer().getStorage().getSlots() < 48) {
                c.getPlayer().modifyCSPoints(toCharge, -4000);
                if (toIncrease == 0) {
                    c.getPlayer().getStorage().gainSlots(4);
                }
                updateInformation(c);
            }
        } else if (action == 28) { // Package
            slea.skip(1);
            int useNX = slea.readInt();
            int snCS = slea.readInt();
            CashItemInfo item = CashItemFactory.getItem(snCS);
            if (c.getPlayer().getCSPoints(useNX) >= item.getPrice()) {
                c.getPlayer().modifyCSPoints(useNX, -item.getPrice());
            } else {
                c.getSession().write(MaplePacketCreator.enableActions());
                AutobanManager.getInstance().autoban(c, "Trying to purchase from the CS when they have no NX.");
                return;
            }
            for (int i : CashItemFactory.getPackageItems(item.getId())) {
                if (i >= 5000000 && i <= 5000100) {
                    int petId = MaplePet.createPet(i);
                    if (petId == -1) {
                        c.getSession().write(MaplePacketCreator.enableActions());
                        return;
                    }
                    MapleInventoryManipulator.addById(c, i, (short) 1, null, petId);
                } else {
                    MapleInventoryManipulator.addById(c, i, (short) item.getCount());
                }
            }
            updateInformation(c, snCS);
        } else if (action == 30) {
            int snCS = slea.readInt();
            CashItemInfo item = CashItemFactory.getItem(snCS);
            if (c.getPlayer().getMeso() >= item.getPrice()) {
                c.getPlayer().gainMeso(-item.getPrice(), false);
                MapleInventoryManipulator.addById(c, item.getId(), (short) item.getCount());
            } else {
                c.getSession().write(MaplePacketCreator.enableActions());
                AutobanManager.getInstance().autoban(c, "Trying to purchase from the CS with an insufficient amount.");
                return;
            }
        } else if (action == 27 || action == 33) {
            if(true) {
                c.getSession().write(MaplePacketCreator.enableActions());
                c.getPlayer().dropMessage(1, "You may not purchase this item."); 
                return;
            }
            int birthdate = slea.readInt();
            int toCharge = slea.readInt();
            int SN = slea.readInt();
            String recipient = slea.readMapleAsciiString();
            String text = slea.readMapleAsciiString();
            CashItemInfo ring = CashItemFactory.getItem(SN);
            if(c.getPlayer().getCSPoints(1) < ring.getPrice()) {
                c.disconnect();
                return;
            }
            MapleCharacter partnerChar = c.getChannelServer().getPlayerStorage().getCharacterByName(recipient);
            if (partnerChar == null) {
                c.getPlayer().getClient().getSession().write(MaplePacketCreator.serverNotice(1, "The partner specified could not be found. Please make sure your partner is online and in the same channel."));
                c.getPlayer().getClient().getSession().write(MaplePacketCreator.enableActions());
                return;
            }
                c.getPlayer().modifyCSPoints(toCharge, -ring.getPrice());
                MapleRing.createRing(ring.getId(), c.getPlayer(), partnerChar);
                c.getPlayer().saveToDB(true, true);
                partnerChar.saveToDB(true, true);
                c.getPlayer().getClient().getSession().write(MaplePacketCreator.serverNotice(1, "Successfully created a ring for you and your partner!\\r\\nIf you can not see the effect, try re-logging in."));
            }
        }
    private boolean isRing(int itemid) {
        return (itemid == 1112001 || itemid == 1112002 || itemid == 1112003 || itemid == 1112005 || itemid == 1112006 || itemid == 1112800 || itemid == 1112801 || itemid == 1112802);
    }
}