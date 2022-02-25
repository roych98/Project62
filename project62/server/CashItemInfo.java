package net.sf.odinms.server;

public class CashItemInfo {
    private int itemId;
    private int count;
    private int price;
    private boolean onSale;

    public CashItemInfo(int itemId, int count, int price, boolean onSale) {
        this.itemId = itemId;
        this.count = count;
        this.price = price;
        this.onSale = onSale;
    }

    public int getId() {
        return itemId;
    }
    
    public boolean isOnSale() {
            return onSale;
      }

    public int getCount() {
        return count;
    }

    public int getPrice() {
        return price;
    }
}