package sim.ui;

import sim.floorplan.ui.FloorplanEditorPanel;   // ✅ NEW
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

public class MainFrame extends JFrame {
    private GlobalInputPanel    globalInputPanel;
    private FlightTablePanel    flightTablePanel;
    private TicketCounterPanel  ticketCounterPanel;
    private CheckpointPanel     checkpointPanel;
    private HoldRoomSetupPanel  holdRoomSetupPanel;

    // ✅ NEW (Step 6)
    private ArrivalCurveEditorPanel arrivalCurvePanel;

    // ✅ NEW (Milestone 4 integration)
    private FloorplanEditorPanel floorplanEditorPanel;
    private JButton validateFloorplanButton;

    private JButton startSimulationButton;
    private JTabbedPane tabs;

    public MainFrame() {
        super("Airport Setup");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // new stuff
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18)); // top,left,bottom,right
        setContentPane(root);

        initializeComponents();
        pack();
        setLocationRelativeTo(null);
    }

    private void initializeComponents() {
        globalInputPanel   = new GlobalInputPanel();
        flightTablePanel   = new FlightTablePanel();
        ticketCounterPanel = new TicketCounterPanel(flightTablePanel.getFlights());
        checkpointPanel    = new CheckpointPanel();
        holdRoomSetupPanel = new HoldRoomSetupPanel(flightTablePanel.getFlights());

        // ✅ NEW (Step 6)
        arrivalCurvePanel  = new ArrivalCurveEditorPanel(ArrivalCurveConfig.legacyDefault());

        // ✅ NEW (Milestone 4)
        floorplanEditorPanel = new FloorplanEditorPanel();

        // Buttons
        validateFloorplanButton = new JButton("Validate Floorplan");
        startSimulationButton = new JButton("Start Simulation");

        startSimulationButton.setForeground(Color.WHITE);
        startSimulationButton.setOpaque(true);
        startSimulationButton.setContentAreaFilled(true);

        // Start disabled until floorplan validated & locked
        startSimulationButton.setEnabled(false);

        validateFloorplanButton.addActionListener(e -> {
            // Jump user to floorplan tab (helpful if they clicked validate from another tab)
            if (tabs != null && floorplanEditorPanel != null) {
                tabs.setSelectedComponent(floorplanEditorPanel);
            }

            boolean ok = floorplanEditorPanel.validateAndLock();
            startSimulationButton.setEnabled(ok);
        });

        startSimulationButton.addActionListener(e -> onStartSimulation());

        // Layout
        add(globalInputPanel, BorderLayout.NORTH);

        tabs = new JTabbedPane();

        // ✅ Floorplan FIRST
        tabs.addTab("Floorplan", floorplanEditorPanel);

        // Existing tabs
        tabs.addTab("Flights", flightTablePanel);
        tabs.addTab("Ticket Counters", ticketCounterPanel);
        tabs.addTab("Checkpoints", checkpointPanel);
        tabs.addTab("Hold Rooms", holdRoomSetupPanel);
        tabs.addTab("Arrivals Curve", arrivalCurvePanel);

        add(tabs, BorderLayout.CENTER);

        // Bottom row: Validate + Start
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        bottom.add(validateFloorplanButton);
        bottom.add(startSimulationButton);
        add(bottom, BorderLayout.SOUTH);
    }

    private void onStartSimulation() {
        // Safety: if user unlocked after validating, don’t allow starting
        if (floorplanEditorPanel != null && !floorplanEditorPanel.isLocked()) {
            JOptionPane.showMessageDialog(this,
                    "Please validate & lock the floorplan before starting the simulation.\n\n" +
                            "Go to the Floorplan tab and click “Validate & Lock”.",
                    "Floorplan Not Validated",
                    JOptionPane.WARNING_MESSAGE);

            if (tabs != null) tabs.setSelectedComponent(floorplanEditorPanel);
            startSimulationButton.setEnabled(false);
            return;
        }

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

            // Hold-room delay is no longer a GlobalInputPanel control.
            // Prefer pulling it from the Hold Rooms tab if available; fallback safely.
            int holdDelay = resolveHoldDelayMinutes();

            List<Flight> flights = flightTablePanel.getFlights();

            // ✅ NEW (Step 6): pull curve config from UI
            ArrivalCurveConfig curveCfg = arrivalCurvePanel.getConfigCopy();
            curveCfg.validateAndClamp();

            // ✅ NEW (Step 6): effective arrival span
            int curveStart = curveCfg.isLegacyMode()
                    ? ArrivalCurveConfig.DEFAULT_WINDOW_START
                    : curveCfg.getWindowStartMinutesBeforeDeparture();

            int effectiveArrivalSpan = Math.max(baseArrivalSpan, curveStart);

            // build the pre-run engine for the data table (populate its history)
            SimulationEngine tableEngine = createEngine(
                    percentInPerson,
                    counters,
                    checkpoints,
                    effectiveArrivalSpan,
                    interval,
                    transitDelay,
                    holdDelay,
                    flights,
                    holdRooms
            );

            // ✅ NEW (Step 6): apply curve config BEFORE running
            tableEngine.setArrivalCurveConfig(curveCfg);
            tableEngine.runAllIntervals();

            // build the fresh engine for live animation
            SimulationEngine simEngine = createEngine(
                    percentInPerson,
                    counters,
                    checkpoints,
                    effectiveArrivalSpan,
                    interval,
                    transitDelay,
                    holdDelay,
                    flights,
                    holdRooms
            );

            // ✅ NEW (Step 6): apply curve config BEFORE showing UI
            simEngine.setArrivalCurveConfig(curveCfg);

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

    /**
     * Try to get hold-room delay from the Hold Rooms tab/panel, without hard-coding
     * a specific method name (so you don’t break if you renamed it).
     *
     * Falls back to 5 minutes if nothing is found (matching your old default).
     */
    private int resolveHoldDelayMinutes() {
        // 1) Try common method names on the setup panel
        Integer fromPanel = tryInvokeInt(holdRoomSetupPanel,
                "getHoldDelayMinutes",
                "getDefaultHoldDelayMinutes",
                "getHoldroomDelayMinutes",
                "getHoldRoomDelayMinutes",
                "getCheckpointToHoldDelayMinutes"
        );
        if (fromPanel != null && fromPanel >= 0) return fromPanel;

        // 2) Try to infer from HoldRoomConfig objects (if they store a delay)
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

                    // If they store seconds, convert to minutes (round up)
                    Integer sec = tryInvokeInt(cfg,
                            "getWalkSeconds",
                            "getCheckpointToHoldSeconds",
                            "getSecondsFromCheckpoint"
                    );
                    if (sec != null && sec > 0) {
                        return (sec + 59) / 60;
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore and fall back
        }

        // 3) Old default
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
            } catch (Exception ignored) {
                // try next name
            }
        }
        return null;
    }

    /**
     * Compatibility helper:
     * - Prefer NEW constructor that supports per-checkpoint configs AND hold rooms.
     * - Also supports variants that omit holdDelay (if you’ve moved delay into hold-room configs).
     * - Fall back to older constructors if needed (using average checkpoint rate).
     */
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

        // Preferred signature WITH holdDelay:
        // (double, List, List, int, int, int, int, List, List)
        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 9
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])   // counters
                    && List.class.isAssignableFrom(p[2])   // checkpoints
                    && p[3] == int.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && p[6] == int.class                  // holdDelay
                    && List.class.isAssignableFrom(p[7])   // flights
                    && List.class.isAssignableFrom(p[8]))  // holdRooms
            {
                return (SimulationEngine) c.newInstance(
                        percentInPerson,
                        counters,
                        checkpoints,
                        arrivalSpan,
                        interval,
                        transitDelay,
                        holdDelay,
                        flights,
                        holdRooms
                );
            }
        }

        // Preferred signature WITHOUT holdDelay:
        // (double, List, List, int, int, int, List, List)
        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 8
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])   // counters
                    && List.class.isAssignableFrom(p[2])   // checkpoints
                    && p[3] == int.class
                    && p[4] == int.class
                    && p[5] == int.class                  // transitDelay
                    && List.class.isAssignableFrom(p[6])   // flights
                    && List.class.isAssignableFrom(p[7]))  // holdRooms
            {
                return (SimulationEngine) c.newInstance(
                        percentInPerson,
                        counters,
                        checkpoints,
                        arrivalSpan,
                        interval,
                        transitDelay,
                        flights,
                        holdRooms
                );
            }
        }

        // Fallback: older constructor style using numCheckpoints + avgRatePerMin (and maybe holdDelay)
        int numCheckpoints = (checkpoints == null) ? 0 : checkpoints.size();
        double avgRatePerMin = 0.0;
        if (checkpoints != null && !checkpoints.isEmpty()) {
            double sum = 0.0;
            for (CheckpointConfig cfg : checkpoints) sum += cfg.getRatePerMinute();
            avgRatePerMin = sum / checkpoints.size();
        }

        // Older hold-rooms constructor WITH holdDelay:
        // (double, List, int, double, int, int, int, int, List, List)
        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 10
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && p[2] == int.class
                    && p[3] == double.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && p[6] == int.class
                    && p[7] == int.class
                    && List.class.isAssignableFrom(p[8])
                    && List.class.isAssignableFrom(p[9])) {

                return (SimulationEngine) c.newInstance(
                        percentInPerson,
                        counters,
                        numCheckpoints,
                        avgRatePerMin,
                        arrivalSpan,
                        interval,
                        transitDelay,
                        holdDelay,
                        flights,
                        holdRooms
                );
            }
        }

        // Older hold-rooms constructor WITHOUT holdDelay:
        // (double, List, int, double, int, int, int, List, List)
        for (Constructor<?> c : SimulationEngine.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 9
                    && p[0] == double.class
                    && List.class.isAssignableFrom(p[1])
                    && p[2] == int.class
                    && p[3] == double.class
                    && p[4] == int.class
                    && p[5] == int.class
                    && p[6] == int.class
                    && List.class.isAssignableFrom(p[7])
                    && List.class.isAssignableFrom(p[8])) {

                return (SimulationEngine) c.newInstance(
                        percentInPerson,
                        counters,
                        numCheckpoints,
                        avgRatePerMin,
                        arrivalSpan,
                        interval,
                        transitDelay,
                        flights,
                        holdRooms
                );
            }
        }

        // Final fallback: old constructor (no hold rooms)
        // (double, List, int, double, int, int, int, int, List)
        return new SimulationEngine(
                percentInPerson,
                counters,
                numCheckpoints,
                avgRatePerMin,
                arrivalSpan,
                interval,
                transitDelay,
                holdDelay,
                flights
        );
    }
}
