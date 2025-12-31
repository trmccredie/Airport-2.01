// HoldRoomSetupPanel.java
package sim.ui;

import sim.model.Flight;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Panel for adding/removing PHYSICAL hold rooms with per-room walk time
 * and flight restrictions (empty selection = accepts ALL flights).
 *
 * Mirrors TicketCounterPanel UX.
 */
public class HoldRoomSetupPanel extends JPanel {
    private final JTable table;
    private final HoldRoomTableModel model;
    private final JButton addBtn;
    private final JButton removeBtn;

    public HoldRoomSetupPanel(List<Flight> flights) {
        super(new BorderLayout());

        model = new HoldRoomTableModel(flights);
        table = new JTable(model);

        // Column 3 (Available Flights) uses your existing checkbox dialog editor
        table.getColumnModel().getColumn(3)
                .setCellEditor(new AvailableFlightsCellEditor(flights));

        add(new JScrollPane(table), BorderLayout.CENTER);

        addBtn = new JButton("Add Hold Room");
        removeBtn = new JButton("Remove Hold Room");

        addBtn.addActionListener(e -> model.addHoldRoom());
        removeBtn.addActionListener(e -> {
            int sel = table.getSelectedRow();
            if (sel >= 0) model.removeHoldRoom(sel);
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        btnPanel.add(addBtn);
        btnPanel.add(removeBtn);
        add(btnPanel, BorderLayout.SOUTH);
    }

    /** Returns the user-configured physical hold rooms. */
    public List<HoldRoomConfig> getHoldRooms() {
        return model.getHoldRooms();
    }
}
