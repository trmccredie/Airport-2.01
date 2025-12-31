package sim.ui;

import sim.model.ArrivalCurveConfig;
import sim.model.Flight;
import sim.service.SimulationEngine;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

public class AirportSetupPanel extends JPanel {

    // old MainFrame fields
    private GlobalInputPanel    globalInputPanel;
    private FlightTablePanel    flightTablePanel;
    private TicketCounterPanel  ticketCounterPanel;
    private CheckpointPanel     checkpointPanel;
    private HoldRoomSetupPanel  holdRoomSetupPanel;
    private ArrivalCurveEditorPanel arrivalCurvePanel;

    private JButton startSimulationButton;

    // optional hook so AppFrame can react (enable analytics, etc.)
    public interface SimulationStartListener {
        void onSimulationStarted(SimulationEngine tableEngine, SimulationEngine simEngine);
    }
    private SimulationStartListener startListener;

    public void setSimulationStartListener(SimulationStartListener l) {
        this.startListener = l;
    }

    public AirportSetupPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
        initializeComponents();
    }

    private void initializeComponents() {
        globalInputPanel   = new GlobalInputPanel();
        flightTablePanel   = new FlightTablePanel();
        ticketCounterPanel = new TicketCounterPanel(flightTablePanel.getFlights());
        checkpointPanel    = new CheckpointPanel();
        holdRoomSetupPanel = new HoldRoomSetupPanel(flightTablePanel.getFlights());

        arrivalCurvePanel  = new ArrivalCurveEditorPanel(ArrivalCurveConfig.legacyDefault());

        startSimulationButton = new JButton("Start Simulation");
        startSimulationButton.setForeground(Color.WHITE);
        startSimulationButton.setOpaque(true);
        startSimulationButton.setContentAreaFilled(true);
        startSimulationButton.addActionListener(e -> onStartSimulation());

        add(globalInputPanel, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Flights", flightTablePanel);
        tabs.addTab("Ticket Counters", ticketCounterPanel);
        tabs.addTab("Checkpoints", checkpointPanel);
        tabs.addTab("Hold Rooms", holdRoomSetupPanel);
        tabs.addTab("Arrivals Curve", arrivalCurvePanel);

        add(tabs, BorderLayout.CENTER);
        add(startSimulationButton, BorderLayout.SOUTH);
    }

    private void onStartSimulation() {
        if (flightTablePanel.getFlights().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one flight before starting simulation.",
                    "No Flights Defined",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<TicketCounterConfig> counters = ticketCounterPanel.getCounters();
        if (counters.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one ticket counter before starting simulation.",
                    "No Counters Defined",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<CheckpointConfig> checkpoints = checkpointPanel.getCheckpoints();
        if (checkpoints.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one checkpoint before starting simulation.",
                    "No Checkpoints Defined",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<HoldRoomConfig> holdRooms = holdRoomSetupPanel.getHoldRooms();
        if (holdRooms.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please add at least one hold room before starting simulation.",
                    "No Hold Rooms Defined",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            double percentInPerson = globalInputPanel.getPercentInPerson();
            if (percentInPerson < 0 || percentInPerson > 1) {
                throw new IllegalArgumentException("Percent in person must be between 0 and 1");
            }

            int baseArrivalSpan  = globalInputPanel.getArrivalSpanMinutes();
            int interval         = globalInputPanel.getIntervalMinutes();
            int transitDelay     = globalInputPanel.getTransitDelayMinutes();

            int holdDelay = resolveHoldDelayMinutes();

            List<Flight> flights = flightTablePanel.getFlights();

            ArrivalCurveConfig curveCfg = arrivalCurvePanel.getConfigCopy();
            curveCfg.validateAndClamp();

            int curveStart = curveCfg.isLegacyMode()
                    ? ArrivalCurveConfig.DEFAULT_WINDOW_START
                    : curveCfg.getWindowStartMinutesBeforeDeparture();

            int effectiveArrivalSpan = Math.max(baseArrivalSpan, curveStart);

            SimulationEngine tableEngine = createEngine(
                    percentInPerson, counters, checkpoints,
                    effectiveArrivalSpan, interval, transitDelay, holdDelay,
                    flights, holdRooms
            );
            tableEngine.setArrivalCurveConfig(curveCfg);
            tableEngine.runAllIntervals();

            SimulationEngine simEngine = createEngine(
                    percentInPerson, counters, checkpoints,
                    effectiveArrivalSpan, interval, transitDelay, holdDelay,
                    flights, holdRooms
            );
            simEngine.setArrivalCurveConfig(curveCfg);

            if (startListener != null) startListener.onSimulationStarted(tableEngine, simEngine);

            new DataTableFrame(tableEngine).setVisible(true);
            new SimulationFrame(simEngine).setVisible(true);

        } catch (Exception ex) {
            ex.printStackTrace();
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            JTextArea area = new JTextArea(sw.toString(), 20, 60);
            area.setEditable(false);
            JOptionPane.showMessageDialog(this,
                    new JScrollPane(area),
                    "Simulation Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private int resolveHoldDelayMinutes() {
        Integer fromPanel = tryInvokeInt(holdRoomSetupPanel,
                "getHoldDelayMinutes",
                "getDefaultHoldDelayMinutes",
                "getHoldroomDelayMinutes",
                "getHoldRoomDelayMinutes",
                "getCheckpointToHoldDelayMinutes"
        );
        if (fromPanel != null && fromPanel >= 0) return fromPanel;

        try {
            List<HoldRoomConfig> rooms = holdRoomSetupPanel.getHoldRooms();
            if (rooms != null && !rooms.isEmpty()) {
                for (HoldRoomConfig cfg : rooms) {
                    Integer v = tryInvokeInt(cfg,
                            "getHoldDelayMinutes",
                            "getDelayMinutes",
                            "getHoldroomDelayMinutes",
                            "getHoldRoomDelayMinutes",
                            "getCheckpointToHoldDelayMinutes"
                    );
                    if (v != null && v >= 0) return v;

                    Integer sec = tryInvokeInt(cfg,
                            "getWalkSeconds",
                            "getCheckpointToHoldSeconds",
                            "getSecondsFromCheckpoint"
                    );
                    if (sec != null && sec > 0) return (sec + 59) / 60;
                }
            }
        } catch (Exception ignored) {}

        return 5;
    }

    private Integer tryInvokeInt(Object target, String... methodNames) {
        if (target == null) return null;
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                Class<?> rt = m.getReturnType();
                if (rt == int.class || rt == Integer.class) {
                    Object out = m.invoke(target);
                    return (out == null) ? null : ((Number) out).intValue();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private SimulationEngine createEngine(
            double percentInPerson,
            List<TicketCounterConfig> counters,
            List<CheckpointConfig> checkpoints,
            int arrivalSpan,
            int interval,
            int transitDelay,
            int holdDelay,
            List<Flight> flights,
            List<HoldRoomConfig> holdRooms
    ) throws Exception {

        // Prefer signature WITH holdDelay:
        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 9
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && List.class.isAssignableFrom(p[2])
                    && p[3] == int.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && p[6] == int.class
                    && List.class.isAssignableFrom(p[7])
                    && List.class.isAssignableFrom(p[8])) {
                return (SimulationEngine) c.newInstance(
                        percentInPerson, counters, checkpoints,
                        arrivalSpan, interval, transitDelay, holdDelay,
                        flights, holdRooms
                );
            }
        }

        // Signature WITHOUT holdDelay:
        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 8
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && List.class.isAssignableFrom(p[2])
                    && p[3] == int.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && List.class.isAssignableFrom(p[6])
                    && List.class.isAssignableFrom(p[7])) {
                return (SimulationEngine) c.newInstance(
                        percentInPerson, counters, checkpoints,
                        arrivalSpan, interval, transitDelay,
                        flights, holdRooms
                );
            }
        }

        throw new IllegalStateException("No compatible SimulationEngine constructor found.");
    }
}
