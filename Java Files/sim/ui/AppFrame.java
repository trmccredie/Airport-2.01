package sim.ui;

import sim.floorplan.ui.FloorplanEditorPanel;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class AppFrame extends JFrame {

    private final JTabbedPane tabs = new JTabbedPane();

    private final FloorplanEditorPanel floorplanEditor = new FloorplanEditorPanel();
    private final AirportSetupPanel setupPanel = new AirportSetupPanel();

    private final JPanel analyticsPanel = new JPanel(new BorderLayout());
    private final JLabel analyticsStatus = new JLabel("Run a simulation to enable analytics.", SwingConstants.CENTER);

    public AppFrame() {
        super("AirportSim — Floorplan + Simulation");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Floorplan tab with an unlock button
        JPanel floorplanTab = new JPanel(new BorderLayout());
        floorplanTab.add(floorplanEditor, BorderLayout.CENTER);

        JButton validateUnlockBtn = new JButton("Validate & Unlock Simulation");
        validateUnlockBtn.addActionListener(e -> tryUnlockSimulation());

        JPanel southBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        southBar.add(validateUnlockBtn);
        floorplanTab.add(southBar, BorderLayout.SOUTH);

        // Simulation tab (setup UI for now)
        JPanel simulationTab = new JPanel(new BorderLayout());
        simulationTab.add(setupPanel, BorderLayout.CENTER);

        // Analytics tab (placeholder now; enabled once sim is started)
        analyticsPanel.add(analyticsStatus, BorderLayout.CENTER);

        tabs.addTab("Floorplan Editor", floorplanTab);
        tabs.addTab("Simulation", simulationTab);
        tabs.addTab("Analytics", analyticsPanel);

        // Gate tabs
        tabs.setEnabledAt(1, false);
        tabs.setEnabledAt(2, false);

        // When simulation starts, enable Analytics
        setupPanel.setSimulationStartListener((tableEngine, simEngine) -> {
            tabs.setEnabledAt(2, true);
            analyticsStatus.setText("<html><div style='text-align:center;'>Analytics enabled.<br/>" +
                    "For now, analytics are still shown inside the Simulation window & Data Table window.<br/>" +
                    "Next step: we will embed those panels directly into this Analytics tab.</div></html>");
        });

        add(tabs, BorderLayout.CENTER);

        setSize(1100, 850);
        setLocationRelativeTo(null);
    }

    private void tryUnlockSimulation() {
        List<String> errors = floorplanEditor.validateProject();
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    String.join("\n", errors),
                    "Floorplan Validation Failed",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        tabs.setEnabledAt(1, true);
        tabs.setSelectedIndex(1);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new AppFrame().setVisible(true));
    }
}
