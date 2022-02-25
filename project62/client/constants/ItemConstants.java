package net.sf.odinms.client.constants;

public class ItemConstants {

    
public static boolean isFinisherSkill(int skillId) {
    return skillId > 1111002 && skillId < 1111007 || skillId == 11111002 || skillId == 11111003;
}
public static boolean disallowedGMItems(int itemid){
    switch (itemid){
        case 2340000:
        case 2049100:
        return true;
    }
    return false;
}    
public static boolean trackedItems(int itemid){
    switch (itemid){
        case 2340000:
        case 2049100:
        case 2022179:
        case 5222000:
            return true;
    }
    return false;
}
public static boolean blockedNPCs(int npcid){
    switch (npcid){
        case 9330045:
        case 9270043:
        return true;
    }
    return false;
}
public static boolean isRechargable(int itemId) {
    return itemId / 10000 == 233 || itemId / 10000 == 207;
}
public static boolean blockedHWIDs(long hwid){
    if (hwid == 0 ||
            hwid == 3934314570L ||
            hwid == 3357730508L ||
            hwid == 546549769L ||
            hwid == 1780913768L ||
            hwid == 388511663L || 
            hwid == 88668814L || 
            hwid == 2958237392L || 
            hwid == 773810768 ||
            hwid == 4075859006L) {
        return true;
    }
    return false;
}
}