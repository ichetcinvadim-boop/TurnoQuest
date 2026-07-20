package pro.turnoworld.quests;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class QuestInventoryHolder implements InventoryHolder {
    public enum View { MAIN, CHAPTER }
    private final View view;
    private final int chapter;
    private Inventory inventory;

    public QuestInventoryHolder(View view, int chapter) { this.view = view; this.chapter = chapter; }
    public View view() { return view; }
    public int chapter() { return chapter; }
    void inventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}
