package com.dlnahub.dlna.model;

import java.util.List;

public class BrowseResult {

    private final List<BrowsableItem> items;
    private final int total;
    private final int index;
    private final int count;
    private final String updateId;

    public BrowseResult(List<BrowsableItem> items, int total, int index, int count, String updateId) {
        this.items = items;
        this.total = total;
        this.index = index;
        this.count = count;
        this.updateId = updateId;
    }

    public List<BrowsableItem> getItems() { return items; }
    public int getTotal() { return total; }
    public int getIndex() { return index; }
    public int getCount() { return count; }
    public String getUpdateId() { return updateId; }
}
