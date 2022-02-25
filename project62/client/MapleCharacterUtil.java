package net.sf.odinms.client;

public class MapleCharacterUtil {
 private static final int[] mapleIds = {0, 1, 2, 3, 10000, 20000, 20001, 30000, 40000, 40001, 40002, 50000, 50001, 60000, 60001, 1000000, 1000001, 1000002, 1000003, 1000004, 1000005, 1000006, 1010000, 1010001, 1010002, 1010003, 1010004, 1020000, 1020001};

    private MapleCharacterUtil() {
    }
    public static boolean isMapleIsland(int mapid) {
        boolean isTrue = false;
        for (int ids : mapleIds) {
            if (mapid == ids) {
                isTrue = true;
            }
        }
        return isTrue;
    }
    public static boolean canCreateChar(String name, int world) {
        return isNameLegal(name) && MapleCharacter.getIdByName(name, world) < 0;
    }

    public static boolean isNameLegal(String name) {
        if (name.length() < 3 || name.length() > 12) {
            return false;
        }
        return java.util.regex.Pattern.compile("[a-zA-Z0-9_-]{3,12}").matcher(name).matches();
    }

    public static boolean hasSymbols(String name) {
        String[] symbols = {"`","~","!","@","#","$","%","^","&","*","(",")","_","-","=","+","{","[","]","}","|",";",":","'",",","<",">",".","?","/"};
        for (byte s = 0; s < symbols.length; s++) {
            if (name.contains(symbols[s])) {
                return true;
            }
        }
        return false;
    }

    public static String makeMapleReadable(String in) {
        String wui = in.replace('I', 'i');
        wui = wui.replace('l', 'L');
        wui = wui.replace("rn", "Rn");
        wui = wui.replace("vv", "Vv");
        wui = wui.replace("VV", "Vv");
        return wui;
    }
}