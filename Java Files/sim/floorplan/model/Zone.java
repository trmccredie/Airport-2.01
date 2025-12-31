package sim.floorplan.model;

import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.io.Serializable;

public class Zone implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private ZoneType type;

    // either anchor or area (or both later)
    private Point anchor;
    private Polygon area;

    public Zone() {}

    public Zone(String id, ZoneType type) {
        this.id = id;
        this.type = type;
    }

    public static Zone anchorZone(String id, ZoneType type, Point anchor) {
        Zone z = new Zone(id, type);
        z.anchor = (anchor == null) ? null : new Point(anchor);
        return z;
    }

    public static Zone areaZone(String id, ZoneType type, Polygon area) {
        Zone z = new Zone(id, type);
        z.area = copyPolygon(area);
        return z;
    }

    public Zone copy() {
        Zone z = new Zone(id, type);
        z.anchor = (anchor == null) ? null : new Point(anchor);
        z.area = copyPolygon(area);
        return z;
    }

    private static Polygon copyPolygon(Polygon p) {
        if (p == null) return null;
        return new Polygon(p.xpoints.clone(), p.ypoints.clone(), p.npoints);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public ZoneType getType() { return type; }
    public void setType(ZoneType type) { this.type = type; }

    public Point getAnchor() { return anchor; }
    public void setAnchor(Point anchor) { this.anchor = (anchor == null) ? null : new Point(anchor); }

    public Polygon getArea() { return area; }
    public void setArea(Polygon area) { this.area = copyPolygon(area); }

    // ==========================
    // Milestone 4 helpers
    // ==========================

    public boolean isComplete() {
        if (type == null) return false;

        if (type.hasAnchor()) {
            if (anchor == null) return false;
        }
        if (type.hasArea()) {
            if (area == null || area.npoints < 3) return false;
        }
        return true;
    }

    public Rectangle getBounds() {
        if (area != null && area.npoints >= 3) return area.getBounds();
        if (anchor != null) return new Rectangle(anchor.x, anchor.y, 1, 1);
        return new Rectangle(0, 0, 0, 0);
    }

    public boolean containsPoint(Point p) {
        if (p == null) return false;
        if (area != null && area.npoints >= 3) {
            Rectangle b = area.getBounds();
            if (!b.contains(p)) return false;
            return area.contains(p.x + 0.5, p.y + 0.5);
        }
        return false;
    }

    @Override
    public String toString() {
        return (type == null ? "Zone" : type.getLabel()) + (id == null ? "" : (" (" + id + ")"));
    }
}
