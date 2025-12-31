package sim.ui;

import sim.model.Flight;
import sim.model.Passenger;
import sim.service.SimulationEngine;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import java.awt.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SimulationFrame extends JFrame {
    private final JLabel            timeLabel;
    private final LocalTime         startTime;
    private final DateTimeFormatter TIME_FMT     = DateTimeFormatter.ofPattern("HH:mm");

    private final JButton           autoRunBtn;
    private final JButton           pausePlayBtn;
    private final JButton           summaryBtn;
    private final JSlider           speedSlider;

    // remove final so it can be referenced in lambdas before assignment without "definite assignment" errors
    private javax.swing.Timer       autoRunTimer;

    private       boolean           isPaused    = false;

    // Rewind + scrub controls
    private final JButton           prevBtn;
    private final JSlider           timelineSlider;
    private final JLabel            intervalLabel;

    // Guard: prevents programmatic timelineSlider.setValue() from triggering scrub logic
    private boolean                 timelineProgrammaticUpdate = false;

    // Arrivals graph tab (strongly typed so we can call syncWithEngine/setViewedInterval)
    private final ArrivalsGraphPanel arrivalsGraphPanel;

    // Queue totals graph tab (ticket vs checkpoint vs hold rooms)
    private final QueueTotalsGraphPanel queueTotalsGraphPanel;

    // Hold room per-room population tab
    private final HoldRoomPopulationGraphPanel holdRoomPopulationGraphPanel;

    // NEW: arrival curve inspector tab (actual curve used by the engine)
    private final ArrivalCurveUsedPanel arrivalCurveUsedPanel;

    // track, for each flight, the interval index at which it closed
    private final Map<Flight,Integer> closeSteps = new LinkedHashMap<>();

    // track whether we have finished at least once (enables Summary permanently)
    private boolean simulationCompleted = false;

    /**
     * LEGACY Convenience constructor (still supported):
     * - numCheckpoints + checkpointRate are still accepted
     * - checkpointRate should now be interpreted as PASSENGERS/HOUR (industry standard),
     *   and SimulationEngine will convert it to per-interval internally.
     */
    public SimulationFrame(double percentInPerson,
                           List<TicketCounterConfig> counterConfigs,
                           int numCheckpoints,
                           double checkpointRate,
                           int arrivalSpanMinutes,
                           int intervalMinutes,
                           int transitDelayMinutes,
                           int holdDelayMinutes,
                           List<Flight> flights) {
        this(buildEngineWithHoldRooms(
                percentInPerson,
                counterConfigs,
                numCheckpoints,
                checkpointRate,
                arrivalSpanMinutes,
                intervalMinutes,
                transitDelayMinutes,
                holdDelayMinutes,
                flights
        ));
    }

    /**
     * NEW Convenience constructor:
     * - Per-checkpoint rates (passengers/hour) come from the CheckpointPanel tab.
     * - SimulationEngine converts passengers/hour -> per-interval internally.
     */
    public SimulationFrame(double percentInPerson,
                           List<TicketCounterConfig> counterConfigs,
                           List<CheckpointConfig> checkpointConfigs,
                           int arrivalSpanMinutes,
                           int intervalMinutes,
                           int transitDelayMinutes,
                           int holdDelayMinutes,
                           List<Flight> flights) {
        this(buildEngineWithHoldRooms(
                percentInPerson,
                counterConfigs,
                checkpointConfigs,
                arrivalSpanMinutes,
                intervalMinutes,
                transitDelayMinutes,
                holdDelayMinutes,
                flights
        ));
    }

    private static SimulationEngine buildEngineWithHoldRooms(double percentInPerson,
                                                             List<TicketCounterConfig> counterConfigs,
                                                             int numCheckpoints,
                                                             double checkpointRate,
                                                             int arrivalSpanMinutes,
                                                             int intervalMinutes,
                                                             int transitDelayMinutes,
                                                             int holdDelayMinutes,
                                                             List<Flight> flights) {
        List<HoldRoomConfig> holdRooms = buildDefaultHoldRoomConfigs(flights, holdDelayMinutes);

        // NOTE: checkpointRate is now "passengers/hour" at the UI level.
        // SimulationEngine handles conversion per-interval internally.
        return new SimulationEngine(
                percentInPerson,
                counterConfigs,
                numCheckpoints,
                checkpointRate,
                arrivalSpanMinutes,
                intervalMinutes,
                transitDelayMinutes,
                holdDelayMinutes,
                flights,
                holdRooms
        );
    }

    private static SimulationEngine buildEngineWithHoldRooms(double percentInPerson,
                                                             List<TicketCounterConfig> counterConfigs,
                                                             List<CheckpointConfig> checkpointConfigs,
                                                             int arrivalSpanMinutes,
                                                             int intervalMinutes,
                                                             int transitDelayMinutes,
                                                             int holdDelayMinutes,
                                                             List<Flight> flights) {
        List<HoldRoomConfig> holdRooms = buildDefaultHoldRoomConfigs(flights, holdDelayMinutes);

        // NEW: pass checkpointConfigs directly (per-checkpoint passengers/hour)
        return new SimulationEngine(
                percentInPerson,
                counterConfigs,
                checkpointConfigs,
                arrivalSpanMinutes,
                intervalMinutes,
                transitDelayMinutes,
                holdDelayMinutes,
                flights,
                holdRooms
        );
    }

    /**
     * Default hold-room configs:
     * - one HoldRoomConfig per flight
     * - best-effort sets walk-time to holdDelayMinutes
     * - best-effort restricts each room to ONLY its matching flight (prevents random assignment)
     */
    private static List<HoldRoomConfig> buildDefaultHoldRoomConfigs(List<Flight> flights, int holdDelayMinutes) {
        List<HoldRoomConfig> list = new ArrayList<>();
        int n = (flights == null) ? 0 : flights.size();

        for (int i = 0; i < n; i++) {
            Flight f = flights.get(i);

            HoldRoomConfig cfg = tryInstantiateHoldRoomConfig("Hold Room " + (i + 1));
            if (cfg != null) {
                bestEffortSetWalkTime(cfg, holdDelayMinutes, 0);
                bestEffortAssignSingleFlight(cfg, f);
            }
            list.add(cfg);
        }
        return list;
    }

    private static HoldRoomConfig tryInstantiateHoldRoomConfig(String name) {
        try {
            // Prefer (String) constructor if present
            Constructor<HoldRoomConfig> c = HoldRoomConfig.class.getConstructor(String.class);
            return c.newInstance(name);
        } catch (Exception ignored) { }

        try {
            // Fallback: no-arg constructor
            Constructor<HoldRoomConfig> c0 = HoldRoomConfig.class.getConstructor();
            HoldRoomConfig cfg = c0.newInstance();
            // Best-effort setName(String)
            bestEffortInvoke(cfg, "setName", new Class<?>[]{String.class}, new Object[]{name});
            return cfg;
        } catch (Exception ignored) { }

        // If neither constructor exists, return null placeholder (engine will still run)
        return null;
    }

    private static void bestEffortSetWalkTime(HoldRoomConfig cfg, int minutes, int seconds) {
        if (cfg == null) return;

        if (bestEffortInvoke(cfg, "setWalkTime", new Class<?>[]{int.class, int.class}, new Object[]{minutes, seconds})) return;
        if (bestEffortInvoke(cfg, "setWalkMinutesSeconds", new Class<?>[]{int.class, int.class}, new Object[]{minutes, seconds})) return;
        if (bestEffortInvoke(cfg, "setTravelTime", new Class<?>[]{int.class, int.class}, new Object[]{minutes, seconds})) return;

        int totalSeconds = Math.max(0, minutes) * 60 + Math.max(0, seconds);
        bestEffortInvoke(cfg, "setWalkSeconds", new Class<?>[]{int.class}, new Object[]{totalSeconds});
    }

    /**
     * IMPORTANT: Without this, your helper-created default rooms often "accept all flights",
     * which can cause chosenHoldRoomIndexByFlight to assign flights to random rooms.
     *
     * This method tries multiple possible API shapes across your HoldRoomConfig versions.
     */
    private static void bestEffortAssignSingleFlight(HoldRoomConfig cfg, Flight f) {
        if (cfg == null || f == null) return;

        // Try setAllowedFlights(Collection/ List / Set)
        if (bestEffortInvoke(cfg, "setAllowedFlights", new Class<?>[]{java.util.Collection.class},
                new Object[]{Collections.singletonList(f)})) return;
        if (bestEffortInvoke(cfg, "setAllowedFlights", new Class<?>[]{java.util.List.class},
                new Object[]{Collections.singletonList(f)})) return;
        if (bestEffortInvoke(cfg, "setAllowedFlights", new Class<?>[]{java.util.Set.class},
                new Object[]{Collections.singleton(f)})) return;

        // Try addAllowedFlight(Flight)
        if (bestEffortInvoke(cfg, "addAllowedFlight", new Class<?>[]{Flight.class},
                new Object[]{f})) return;

        // Try setAllowedFlightNumbers(Collection<String>)
        bestEffortInvoke(cfg, "setAllowedFlightNumbers", new Class<?>[]{java.util.Collection.class},
                new Object[]{Collections.singletonList(f.getFlightNumber())});

        // Try addAllowedFlightNumber(String)
        bestEffortInvoke(cfg, "addAllowedFlightNumber", new Class<?>[]{String.class},
                new Object[]{f.getFlightNumber()});
    }

    private static boolean bestEffortInvoke(Object target, String methodName, Class<?>[] sig, Object[] args) {
        if (target == null) return false;
        try {
            Method m = target.getClass().getMethod(methodName, sig);
            m.invoke(target, args);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ==========================================================
    // EXISTING constructor (simulation view)
    // ==========================================================
    public SimulationFrame(SimulationEngine engine) {
        super("Simulation View");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        LocalTime firstDep = engine.getFlights().stream()
                .map(Flight::getDepartureTime)
                .min(LocalTime::compareTo)
                .orElse(LocalTime.MIDNIGHT);
        startTime = firstDep.minusMinutes(engine.getArrivalSpan());

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.X_AXIS));

        JPanel legendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        legendPanel.setBorder(BorderFactory.createTitledBorder("Legend"));
        for (Flight f : engine.getFlights()) {
            legendPanel.add(new JLabel(f.getShape().name() + " = " + f.getFlightNumber()));
        }
        topPanel.add(legendPanel);

        topPanel.add(Box.createHorizontalGlue());

        timeLabel = new JLabel(startTime.format(TIME_FMT));
        timeLabel.setFont(timeLabel.getFont().deriveFont(Font.BOLD, 16f));
        timeLabel.setBorder(BorderFactory.createTitledBorder("Current Time"));
        timeLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel timePanel = new JPanel();
        timePanel.setLayout(new BorderLayout());
        timePanel.setPreferredSize(new Dimension(180, 50));
        timePanel.add(timeLabel, BorderLayout.CENTER);

        timePanel.setPreferredSize(new Dimension(180, 50));
        timePanel.setMaximumSize(new Dimension(180, 50));

        topPanel.add(timePanel);
        topPanel.add(Box.createRigidArea(new Dimension(20, 0)));

        add(topPanel, BorderLayout.NORTH);

        JPanel split = new JPanel();
        split.setLayout(new BoxLayout(split, BoxLayout.X_AXIS));
        int cellW   = 60 / 3, boxSize = 60, gutter = 30, padding = 100;
        int queuedW = GridRenderer.COLS * cellW,
                servedW = GridRenderer.COLS * cellW,
                panelW  = queuedW + boxSize + servedW + padding;

        TicketLinesPanel ticketPanel = new TicketLinesPanel(
                engine, new ArrayList<>(), new ArrayList<>(), null
        );
        Dimension tPref = ticketPanel.getPreferredSize();
        ticketPanel.setPreferredSize(new Dimension(panelW, tPref.height));
        ticketPanel.setMinimumSize(ticketPanel.getPreferredSize());
        ticketPanel.setMaximumSize(ticketPanel.getPreferredSize());
        split.add(Box.createHorizontalStrut(gutter));
        split.add(ticketPanel);

        split.add(Box.createHorizontalStrut(gutter));
        CheckpointLinesPanel cpPanel = new CheckpointLinesPanel(
                engine, new ArrayList<>(), new ArrayList<>(), null
        );
        Dimension cPref = cpPanel.getPreferredSize();
        cpPanel.setPreferredSize(new Dimension(panelW, cPref.height));
        cpPanel.setMinimumSize(cpPanel.getPreferredSize());
        cpPanel.setMaximumSize(cpPanel.getPreferredSize());
        split.add(cpPanel);

        split.add(Box.createHorizontalStrut(gutter));
        HoldRoomsPanel holdPanel = new HoldRoomsPanel(
                engine, new ArrayList<>(), new ArrayList<>(), null
        );
        split.add(holdPanel);

        JScrollPane centerScroll = new JScrollPane(
                split,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS
        );

        JPanel control = new JPanel();
        control.setLayout(new BoxLayout(control, BoxLayout.Y_AXIS));
        control.setPreferredSize(new Dimension(800, 300));
        control.setMinimumSize(new Dimension(0, 220));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centerScroll, control);
        mainSplit.setResizeWeight(0.72);
        mainSplit.setContinuousLayout(true);
        mainSplit.setOneTouchExpandable(true);

        add(mainSplit, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> mainSplit.setDividerLocation(0.55));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        prevBtn = new JButton("Prev Interval");
        btnPanel.add(prevBtn);

        JButton nextBtn = new JButton("Next Interval");
        btnPanel.add(nextBtn);

        autoRunBtn   = new JButton("AutoRun");
        pausePlayBtn = new JButton("Pause");
        summaryBtn   = new JButton("Summary");

        summaryBtn.setEnabled(false);
        pausePlayBtn.setVisible(false);

        btnPanel.add(autoRunBtn);
        btnPanel.add(pausePlayBtn);

        JButton graphBtn = new JButton("Show Graph");
        graphBtn.addActionListener(e -> {
            Map<Integer, Integer> heldUps = engine.getHoldUpsByInterval();
            new GraphWindow("Passenger Hold-Ups by Interval", heldUps).setVisible(true);
        });
        btnPanel.add(graphBtn);

        btnPanel.add(summaryBtn);
        control.add(btnPanel);

        JPanel timelineAndGraphContainer = new JPanel(new BorderLayout(8, 6));
        timelineAndGraphContainer.setBorder(
                BorderFactory.createTitledBorder("Timeline (rewind / review computed intervals)")
        );

        JTabbedPane tabs = new JTabbedPane();

        JPanel timelineTab = new JPanel(new BorderLayout(8, 4));

        intervalLabel = new JLabel();
        intervalLabel.setPreferredSize(new Dimension(260, 20));
        intervalLabel.setHorizontalAlignment(SwingConstants.LEFT);

        timelineSlider = new JSlider(0, Math.max(0, engine.getMaxComputedInterval()), 0);
        timelineSlider.setPaintTicks(true);
        timelineSlider.setPaintLabels(true);
        timelineSlider.setMajorTickSpacing(10);
        timelineSlider.setMinorTickSpacing(1);

        rebuildTimelineLabels(timelineSlider);

        timelineTab.add(intervalLabel, BorderLayout.NORTH);
        timelineTab.add(timelineSlider, BorderLayout.CENTER);

        tabs.addTab("Timeline", timelineTab);

        arrivalsGraphPanel = new ArrivalsGraphPanel(engine);
        JPanel arrivalsTab = new JPanel(new BorderLayout());
        arrivalsTab.add(arrivalsGraphPanel, BorderLayout.CENTER);
        tabs.addTab("Arrivals", arrivalsTab);

        queueTotalsGraphPanel = new QueueTotalsGraphPanel(engine);
        JPanel queueTotalsTab = new JPanel(new BorderLayout());
        queueTotalsTab.add(queueTotalsGraphPanel, BorderLayout.CENTER);
        tabs.addTab("Queues", queueTotalsTab);

        holdRoomPopulationGraphPanel = new HoldRoomPopulationGraphPanel(engine);
        JPanel holdRoomsTab = new JPanel(new BorderLayout());
        holdRoomsTab.add(holdRoomPopulationGraphPanel, BorderLayout.CENTER);
        tabs.addTab("Hold Rooms", holdRoomsTab);

        // NEW: actual curve used by engine (per-flight dropdown, with viewed-interval marker)
        arrivalCurveUsedPanel = new ArrivalCurveUsedPanel(engine);
        JPanel curveTab = new JPanel(new BorderLayout());
        curveTab.add(arrivalCurveUsedPanel, BorderLayout.CENTER);
        tabs.addTab("Curve (Used)", curveTab);

        timelineAndGraphContainer.add(tabs, BorderLayout.CENTER);
        control.add(timelineAndGraphContainer);

        JPanel sliderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sliderPanel.setBorder(BorderFactory.createTitledBorder(
                "AutoRun Speed (ms per interval)"
        ));
        speedSlider = new JSlider(100, 2000, 1000);
        speedSlider.setMajorTickSpacing(500);
        speedSlider.setMinorTickSpacing(100);
        speedSlider.setPaintTicks(true);
        speedSlider.setPaintLabels(true);
        Hashtable<Integer,JLabel> labels = new Hashtable<>();
        labels.put(100,  new JLabel("0.1s"));
        labels.put(500,  new JLabel("0.5s"));
        labels.put(1000, new JLabel("1s"));
        labels.put(1500, new JLabel("1.5s"));
        labels.put(2000, new JLabel("2s"));
        speedSlider.setLabelTable(labels);
        sliderPanel.add(speedSlider);
        control.add(sliderPanel);

        summaryBtn.addActionListener(e ->
                new FlightsSummaryFrame(engine).setVisible(true)
        );

        Runnable refreshUI = () -> {
            LocalTime now = startTime.plusMinutes(engine.getCurrentInterval());
            timeLabel.setText(now.format(TIME_FMT));
            split.repaint();

            int maxComputed = engine.getMaxComputedInterval();

            timelineProgrammaticUpdate = true;
            try {
                if (timelineSlider.getMaximum() != maxComputed) {
                    timelineSlider.setMaximum(maxComputed);
                    int major = computeMajorTickSpacing(maxComputed);
                    timelineSlider.setMajorTickSpacing(major);
                    timelineSlider.setMinorTickSpacing(1);
                    rebuildTimelineLabels(timelineSlider);
                }

                int ci = engine.getCurrentInterval();
                if (ci <= timelineSlider.getMaximum()) timelineSlider.setValue(ci);
                else timelineSlider.setValue(timelineSlider.getMaximum());
            } finally {
                timelineProgrammaticUpdate = false;
            }

            intervalLabel.setText("Interval: " + engine.getCurrentInterval()
                    + " / " + engine.getTotalIntervals());

            arrivalsGraphPanel.syncWithEngine();

            // NEW: keep curve inspector synced
            arrivalCurveUsedPanel.setViewedInterval(engine.getCurrentInterval());
            arrivalCurveUsedPanel.syncWithEngine();

            queueTotalsGraphPanel.setMaxComputedInterval(maxComputed);
            queueTotalsGraphPanel.setTotalIntervals(engine.getTotalIntervals());
            queueTotalsGraphPanel.setCurrentInterval(engine.getCurrentInterval());

            holdRoomPopulationGraphPanel.setMaxComputedInterval(maxComputed);
            holdRoomPopulationGraphPanel.setTotalIntervals(engine.getTotalIntervals());
            holdRoomPopulationGraphPanel.setCurrentInterval(engine.getCurrentInterval());
            holdRoomPopulationGraphPanel.syncWithEngine();

            prevBtn.setEnabled(engine.canRewind());

            boolean canAdvance = engine.getCurrentInterval() < engine.getTotalIntervals();
            nextBtn.setEnabled(canAdvance);

            if (autoRunTimer == null || !autoRunTimer.isRunning()) {
                autoRunBtn.setEnabled(canAdvance);
            }

            if (simulationCompleted) {
                summaryBtn.setEnabled(true);
            }
        };

        java.util.function.Consumer<List<Flight>> handleClosures = (closed) -> {
            if (closed == null || closed.isEmpty()) return;

            int step = engine.getCurrentInterval() - 1;

            List<Flight> newlyClosed = new ArrayList<>();
            for (Flight f : closed) {
                if (!closeSteps.containsKey(f)) {
                    closeSteps.put(f, step);
                    newlyClosed.add(f);
                }
            }

            if (newlyClosed.isEmpty()) return;

            if (autoRunTimer != null && autoRunTimer.isRunning()) {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
                isPaused = true;
            }

            for (Flight f : newlyClosed) {
                int total = (int)Math.round(f.getSeats() * f.getFillPercent());

                int made = 0;
                for (java.util.LinkedList<Passenger> room : engine.getHoldRoomLines()) {
                    for (Passenger p : room) {
                        if (p != null && p.getFlight() == f) made++;
                    }
                }

                JOptionPane.showMessageDialog(
                        SimulationFrame.this,
                        String.format("%s: %d of %d made their flight.",
                                f.getFlightNumber(), made, total),
                        "Flight Closed",
                        JOptionPane.INFORMATION_MESSAGE
                );
            }
        };

        autoRunTimer = new javax.swing.Timer(speedSlider.getValue(), ev -> {
            javax.swing.Timer t = (javax.swing.Timer)ev.getSource();
            if (engine.getCurrentInterval() < engine.getTotalIntervals()) {
                engine.computeNextInterval();
                refreshUI.run();

                List<Flight> closed = engine.getFlightsJustClosed();
                handleClosures.accept(closed);

                if (engine.getCurrentInterval() >= engine.getTotalIntervals()) {
                    simulationCompleted = true;
                    t.stop();
                    autoRunBtn.setEnabled(false);
                    pausePlayBtn.setEnabled(false);
                    summaryBtn.setEnabled(true);
                }
            }
        });

        speedSlider.addChangeListener((ChangeEvent e) -> {
            if (autoRunTimer != null) {
                autoRunTimer.setDelay(speedSlider.getValue());
            }
        });

        prevBtn.addActionListener(ev -> {
            if (autoRunTimer != null && autoRunTimer.isRunning()) {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
                isPaused = true;
            }
            engine.rewindOneInterval();
            refreshUI.run();
        });

        nextBtn.addActionListener(ev -> {
            engine.computeNextInterval();
            refreshUI.run();

            List<Flight> closed = engine.getFlightsJustClosed();
            handleClosures.accept(closed);

            if (engine.getCurrentInterval() >= engine.getTotalIntervals()) {
                simulationCompleted = true;
                nextBtn.setEnabled(false);
                autoRunBtn.setEnabled(false);
                summaryBtn.setEnabled(true);
            }
        });

        timelineSlider.addChangeListener((ChangeEvent e) -> {
            if (timelineProgrammaticUpdate) return;

            if (timelineSlider.getValueIsAdjusting()) {
                intervalLabel.setText("Interval: " + timelineSlider.getValue()
                        + " / " + engine.getTotalIntervals());

                int v = timelineSlider.getValue();
                arrivalsGraphPanel.setViewedInterval(v);
                queueTotalsGraphPanel.setCurrentInterval(v);
                holdRoomPopulationGraphPanel.setViewedInterval(v);

                // NEW: curve inspector follows scrub
                arrivalCurveUsedPanel.setViewedInterval(v);

                return;
            }

            int target = timelineSlider.getValue();

            if (autoRunTimer != null && autoRunTimer.isRunning()) {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
                isPaused = true;
            }

            engine.goToInterval(target);
            refreshUI.run();
        });

        autoRunBtn.addActionListener(e -> {
            autoRunBtn.setEnabled(false);
            pausePlayBtn.setVisible(true);

            pausePlayBtn.setText("Pause");
            isPaused = false;

            if (autoRunTimer != null) {
                autoRunTimer.start();
            }
        });

        pausePlayBtn.addActionListener(e -> {
            if (autoRunTimer == null) return;

            if (isPaused) {
                autoRunTimer.start();
                pausePlayBtn.setText("Pause");
            } else {
                autoRunTimer.stop();
                pausePlayBtn.setText("Play");
            }
            isPaused = !isPaused;
            refreshUI.run();
        });

        refreshUI.run();

        setSize(900, 900);
        setLocationRelativeTo(null);
    }

    private static int computeMajorTickSpacing(int maxIntervals) {
        if (maxIntervals >= 1000) return 500;
        if (maxIntervals >= 500)  return 100;
        if (maxIntervals >= 150)  return 50;
        if (maxIntervals >= 100)  return 20;

        if (maxIntervals >= 50) return 10;
        if (maxIntervals >= 20) return 5;
        return 1;
    }

    private static void rebuildTimelineLabels(JSlider slider) {
        int max = slider.getMaximum();
        int major = slider.getMajorTickSpacing();
        if (major <= 0) major = 1;

        Hashtable<Integer, JLabel> table = new Hashtable<>();

        table.put(0, new JLabel("0"));

        for (int v = major; v < max; v += major) {
            table.put(v, new JLabel(String.valueOf(v)));
        }

        if (max != 0) {
            int lastMajor = (max / major) * major;
            if (lastMajor == max) {
                table.put(max, new JLabel(String.valueOf(max)));
            } else {
                if (lastMajor > 0 && (max - lastMajor) < (major / 2)) {
                    table.remove(lastMajor);
                }
                table.put(max, new JLabel(String.valueOf(max)));
            }
        }

        slider.setLabelTable(table);
        slider.setPaintLabels(true);
        slider.setPaintTicks(true);
        slider.repaint();
    }
}
