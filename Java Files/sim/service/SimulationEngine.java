package sim.service;

import sim.model.ArrivalCurveConfig;
import sim.model.Flight;
import sim.model.Passenger;
import sim.service.arrivals.ArrivalCurveGenerator;
import sim.service.arrivals.EditedSplitGaussianArrivalGenerator;
import sim.ui.CheckpointConfig;
import sim.ui.GridRenderer;
import sim.ui.TicketCounterConfig;
import sim.ui.HoldRoomConfig;

import java.time.Duration;
import java.time.LocalTime;
import java.util.*;

public class SimulationEngine {
    private final List<Flight> flights;

    // ============================
    // Hold-room configs (physical rooms)
    // ============================
    private final List<HoldRoomConfig> holdRoomConfigs;

    // Precomputed: exactly ONE chosen physical room per flight
    private final Map<Flight, Integer> chosenHoldRoomIndexByFlight = new HashMap<>();

    // Existing held-ups series
    private final Map<Integer, Integer> heldUpsByInterval = new LinkedHashMap<>();

    // NEW: queue totals series (waiting lines only)
    private final Map<Integer, Integer> ticketQueuedByInterval = new LinkedHashMap<>();
    private final Map<Integer, Integer> checkpointQueuedByInterval = new LinkedHashMap<>();
    private final Map<Integer, Integer> holdRoomTotalByInterval = new LinkedHashMap<>();

    // ============================
    // ✅ Arrival curve support (Step 6)
    // ============================
    private ArrivalCurveConfig arrivalCurveConfig = ArrivalCurveConfig.legacyDefault();

    // Legacy minute generator = EXACT existing logic (your current ArrivalGenerator)
    private final ArrivalGenerator legacyMinuteGenerator;

    // Edited curve generator (split Gaussian)
    private final ArrivalCurveGenerator editedMinuteGenerator = new EditedSplitGaussianArrivalGenerator();

    // Used by DataTableModel/DataTableFrame
    private final Map<Flight, int[]> minuteArrivalsMap = new HashMap<>();

    private final Map<Flight, Integer> holdRoomCellSize;

    private final int arrivalSpanMinutes;
    private final int intervalMinutes;
    private final int transitDelayMinutes;    // ticket→checkpoint delay
    private final int holdDelayMinutes;       // legacy global delay (kept for compatibility / defaults)
    private final int totalIntervals;

    // simulation clock (minutes since globalStart)
    private int currentInterval;

    private final double percentInPerson;

    // Ticket counters:
    // IMPORTANT: TicketCounterConfig.getRate() is stored as passengers/minute (your table model converts hr<->min)
    private final List<TicketCounterConfig> counterConfigs;

    // Checkpoints:
    // IMPORTANT: CheckpointConfig stores passengers/hour (industry input)
    private final List<CheckpointConfig> checkpointConfigs;
    private final int numCheckpoints;

    // Legacy checkpointRate parameter is interpreted as passengers/hour (used only when building defaults)
    private final double defaultCheckpointRatePerHour;

    private final LocalTime globalStart;
    private final List<Flight> justClosedFlights = new ArrayList<>();
    private final Set<Passenger> ticketCompletedVisible = new HashSet<>();

    private final List<LinkedList<Passenger>> ticketLines;
    private final List<LinkedList<Passenger>> checkpointLines;
    private final List<LinkedList<Passenger>> completedTicketLines;
    private final List<LinkedList<Passenger>> completedCheckpointLines;

    // per-flight counts (kept)
    private final List<Map<Flight, Integer>> historyArrivals = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyEnqueuedTicket = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyTicketed = new ArrayList<>();
    private final List<Integer> historyTicketLineSize = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyArrivedToCheckpoint = new ArrayList<>();
    private final List<Integer> historyCPLineSize = new ArrayList<>();
    private final List<Map<Flight, Integer>> historyPassedCheckpoint = new ArrayList<>();
    private final List<List<List<Passenger>>> historyOnlineArrivals = new ArrayList<>();
    private final List<List<List<Passenger>>> historyFromTicketArrivals = new ArrayList<>();

    // the hold-room queues (PHYSICAL ROOMS)
    private final List<LinkedList<Passenger>> holdRoomLines;

    // histories for the UI panels
    private final List<List<List<Passenger>>> historyServedTicket = new ArrayList<>();
    private final List<List<List<Passenger>>> historyQueuedTicket = new ArrayList<>();
    private final List<List<List<Passenger>>> historyServedCheckpoint = new ArrayList<>();
    private final List<List<List<Passenger>>> historyQueuedCheckpoint = new ArrayList<>();
    private final List<List<List<Passenger>>> historyHoldRooms = new ArrayList<>();

    private final Random rand = new Random();

    private double[] counterProgress;
    private double[] checkpointProgress;
    private final Map<Integer, List<Passenger>> pendingToCP;
    private final Map<Integer, List<Passenger>> pendingToHold;
    private Passenger[] counterServing;
    private Passenger[] checkpointServing;

    // ============================
    // PHASES 0–3: REWIND SUPPORT
    // ============================

    private final List<EngineSnapshot> stateSnapshots = new ArrayList<>();
    private int maxComputedInterval = 0;

    private static final class EngineSnapshot {
        final int currentInterval;

        final List<LinkedList<Passenger>> ticketLines;
        final List<LinkedList<Passenger>> completedTicketLines;
        final List<LinkedList<Passenger>> checkpointLines;
        final List<LinkedList<Passenger>> completedCheckpointLines;
        final List<LinkedList<Passenger>> holdRoomLines;

        final double[] counterProgress;
        final double[] checkpointProgress;

        final Map<Integer, List<Passenger>> pendingToCP;
        final Map<Integer, List<Passenger>> pendingToHold;

        final Passenger[] counterServing;
        final Passenger[] checkpointServing;

        final Set<Passenger> ticketCompletedVisible;
        final List<Flight> justClosedFlights;

        final LinkedHashMap<Integer, Integer> heldUpsByInterval;
        final LinkedHashMap<Integer, Integer> ticketQueuedByInterval;
        final LinkedHashMap<Integer, Integer> checkpointQueuedByInterval;
        final LinkedHashMap<Integer, Integer> holdRoomTotalByInterval;

        EngineSnapshot(
                int currentInterval,
                List<LinkedList<Passenger>> ticketLines,
                List<LinkedList<Passenger>> completedTicketLines,
                List<LinkedList<Passenger>> checkpointLines,
                List<LinkedList<Passenger>> completedCheckpointLines,
                List<LinkedList<Passenger>> holdRoomLines,
                double[] counterProgress,
                double[] checkpointProgress,
                Map<Integer, List<Passenger>> pendingToCP,
                Map<Integer, List<Passenger>> pendingToHold,
                Passenger[] counterServing,
                Passenger[] checkpointServing,
                Set<Passenger> ticketCompletedVisible,
                List<Flight> justClosedFlights,
                LinkedHashMap<Integer, Integer> heldUpsByInterval,
                LinkedHashMap<Integer, Integer> ticketQueuedByInterval,
                LinkedHashMap<Integer, Integer> checkpointQueuedByInterval,
                LinkedHashMap<Integer, Integer> holdRoomTotalByInterval
        ) {
            this.currentInterval = currentInterval;
            this.ticketLines = ticketLines;
            this.completedTicketLines = completedTicketLines;
            this.checkpointLines = checkpointLines;
            this.completedCheckpointLines = completedCheckpointLines;
            this.holdRoomLines = holdRoomLines;

            this.counterProgress = counterProgress;
            this.checkpointProgress = checkpointProgress;

            this.pendingToCP = pendingToCP;
            this.pendingToHold = pendingToHold;

            this.counterServing = counterServing;
            this.checkpointServing = checkpointServing;

            this.ticketCompletedVisible = ticketCompletedVisible;
            this.justClosedFlights = justClosedFlights;

            this.heldUpsByInterval = heldUpsByInterval;
            this.ticketQueuedByInterval = ticketQueuedByInterval;
            this.checkpointQueuedByInterval = checkpointQueuedByInterval;
            this.holdRoomTotalByInterval = holdRoomTotalByInterval;
        }
    }

    // ==========================================================
    // Constructors (existing signature preserved — NO call-site break)
    // ==========================================================

    public SimulationEngine(double percentInPerson,
                            List<TicketCounterConfig> counterConfigs,
                            int numCheckpoints,
                            double checkpointRatePerHour,
                            int arrivalSpanMinutes,
                            int intervalMinutes,
                            int transitDelayMinutes,
                            int holdDelayMinutes,
                            List<Flight> flights) {
        this(percentInPerson, counterConfigs,
                buildDefaultCheckpointConfigs(numCheckpoints, checkpointRatePerHour),
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes,
                flights, null);
    }

    public SimulationEngine(double percentInPerson,
                            List<TicketCounterConfig> counterConfigs,
                            int numCheckpoints,
                            double checkpointRatePerHour,
                            int arrivalSpanMinutes,
                            int intervalMinutes,
                            int transitDelayMinutes,
                            int holdDelayMinutes,
                            List<Flight> flights,
                            List<HoldRoomConfig> holdRoomConfigs) {
        this(percentInPerson, counterConfigs,
                buildDefaultCheckpointConfigs(numCheckpoints, checkpointRatePerHour),
                arrivalSpanMinutes, intervalMinutes, transitDelayMinutes, holdDelayMinutes,
                flights, holdRoomConfigs);
    }

    // NEW: preferred constructor for per-checkpoint rates
    public SimulationEngine(double percentInPerson,
                            List<TicketCounterConfig> counterConfigs,
                            List<CheckpointConfig> checkpointConfigs,
                            int arrivalSpanMinutes,
                            int intervalMinutes,
                            int transitDelayMinutes,
                            int holdDelayMinutes,
                            List<Flight> flights,
                            List<HoldRoomConfig> holdRoomConfigs) {

        this.percentInPerson = percentInPerson;

        this.flights = (flights == null) ? new ArrayList<>() : flights;

        this.counterConfigs = (counterConfigs == null) ? new ArrayList<>() : counterConfigs;

        List<CheckpointConfig> cps = (checkpointConfigs == null) ? new ArrayList<>() : new ArrayList<>(checkpointConfigs);
        // safety: keep engine stable if someone accidentally creates 0 checkpoints
        if (cps.isEmpty()) {
            CheckpointConfig fallback = new CheckpointConfig(1);
            fallback.setRatePerHour(0.0);
            cps.add(fallback);
        }
        this.checkpointConfigs = cps;
        this.numCheckpoints = this.checkpointConfigs.size();

        this.defaultCheckpointRatePerHour = (this.checkpointConfigs.isEmpty())
                ? 0.0
                : this.checkpointConfigs.get(0).getRatePerHour();

        this.arrivalSpanMinutes = arrivalSpanMinutes;
        this.intervalMinutes = intervalMinutes;
        this.transitDelayMinutes = transitDelayMinutes;
        this.holdDelayMinutes = holdDelayMinutes;

        // Hold rooms: use provided or build defaults
        if (holdRoomConfigs != null && !holdRoomConfigs.isEmpty()) {
            this.holdRoomConfigs = new ArrayList<>(holdRoomConfigs);
        } else {
            this.holdRoomConfigs = buildDefaultHoldRoomConfigs(this.flights, holdDelayMinutes);
        }
        if (this.holdRoomConfigs.isEmpty()) {
            // safest constructor shape: (int)
            HoldRoomConfig cfg = new HoldRoomConfig(1);
            cfg.setWalkTime(Math.max(0, holdDelayMinutes), 0);
            this.holdRoomConfigs.add(cfg);
        }

        // compute global start time based on earliest departure
        LocalTime firstDep = this.flights.stream()
                .map(Flight::getDepartureTime)
                .min(LocalTime::compareTo)
                .orElse(LocalTime.MIDNIGHT);
        this.globalStart = firstDep.minusMinutes(arrivalSpanMinutes);

        long maxDeparture = this.flights.stream()
                .mapToLong(f -> Duration.between(globalStart, f.getDepartureTime()).toMinutes())
                .max().orElse(0);
        this.totalIntervals = (int) maxDeparture + 1;

        // ✅ Step 6: legacy generator is your existing ArrivalGenerator
        this.legacyMinuteGenerator = new ArrivalGenerator(arrivalSpanMinutes, 1);

        // ✅ Step 6: build arrivals map using legacy defaults (behavior unchanged)
        setArrivalCurveConfig(ArrivalCurveConfig.legacyDefault());

        computeChosenHoldRooms();

        holdRoomCellSize = new HashMap<>();
        for (Flight f : this.flights) {
            int total = (int) Math.round(f.getSeats() * f.getFillPercent());
            int bestCell = GridRenderer.MIN_CELL_SIZE;
            for (int rows = 1; rows <= Math.max(1, total); rows++) {
                int cols = (total + rows - 1) / rows;
                int cellByRows = GridRenderer.HOLD_BOX_SIZE / rows;
                int cellByCols = GridRenderer.HOLD_BOX_SIZE / cols;
                int cell = Math.min(cellByRows, cellByCols);
                bestCell = Math.max(bestCell, cell);
            }
            holdRoomCellSize.put(f, bestCell);
        }

        this.currentInterval = 0;

        // ticket lines
        ticketLines = new ArrayList<>();
        completedTicketLines = new ArrayList<>();
        for (int i = 0; i < this.counterConfigs.size(); i++) {
            ticketLines.add(new LinkedList<>());
            completedTicketLines.add(new LinkedList<>());
        }

        // checkpoint lines
        checkpointLines = new ArrayList<>();
        completedCheckpointLines = new ArrayList<>();
        for (int i = 0; i < this.numCheckpoints; i++) {
            checkpointLines.add(new LinkedList<>());
            completedCheckpointLines.add(new LinkedList<>());
        }

        // hold-room lines (PHYSICAL rooms)
        holdRoomLines = new ArrayList<>();
        for (int i = 0; i < this.holdRoomConfigs.size(); i++) {
            holdRoomLines.add(new LinkedList<>());
        }

        counterProgress = new double[this.counterConfigs.size()];
        checkpointProgress = new double[this.numCheckpoints];
        pendingToCP = new HashMap<>();
        pendingToHold = new HashMap<>();
        counterServing = new Passenger[this.counterConfigs.size()];
        checkpointServing = new Passenger[this.numCheckpoints];

        captureSnapshot0();
    }

    // ==========================================================
    // ✅ Step 6 public API: set arrivals config
    // ==========================================================

    /**
     * Apply curve config and rebuild per-minute arrivals.
     *
     * IMPORTANT:
     * - This is intended to be called BEFORE the simulation runs (currentInterval=0).
     * - Legacy behavior remains EXACTLY the same until cfg.legacyMode becomes false.
     */
    public void setArrivalCurveConfig(ArrivalCurveConfig cfg) {
        ArrivalCurveConfig copy = copyCfg(cfg);

        // For safety + to preserve your program's "20 minutes" rule everywhere:
        copy.setBoardingCloseMinutesBeforeDeparture(ArrivalCurveConfig.DEFAULT_BOARDING_CLOSE);

        copy.validateAndClamp();
        this.arrivalCurveConfig = copy;

        rebuildMinuteArrivalsMap();
    }

    public ArrivalCurveConfig getArrivalCurveConfigCopy() {
        return copyCfg(this.arrivalCurveConfig);
    }

    private static ArrivalCurveConfig copyCfg(ArrivalCurveConfig src) {
        if (src == null) return ArrivalCurveConfig.legacyDefault();

        ArrivalCurveConfig c = ArrivalCurveConfig.legacyDefault();
        c.setLegacyMode(src.isLegacyMode());

        c.setPeakMinutesBeforeDeparture(src.getPeakMinutesBeforeDeparture());
        c.setLeftSigmaMinutes(src.getLeftSigmaMinutes());
        c.setRightSigmaMinutes(src.getRightSigmaMinutes());

        c.setLateClampEnabled(src.isLateClampEnabled());
        c.setLateClampMinutesBeforeDeparture(src.getLateClampMinutesBeforeDeparture());

        c.setWindowStartMinutesBeforeDeparture(src.getWindowStartMinutesBeforeDeparture());
        c.setBoardingCloseMinutesBeforeDeparture(src.getBoardingCloseMinutesBeforeDeparture());

        c.validateAndClamp();
        return c;
    }

    private void rebuildMinuteArrivalsMap() {
        minuteArrivalsMap.clear();

        for (Flight f : flights) {
            int totalPassengers = (int) Math.round(f.getSeats() * f.getFillPercent());

            int[] perMin;
            if (arrivalCurveConfig == null || arrivalCurveConfig.isLegacyMode()) {
                // ✅ EXACT existing behavior (your current ArrivalGenerator logic)
                perMin = legacyMinuteGenerator.generateArrivals(f); // interval=1 => per-minute
            } else {
                // ✅ Edited behavior (split Gaussian + windowStart + clamp)
                perMin = editedMinuteGenerator.buildArrivalsPerMinute(
                        f,
                        totalPassengers,
                        arrivalCurveConfig,
                        arrivalSpanMinutes
                );
            }

            minuteArrivalsMap.put(f, (perMin == null) ? new int[0] : perMin);
        }
    }

    private static List<CheckpointConfig> buildDefaultCheckpointConfigs(int numCheckpoints, double checkpointRatePerHour) {
        int n = Math.max(0, numCheckpoints);
        double rateHr = Math.max(0.0, checkpointRatePerHour);

        List<CheckpointConfig> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CheckpointConfig cfg = new CheckpointConfig(i + 1);
            cfg.setRatePerHour(rateHr);
            list.add(cfg);
        }

        // safety: avoid 0 checkpoints if caller passed 0
        if (list.isEmpty()) {
            CheckpointConfig cfg = new CheckpointConfig(1);
            cfg.setRatePerHour(0.0);
            list.add(cfg);
        }
        return list;
    }

    // ==========================================================
    // RATES: conversion helpers
    // ==========================================================

    private double perIntervalFromPerMinute(double perMinute) {
        return Math.max(0.0, perMinute) * Math.max(1, intervalMinutes);
    }

    private double perIntervalFromPerHour(double perHour) {
        return (Math.max(0.0, perHour) / 60.0) * Math.max(1, intervalMinutes);
    }

    // TicketCounterConfig.getRate() is passengers/minute
    private double getTicketCounterRatePerInterval(int counterIdx) {
        if (counterIdx < 0 || counterIdx >= counterConfigs.size()) return 0.0;
        return perIntervalFromPerMinute(counterConfigs.get(counterIdx).getRate());
    }

    // CheckpointConfig stores passengers/hour
    private double getCheckpointRatePerInterval(int checkpointIdx) {
        if (checkpointIdx < 0 || checkpointIdx >= checkpointConfigs.size()) return 0.0;
        return perIntervalFromPerHour(checkpointConfigs.get(checkpointIdx).getRatePerHour());
    }

    // ==========================================================
    // Default HoldRoomConfig builder (old behavior)
    // ==========================================================
    private static List<HoldRoomConfig> buildDefaultHoldRoomConfigs(List<Flight> flights, int holdDelayMinutes) {
        List<HoldRoomConfig> list = new ArrayList<>();
        if (flights == null) return list;

        for (int i = 0; i < flights.size(); i++) {
            Flight f = flights.get(i);
            HoldRoomConfig cfg = new HoldRoomConfig(i + 1);
            cfg.setWalkTime(Math.max(0, holdDelayMinutes), 0);
            if (f != null) cfg.setAllowedFlights(Collections.singletonList(f));
            list.add(cfg);
        }
        return list;
    }

    private void computeChosenHoldRooms() {
        chosenHoldRoomIndexByFlight.clear();

        int roomCount = holdRoomConfigs.size();
        if (roomCount <= 0) return;

        for (Flight f : flights) {
            List<Integer> candidates = new ArrayList<>();
            int bestSeconds = Integer.MAX_VALUE;

            for (int r = 0; r < roomCount; r++) {
                HoldRoomConfig cfg = holdRoomConfigs.get(r);
                if (cfg == null) continue;

                if (!cfg.accepts(f)) continue;

                int ws = safeWalkSeconds(cfg);
                if (ws < bestSeconds) {
                    bestSeconds = ws;
                    candidates.clear();
                    candidates.add(r);
                } else if (ws == bestSeconds) {
                    candidates.add(r);
                }
            }

            int chosen;
            if (!candidates.isEmpty()) {
                chosen = candidates.get(rand.nextInt(candidates.size()));
            } else {
                int acceptAll = -1;
                for (int r = 0; r < roomCount; r++) {
                    HoldRoomConfig cfg = holdRoomConfigs.get(r);
                    if (cfg != null && cfg.getAllowedFlightNumbers().isEmpty()) {
                        acceptAll = r;
                        break;
                    }
                }
                chosen = (acceptAll >= 0) ? acceptAll : 0;
            }

            chosenHoldRoomIndexByFlight.put(f, clamp(chosen, 0, roomCount - 1));
        }
    }

    private int safeWalkSeconds(HoldRoomConfig cfg) {
        if (cfg == null) return Math.max(0, holdDelayMinutes) * 60;
        return Math.max(0, cfg.getWalkSecondsFromCheckpoint());
    }

    // NOTE: still uses 20 minutes (this matches your existing program rules)
    private int getBoardingCloseIdx(Flight f) {
        return (int) Duration.between(
                globalStart,
                f.getDepartureTime().minusMinutes(ArrivalCurveConfig.DEFAULT_BOARDING_CLOSE)
        ).toMinutes();
    }

    private int getDepartureIdx(Flight f) {
        return (int) Duration.between(
                globalStart,
                f.getDepartureTime()
        ).toMinutes();
    }

    private int ceilMinutesFromSeconds(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ((s % 60) > 0 ? 1 : 0);
    }

    // ============================
    // Snapshots
    // ============================

    private void captureSnapshot0() {
        stateSnapshots.clear();

        heldUpsByInterval.clear();
        ticketQueuedByInterval.clear();
        checkpointQueuedByInterval.clear();
        holdRoomTotalByInterval.clear();

        justClosedFlights.clear();
        ticketCompletedVisible.clear();

        recordQueueTotalsForCurrentInterval();

        EngineSnapshot s0 = makeSnapshot();
        stateSnapshots.add(s0);
        maxComputedInterval = 0;
    }

    private EngineSnapshot makeSnapshot() {
        return new EngineSnapshot(
                currentInterval,
                deepCopyLinkedLists(ticketLines),
                deepCopyLinkedLists(completedTicketLines),
                deepCopyLinkedLists(checkpointLines),
                deepCopyLinkedLists(completedCheckpointLines),
                deepCopyLinkedLists(holdRoomLines),
                Arrays.copyOf(counterProgress, counterProgress.length),
                Arrays.copyOf(checkpointProgress, checkpointProgress.length),
                deepCopyPendingMap(pendingToCP),
                deepCopyPendingMap(pendingToHold),
                Arrays.copyOf(counterServing, counterServing.length),
                Arrays.copyOf(checkpointServing, checkpointServing.length),
                new HashSet<>(ticketCompletedVisible),
                new ArrayList<>(justClosedFlights),
                new LinkedHashMap<>(heldUpsByInterval),
                new LinkedHashMap<>(ticketQueuedByInterval),
                new LinkedHashMap<>(checkpointQueuedByInterval),
                new LinkedHashMap<>(holdRoomTotalByInterval)
        );
    }

    private void appendSnapshotAfterInterval() {
        EngineSnapshot snap = makeSnapshot();

        if (currentInterval < stateSnapshots.size()) {
            stateSnapshots.set(currentInterval, snap);
        } else {
            stateSnapshots.add(snap);
        }
        maxComputedInterval = Math.max(maxComputedInterval, currentInterval);
    }

    // ============================
    // Restore snapshots
    // ============================

    private void restoreSnapshot(int targetInterval) {
        int t = clamp(targetInterval, 0, maxComputedInterval);
        EngineSnapshot s = stateSnapshots.get(t);

        this.currentInterval = s.currentInterval;

        restoreLinkedListsInPlace(ticketLines, s.ticketLines);
        restoreLinkedListsInPlace(completedTicketLines, s.completedTicketLines);
        restoreLinkedListsInPlace(checkpointLines, s.checkpointLines);
        restoreLinkedListsInPlace(completedCheckpointLines, s.completedCheckpointLines);
        restoreLinkedListsInPlace(holdRoomLines, s.holdRoomLines);

        if (this.counterProgress == null || this.counterProgress.length != s.counterProgress.length) {
            this.counterProgress = Arrays.copyOf(s.counterProgress, s.counterProgress.length);
        } else {
            System.arraycopy(s.counterProgress, 0, this.counterProgress, 0, s.counterProgress.length);
        }

        if (this.checkpointProgress == null || this.checkpointProgress.length != s.checkpointProgress.length) {
            this.checkpointProgress = Arrays.copyOf(s.checkpointProgress, s.checkpointProgress.length);
        } else {
            System.arraycopy(s.checkpointProgress, 0, this.checkpointProgress, 0, s.checkpointProgress.length);
        }

        this.pendingToCP.clear();
        this.pendingToCP.putAll(deepCopyPendingMap(s.pendingToCP));

        this.pendingToHold.clear();
        this.pendingToHold.putAll(deepCopyPendingMap(s.pendingToHold));

        if (this.counterServing == null || this.counterServing.length != s.counterServing.length) {
            this.counterServing = Arrays.copyOf(s.counterServing, s.counterServing.length);
        } else {
            System.arraycopy(s.counterServing, 0, this.counterServing, 0, s.counterServing.length);
        }

        if (this.checkpointServing == null || this.checkpointServing.length != s.checkpointServing.length) {
            this.checkpointServing = Arrays.copyOf(s.checkpointServing, s.checkpointServing.length);
        } else {
            System.arraycopy(s.checkpointServing, 0, this.checkpointServing, 0, s.checkpointServing.length);
        }

        this.ticketCompletedVisible.clear();
        this.ticketCompletedVisible.addAll(s.ticketCompletedVisible);

        this.justClosedFlights.clear();
        this.justClosedFlights.addAll(s.justClosedFlights);

        this.heldUpsByInterval.clear();
        this.heldUpsByInterval.putAll(s.heldUpsByInterval);

        this.ticketQueuedByInterval.clear();
        this.ticketQueuedByInterval.putAll(s.ticketQueuedByInterval);

        this.checkpointQueuedByInterval.clear();
        this.checkpointQueuedByInterval.putAll(s.checkpointQueuedByInterval);

        this.holdRoomTotalByInterval.clear();
        this.holdRoomTotalByInterval.putAll(s.holdRoomTotalByInterval);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ============================
    // Rewind API
    // ============================

    public boolean canRewind() { return currentInterval > 0; }
    public boolean canFastForward() { return currentInterval < maxComputedInterval; }
    public int getMaxComputedInterval() { return maxComputedInterval; }

    public void goToInterval(int targetInterval) { restoreSnapshot(targetInterval); }
    public void rewindOneInterval() { if (canRewind()) restoreSnapshot(currentInterval - 1); }

    public void fastForwardOneInterval() {
        if (canFastForward()) {
            restoreSnapshot(currentInterval + 1);
        } else {
            computeNextInterval();
        }
    }

    // ============================
    // Existing API
    // ============================

    public void computeNextInterval() {
        if (currentInterval >= totalIntervals) return;

        if ((currentInterval + 1) <= maxComputedInterval) {
            restoreSnapshot(currentInterval + 1);
            return;
        }

        simulateInterval();
    }

    public void runAllIntervals() {
        currentInterval = 0;

        clearHistory();

        heldUpsByInterval.clear();
        ticketQueuedByInterval.clear();
        checkpointQueuedByInterval.clear();
        holdRoomTotalByInterval.clear();

        justClosedFlights.clear();
        ticketCompletedVisible.clear();
        ticketLines.forEach(LinkedList::clear);
        completedTicketLines.forEach(LinkedList::clear);
        checkpointLines.forEach(LinkedList::clear);
        completedCheckpointLines.forEach(LinkedList::clear);
        holdRoomLines.forEach(LinkedList::clear);
        Arrays.fill(counterProgress, 0);
        Arrays.fill(checkpointProgress, 0);
        pendingToCP.clear();
        pendingToHold.clear();
        Arrays.fill(counterServing, null);
        Arrays.fill(checkpointServing, null);

        captureSnapshot0();

        while (currentInterval < totalIntervals) {
            simulateInterval();
        }
    }

    // ============================
    // Boarding close MARK
    // ============================

    private void handleBoardingCloseMarkMissed(Flight f) {
        justClosedFlights.add(f);

        int chosenRoom = chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
        chosenRoom = clamp(chosenRoom, 0, holdRoomLines.size() - 1);

        Set<Passenger> inChosen = new HashSet<>();
        for (Passenger p : holdRoomLines.get(chosenRoom)) {
            if (p != null && p.getFlight() == f) inChosen.add(p);
        }

        markMissedNotInChosen(ticketLines, f, inChosen);
        markMissedNotInChosen(completedTicketLines, f, inChosen);
        markMissedNotInChosen(checkpointLines, f, inChosen);
        markMissedNotInChosen(completedCheckpointLines, f, inChosen);

        purgeFromPendingMap(pendingToCP, f, inChosen);
        purgeFromPendingMap(pendingToHold, f, inChosen);

        for (int i = 0; i < counterServing.length; i++) {
            Passenger p = counterServing[i];
            if (p != null && p.getFlight() == f && !inChosen.contains(p)) p.setMissed(true);
        }
        for (int i = 0; i < checkpointServing.length; i++) {
            Passenger p = checkpointServing[i];
            if (p != null && p.getFlight() == f && !inChosen.contains(p)) p.setMissed(true);
        }
    }

    private void markMissedNotInChosen(List<LinkedList<Passenger>> lists, Flight f, Set<Passenger> inChosen) {
        for (LinkedList<Passenger> line : lists) {
            for (Passenger p : line) {
                if (p != null && p.getFlight() == f && !inChosen.contains(p)) p.setMissed(true);
            }
        }
    }

    private void purgeFromPendingMap(Map<Integer, List<Passenger>> pending, Flight f, Set<Passenger> inChosen) {
        Iterator<Map.Entry<Integer, List<Passenger>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<Passenger>> e = it.next();
            List<Passenger> list = e.getValue();
            if (list == null) continue;

            list.removeIf(p -> {
                if (p != null && p.getFlight() == f && !inChosen.contains(p)) {
                    p.setMissed(true);
                    return true;
                }
                return false;
            });

            if (list.isEmpty()) it.remove();
        }
    }

    // ============================
    // CLOSE CLEAR (non-hold areas)
    // ============================

    private void clearFlightFromNonHoldAreas(Flight f) {
        for (LinkedList<Passenger> line : ticketLines) line.removeIf(p -> p != null && p.getFlight() == f);
        for (LinkedList<Passenger> line : completedTicketLines) line.removeIf(p -> p != null && p.getFlight() == f);
        for (LinkedList<Passenger> line : checkpointLines) line.removeIf(p -> p != null && p.getFlight() == f);
        for (LinkedList<Passenger> line : completedCheckpointLines) line.removeIf(p -> p != null && p.getFlight() == f);

        purgeAllFromPendingMap(pendingToCP, f);
        purgeAllFromPendingMap(pendingToHold, f);

        for (int i = 0; i < counterServing.length; i++) {
            Passenger p = counterServing[i];
            if (p != null && p.getFlight() == f) counterServing[i] = null;
        }
        for (int i = 0; i < checkpointServing.length; i++) {
            Passenger p = checkpointServing[i];
            if (p != null && p.getFlight() == f) checkpointServing[i] = null;
        }

        ticketCompletedVisible.removeIf(p -> p != null && p.getFlight() == f);
    }

    private void purgeAllFromPendingMap(Map<Integer, List<Passenger>> pending, Flight f) {
        Iterator<Map.Entry<Integer, List<Passenger>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<Passenger>> e = it.next();
            List<Passenger> list = e.getValue();
            if (list == null) continue;

            list.removeIf(p -> p != null && p.getFlight() == f);
            if (list.isEmpty()) it.remove();
        }
    }

    // ============================
    // DEPARTURE CLEAR (hold rooms)
    // ============================

    private void clearFlightFromHoldRooms(Flight f) {
        for (LinkedList<Passenger> room : holdRoomLines) {
            room.removeIf(p -> p != null && p.getFlight() == f);
        }
    }

    // ============================
    // Queue helpers
    // ============================

    private Passenger takeFirstNotMissed(LinkedList<Passenger> q) {
        if (q == null || q.isEmpty()) return null;
        Iterator<Passenger> it = q.iterator();
        while (it.hasNext()) {
            Passenger p = it.next();
            if (p != null && !p.isMissed()) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    private void removeFromCompletedCheckpointLines(Passenger p) {
        if (p == null) return;
        for (LinkedList<Passenger> line : completedCheckpointLines) {
            Iterator<Passenger> it = line.iterator();
            while (it.hasNext()) {
                if (it.next() == p) {
                    it.remove();
                    return;
                }
            }
        }
    }

    // ============================
    // MAIN SIMULATION STEP
    // ============================

    public void simulateInterval() {
        justClosedFlights.clear();

        int minute = currentInterval;
        List<Flight> flightsDepartingThisMinute = new ArrayList<>();

        // 1) arrivals + detect boarding-close (mark missed only)
        for (Flight f : flights) {
            if (minute == getDepartureIdx(f)) flightsDepartingThisMinute.add(f);

            int[] perMin = minuteArrivalsMap.get(f);
            long offset = Duration.between(globalStart,
                            f.getDepartureTime().minusMinutes(arrivalSpanMinutes))
                    .toMinutes();
            int idx = minute - (int) offset;

            if (perMin != null && idx >= 0 && idx < perMin.length) {
                int totalHere = perMin[idx];

                int inPerson = (int) Math.round(totalHere * percentInPerson);
                int online = totalHere - inPerson;

                // Safety: if there are 0 ticket counters, treat everyone as "online"
                if (counterConfigs.isEmpty()) {
                    online += inPerson;
                    inPerson = 0;
                }

                List<Integer> allowed = new ArrayList<>();
                for (int j = 0; j < counterConfigs.size(); j++) {
                    if (counterConfigs.get(j).accepts(f)) allowed.add(j);
                }
                if (allowed.isEmpty() && !counterConfigs.isEmpty()) {
                    for (int j = 0; j < counterConfigs.size(); j++) allowed.add(j);
                }

                // enqueue in-person to ticket counters
                for (int i = 0; i < inPerson; i++) {
                    Passenger p = new Passenger(f, minute, true);
                    int best = allowed.get(0);
                    for (int ci : allowed) {
                        if (ticketLines.get(ci).size() < ticketLines.get(best).size()) best = ci;
                    }
                    ticketLines.get(best).add(p);
                }

                // online → checkpoint
                for (int i = 0; i < online; i++) {
                    Passenger p = new Passenger(f, minute, false);
                    p.setCheckpointEntryMinute(minute);

                    int bestC = 0;
                    for (int j = 1; j < numCheckpoints; j++) {
                        if (checkpointLines.get(j).size() < checkpointLines.get(bestC).size()) bestC = j;
                    }
                    checkpointLines.get(bestC).add(p);
                }
            }

            int closeIdx = getBoardingCloseIdx(f);
            if (minute == closeIdx) handleBoardingCloseMarkMissed(f);
        }

        // 2) ticket-counter service (TicketCounterConfig rate is passengers/minute)
        for (int c = 0; c < counterConfigs.size(); c++) {
            double ratePerInterval = getTicketCounterRatePerInterval(c);
            counterProgress[c] += ratePerInterval;

            int toComplete = (int) Math.floor(counterProgress[c]);
            counterProgress[c] -= toComplete;

            for (int k = 0; k < toComplete; k++) {
                Passenger next = takeFirstNotMissed(ticketLines.get(c));
                if (next == null) break;

                next.setTicketCompletionMinute(minute);
                completedTicketLines.get(c).add(next);
                ticketCompletedVisible.add(next);

                if (!next.isMissed()) {
                    pendingToCP.computeIfAbsent(minute + transitDelayMinutes, x -> new ArrayList<>())
                            .add(next);
                }
            }
        }

        // 3) move from ticket → checkpoint
        List<Passenger> toMove = pendingToCP.remove(minute);
        if (toMove != null) {
            for (Passenger p : toMove) {
                if (p == null || p.isMissed()) continue;
                ticketCompletedVisible.remove(p);
                p.setCheckpointEntryMinute(minute);

                int bestC = 0;
                for (int j = 1; j < numCheckpoints; j++) {
                    if (checkpointLines.get(j).size() < checkpointLines.get(bestC).size()) bestC = j;
                }
                checkpointLines.get(bestC).add(p);
            }
        }

        // 4) checkpoint service (per-checkpoint passengers/hour -> per interval)
        for (int c = 0; c < numCheckpoints; c++) {
            double ratePerInterval = getCheckpointRatePerInterval(c);
            checkpointProgress[c] += ratePerInterval;

            int toComplete = (int) Math.floor(checkpointProgress[c]);
            checkpointProgress[c] -= toComplete;

            for (int k = 0; k < toComplete; k++) {
                Passenger next = takeFirstNotMissed(checkpointLines.get(c));
                if (next == null) break;

                next.setCheckpointCompletionMinute(minute);
                completedCheckpointLines.get(c).add(next);

                if (!next.isMissed()) {
                    Flight f = next.getFlight();
                    int targetRoom = chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
                    targetRoom = clamp(targetRoom, 0, holdRoomConfigs.size() - 1);

                    next.setAssignedHoldRoomIndex(targetRoom);

                    int walkSeconds = safeWalkSeconds(holdRoomConfigs.get(targetRoom));
                    int delayMin = ceilMinutesFromSeconds(walkSeconds);

                    int arriveMinute = minute + delayMin;
                    pendingToHold.computeIfAbsent(arriveMinute, x -> new ArrayList<>())
                            .add(next);
                }
            }
        }

        // 5) move from checkpoint → hold-room
        List<Passenger> toHold = pendingToHold.remove(minute);
        if (toHold != null) {
            for (Passenger p : toHold) {
                if (p == null || p.isMissed()) continue;

                Flight f = p.getFlight();
                int closeIdx = getBoardingCloseIdx(f);

                if (minute < closeIdx) {
                    int roomIdx = p.getAssignedHoldRoomIndex();
                    if (roomIdx < 0) {
                        roomIdx = chosenHoldRoomIndexByFlight.getOrDefault(f, 0);
                        p.setAssignedHoldRoomIndex(roomIdx);
                    }
                    roomIdx = clamp(roomIdx, 0, holdRoomLines.size() - 1);

                    removeFromCompletedCheckpointLines(p);

                    p.setHoldRoomEntryMinute(minute);
                    int seq = holdRoomLines.get(roomIdx).size() + 1;
                    p.setHoldRoomSequence(seq);
                    holdRoomLines.get(roomIdx).add(p);
                } else {
                    p.setMissed(true);
                }
            }
        }

        // 5.5) Departure: clear hold rooms at departure time
        if (!flightsDepartingThisMinute.isEmpty()) {
            for (Flight f : flightsDepartingThisMinute) clearFlightFromHoldRooms(f);
        }

        // 6) record history (snapshot moment)
        historyServedTicket.add(deepCopyPassengerLists(completedTicketLines));
        historyQueuedTicket.add(deepCopyPassengerLists(ticketLines));
        historyServedCheckpoint.add(deepCopyPassengerLists(completedCheckpointLines));
        historyQueuedCheckpoint.add(deepCopyPassengerLists(checkpointLines));
        historyHoldRooms.add(deepCopyPassengerLists(holdRoomLines));

        // 6.5) close clear after snapshot
        if (!justClosedFlights.isEmpty()) {
            for (Flight f : justClosedFlights) clearFlightFromNonHoldAreas(f);
        }

        // 7) purge missed passengers
        removeMissedPassengers();

        // advance
        currentInterval++;

        int stillInTicketQueue = ticketLines.stream().mapToInt(List::size).sum();
        int stillInCheckpointQueue = checkpointLines.stream().mapToInt(List::size).sum();
        heldUpsByInterval.put(currentInterval, stillInTicketQueue + stillInCheckpointQueue);

        recordQueueTotalsForCurrentInterval();
        appendSnapshotAfterInterval();
    }

    // ============================
    // Missed purge
    // ============================

    public void removeMissedPassengers() {
        ticketLines.forEach(line -> line.removeIf(Passenger::isMissed));
        completedTicketLines.forEach(line -> line.removeIf(Passenger::isMissed));
        checkpointLines.forEach(line -> line.removeIf(Passenger::isMissed));
        completedCheckpointLines.forEach(line -> line.removeIf(Passenger::isMissed));
        holdRoomLines.forEach(line -> line.removeIf(Passenger::isMissed));
    }

    private List<List<Passenger>> deepCopyPassengerLists(List<LinkedList<Passenger>> original) {
        List<List<Passenger>> copy = new ArrayList<>();
        for (LinkedList<Passenger> line : original) copy.add(new ArrayList<>(line));
        return copy;
    }

    private void clearHistory() {
        historyArrivals.clear();
        historyEnqueuedTicket.clear();
        historyTicketed.clear();
        historyTicketLineSize.clear();
        historyArrivedToCheckpoint.clear();
        historyCPLineSize.clear();
        historyPassedCheckpoint.clear();
        historyServedTicket.clear();
        historyQueuedTicket.clear();
        historyOnlineArrivals.clear();
        historyFromTicketArrivals.clear();
        historyServedCheckpoint.clear();
        historyQueuedCheckpoint.clear();
        historyHoldRooms.clear();

        Arrays.fill(counterProgress, 0);
        Arrays.fill(checkpointProgress, 0);
        pendingToCP.clear();
        pendingToHold.clear();
        ticketCompletedVisible.clear();
        holdRoomLines.forEach(LinkedList::clear);
    }

    private static List<LinkedList<Passenger>> deepCopyLinkedLists(List<LinkedList<Passenger>> original) {
        List<LinkedList<Passenger>> copy = new ArrayList<>(original.size());
        for (LinkedList<Passenger> line : original) copy.add(new LinkedList<>(line));
        return copy;
    }

    private static void restoreLinkedListsInPlace(List<LinkedList<Passenger>> target,
                                                 List<LinkedList<Passenger>> source) {
        if (target.size() != source.size()) {
            target.clear();
            for (LinkedList<Passenger> src : source) target.add(new LinkedList<>(src));
            return;
        }
        for (int i = 0; i < target.size(); i++) {
            LinkedList<Passenger> t = target.get(i);
            t.clear();
            t.addAll(source.get(i));
        }
    }

    private static Map<Integer, List<Passenger>> deepCopyPendingMap(Map<Integer, List<Passenger>> original) {
        Map<Integer, List<Passenger>> copy = new HashMap<>();
        for (Map.Entry<Integer, List<Passenger>> e : original.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    // ============================
    // RESTORED METHODS (fix your red underlines)
    // ============================

    /** Used by SimulationFrame to show "Flight Closed" popups */
    public List<Flight> getFlightsJustClosed() {
        return new ArrayList<>(justClosedFlights);
    }

    /** Used by DataTableModel / DataTableFrame */
    public Map<Flight, int[]> getMinuteArrivalsMap() {
        return Collections.unmodifiableMap(minuteArrivalsMap);
    }

    /** Used by ArrivalsGraphPanel */
    public int getTotalArrivalsAtInterval(int intervalIndex) {
        // Interval 0 = initial state (before any simulateInterval ran)
        if (intervalIndex <= 0) return 0;
        // Your engine advances 1 minute per interval
        return getTotalArrivalsAtMinute(intervalIndex - 1);
    }

    /** Helper used by getTotalArrivalsAtInterval */
    public int getTotalArrivalsAtMinute(int minuteSinceGlobalStart) {
        int sum = 0;

        for (Flight f : flights) {
            int[] perMin = minuteArrivalsMap.get(f);
            if (perMin == null) continue;

            long offset = Duration.between(
                    globalStart,
                    f.getDepartureTime().minusMinutes(arrivalSpanMinutes)
            ).toMinutes();

            int idx = minuteSinceGlobalStart - (int) offset;
            if (idx >= 0 && idx < perMin.length) {
                sum += perMin[idx];
            }
        }
        return sum;
    }

    // ============================
    // HISTORY GETTERS
    // ============================
    public List<List<List<Passenger>>> getHistoryServedTicket() { return historyServedTicket; }
    public List<List<List<Passenger>>> getHistoryQueuedTicket() { return historyQueuedTicket; }
    public List<List<List<Passenger>>> getHistoryOnlineArrivals() { return historyOnlineArrivals; }
    public List<List<List<Passenger>>> getHistoryFromTicketArrivals() { return historyFromTicketArrivals; }
    public List<List<List<Passenger>>> getHistoryServedCheckpoint() { return historyServedCheckpoint; }
    public List<List<List<Passenger>>> getHistoryQueuedCheckpoint() { return historyQueuedCheckpoint; }
    public List<List<List<Passenger>>> getHistoryHoldRooms() { return historyHoldRooms; }

    // ============================
    // PUBLIC GETTERS
    // ============================
    public List<Flight> getFlights() { return flights; }
    public int getArrivalSpan() { return arrivalSpanMinutes; }
    public int getInterval() { return intervalMinutes; }
    public int getTotalIntervals() { return totalIntervals; }
    public int getCurrentInterval() { return currentInterval; }
    public List<LinkedList<Passenger>> getTicketLines() { return ticketLines; }
    public List<LinkedList<Passenger>> getCheckpointLines() { return checkpointLines; }
    public List<LinkedList<Passenger>> getCompletedTicketLines() { return completedTicketLines; }
    public List<LinkedList<Passenger>> getCompletedCheckpointLines() { return completedCheckpointLines; }
    public List<LinkedList<Passenger>> getHoldRoomLines() { return holdRoomLines; }
    public int getTransitDelayMinutes() { return transitDelayMinutes; }
    public int getHoldDelayMinutes() { return holdDelayMinutes; }
    public List<HoldRoomConfig> getHoldRoomConfigs() { return Collections.unmodifiableList(holdRoomConfigs); }
    public List<TicketCounterConfig> getCounterConfigs() { return Collections.unmodifiableList(counterConfigs); }
    public List<CheckpointConfig> getCheckpointConfigs() { return Collections.unmodifiableList(checkpointConfigs); }

    public int getHoldRoomCellSize(Flight f) {
        return holdRoomCellSize.getOrDefault(f, GridRenderer.MIN_CELL_SIZE);
    }

    public List<Passenger> getVisibleCompletedTicketLine(int idx) {
        List<Passenger> visible = new ArrayList<>();
        for (Passenger p : completedTicketLines.get(idx)) {
            if (ticketCompletedVisible.contains(p)) visible.add(p);
        }
        return visible;
    }

    public Map<Integer, Integer> getHoldUpsByInterval() {
        return new LinkedHashMap<>(heldUpsByInterval);
    }

    // ============================
    // QUEUE TOTALS METRICS
    // ============================

    public int getTicketQueuedAtInterval(int intervalIndex) {
        Integer v = ticketQueuedByInterval.get(intervalIndex);
        return v == null ? 0 : v;
    }

    public int getCheckpointQueuedAtInterval(int intervalIndex) {
        Integer v = checkpointQueuedByInterval.get(intervalIndex);
        return v == null ? 0 : v;
    }

    public int getHoldRoomTotalAtInterval(int intervalIndex) {
        Integer v = holdRoomTotalByInterval.get(intervalIndex);
        return v == null ? 0 : v;
    }

    public Map<Integer, Integer> getTicketQueuedByInterval() {
        return new LinkedHashMap<>(ticketQueuedByInterval);
    }
    public Map<Integer, Integer> getCheckpointQueuedByInterval() {
        return new LinkedHashMap<>(checkpointQueuedByInterval);
    }
    public Map<Integer, Integer> getHoldRoomTotalByInterval() {
        return new LinkedHashMap<>(holdRoomTotalByInterval);
    }

    private void recordQueueTotalsForCurrentInterval() {
        int ticketWaiting = ticketLines.stream().mapToInt(List::size).sum();
        int checkpointWaiting = checkpointLines.stream().mapToInt(List::size).sum();
        int holdTotal = holdRoomLines.stream().mapToInt(List::size).sum();

        ticketQueuedByInterval.put(currentInterval, ticketWaiting);
        checkpointQueuedByInterval.put(currentInterval, checkpointWaiting);
        holdRoomTotalByInterval.put(currentInterval, holdTotal);
    }
}
