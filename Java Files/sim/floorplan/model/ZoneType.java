package sim.floorplan.model;

public enum ZoneType {
    SPAWN(true, false, "Spawn"),
    TICKET_COUNTER(true, false, "Ticket Counter"),
    CHECKPOINT(true, false, "Checkpoint"),
    HOLDROOM(true, false, "Holdroom"),

    TICKET_QUEUE_AREA(false, true, "Ticket Queue Area"),
    CHECKPOINT_QUEUE_AREA(false, true, "Checkpoint Queue Area"),
    HOLDROOM_AREA(false, true, "Holdroom Area");

    private final boolean hasAnchor;
    private final boolean hasArea;
    private final String label;

    ZoneType(boolean hasAnchor, boolean hasArea, String label) {
        this.hasAnchor = hasAnchor;
        this.hasArea = hasArea;
        this.label = label;
    }

    public boolean hasAnchor() { return hasAnchor; }
    public boolean hasArea() { return hasArea; }
    public String getLabel() { return label; }
}
