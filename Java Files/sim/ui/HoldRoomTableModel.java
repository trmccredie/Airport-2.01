package sim.ui;

import sim.model.Flight;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Table model for configuring PHYSICAL hold rooms:
 * Columns: [Room #, Walk Min, Walk Sec, Available Flights]
 *
 * Note: HoldRoomConfig stores allowed flights as flightNumber Strings.
 * The editor returns Set<Flight>, so we convert via cfg.setAllowedFlights(...).
 */
public class HoldRoomTableModel extends AbstractTableModel {
    private final String[] columns = { "Room #", "Walk Min", "Walk Sec", "Available Flights" };

    private final List<HoldRoomConfig> rooms = new ArrayList<>();
    private final List<Flight> flights;

    public HoldRoomTableModel(List<Flight> flights) {
        this.flights = flights;
    }

    @Override
    public int getRowCount() {
        return rooms.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int col) {
        return columns[col];
    }

    @Override
    public Class<?> getColumnClass(int col) {
        switch (col) {
            case 0: return Integer.class; // id
            case 1: return Integer.class; // walk min
            case 2: return Integer.class; // walk sec
            case 3: return String.class;  // display string (editor returns Set<Flight>)
            default: return Object.class;
        }
    }

    @Override
    public Object getValueAt(int row, int col) {
        HoldRoomConfig cfg = rooms.get(row);
        switch (col) {
            case 0: return cfg.getId();
            case 1: return cfg.getWalkMinutes();
            case 2: return cfg.getWalkSecondsPart();
            case 3:
                // show "All" when no restrictions
                if (cfg.getAllowedFlightNumbers().isEmpty()) return "All";
                return String.join(", ", cfg.getAllowedFlightNumbers());
            default:
                return null;
        }
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        // walk min/sec and flight selection are editable
        return col == 1 || col == 2 || col == 3;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setValueAt(Object val, int row, int col) {
        HoldRoomConfig cfg = rooms.get(row);

        switch (col) {
            case 1: { // minutes
                int m = toNonNegInt(val);
                int s = cfg.getWalkSecondsPart();
                cfg.setWalkTime(m, s);
                break;
            }
            case 2: { // seconds (0..59)
                int s = toNonNegInt(val);
                s = Math.max(0, Math.min(59, s));
                int m = cfg.getWalkMinutes();
                cfg.setWalkTime(m, s);
                break;
            }
            case 3: { // flights from editor
                if (val instanceof Set) {
                    Set<Flight> selected = (Set<Flight>) val;

                    // Empty selection means "accept ALL flights" (your config rule)
                    if (selected == null || selected.isEmpty()) {
                        cfg.clearAllowedFlights();
                    } else {
                        cfg.setAllowedFlights(selected);
                    }
                }
                break;
            }
            default:
                return;
        }

        fireTableCellUpdated(row, col);
    }

    /** Adds a new hold room with default walk time 0:00 and "All flights". */
    public void addHoldRoom() {
        int id = rooms.size() + 1;
        rooms.add(new HoldRoomConfig(id, 0));
        fireTableRowsInserted(rooms.size() - 1, rooms.size() - 1);
    }

    /**
     * Removes a hold room and renumbers IDs to remain 1..N.
     * HoldRoomConfig.id is final, so we recreate configs to renumber safely.
     */
    public void removeHoldRoom(int idx) {
        if (idx < 0 || idx >= rooms.size()) return;

        rooms.remove(idx);

        // rebuild with sequential IDs
        List<HoldRoomConfig> rebuilt = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) {
            HoldRoomConfig old = rooms.get(i);
            HoldRoomConfig cfg = new HoldRoomConfig(i + 1, old.getWalkSecondsFromCheckpoint());
            cfg.setAllowedFlightNumbers(old.getAllowedFlightNumbers());
            rebuilt.add(cfg);
        }
        rooms.clear();
        rooms.addAll(rebuilt);

        fireTableDataChanged();
    }

    /** Returns the user-configured hold rooms. */
    public List<HoldRoomConfig> getHoldRooms() {
        return new ArrayList<>(rooms);
    }

    private static int toNonNegInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Integer) return Math.max(0, (Integer) val);
        if (val instanceof Number) return Math.max(0, ((Number) val).intValue());
        try {
            return Math.max(0, Integer.parseInt(val.toString().trim()));
        } catch (Exception e) {
            return 0;
        }
    }
}
