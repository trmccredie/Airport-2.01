package sim.floorplan.ui;

import sim.floorplan.io.FloorplanProjectIO;
import sim.floorplan.io.PdfFloorplanImporter;
import sim.floorplan.mask.AutoMaskGenerator;
import sim.floorplan.model.FloorplanProject;
import sim.floorplan.model.WalkMask;
import sim.floorplan.model.Zone;
import sim.floorplan.model.ZoneType;

// ✅ Test-route A* router
import sim.floorplan.path.AStarRouter;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FloorplanEditorPanel extends JPanel {

    private final FloorplanCanvas canvas = new FloorplanCanvas();

    // Project state (Milestone 4)
    private final FloorplanProject project = new FloorplanProject();
    private boolean locked = false;
    private List<String> lastValidationErrors = new ArrayList<>();

    // PDF controls
    private final JButton uploadBtn = new JButton("Upload PDF");
    private final JSpinner pageSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
    private final JComboBox<Integer> dpiCombo = new JComboBox<>(new Integer[]{150, 200, 300});
    private final JButton renderBtn = new JButton("Render");

    // Auto-mask controls
    private final JSlider thresholdSlider = new JSlider(0, 255, 200);
    private final JCheckBox autoThrToggle = new JCheckBox("Auto Thr (Otsu)", false);
    private final JSpinner inflateSpinner = new JSpinner(new SpinnerNumberModel(6, 0, 60, 1));

    // outside removal controls
    private final JCheckBox removeOutsideToggle = new JCheckBox("Remove Outside", true);
    private final JSpinner sealGapsSpinner = new JSpinner(new SpinnerNumberModel(14, 0, 80, 1));

    private final JButton autoMaskBtn = new JButton("Auto Mask");

    // Tools (mask)
    private final JToggleButton selectToolBtn = new JToggleButton("Select");
    private final JToggleButton panToolBtn = new JToggleButton("Pan");
    private final JToggleButton walkToolBtn = new JToggleButton("Paint Walkable");
    private final JToggleButton blockToolBtn = new JToggleButton("Paint Blocked");
    private final JToggleButton polyFillBtn = new JToggleButton("Poly Fill Walkable");

    // ✅ Test Route tool controls
    private final JToggleButton testRouteBtn = new JToggleButton("Test Route");
    private final JSpinner routeStrideSpinner = new JSpinner(new SpinnerNumberModel(4, 1, 40, 1));
    private final JButton clearRouteBtn = new JButton("Clear Route");

    // Tools (zones)
    private final JToggleButton placeSpawnBtn = new JToggleButton("Place Spawn");
    private final JToggleButton placeTicketBtn = new JToggleButton("Place Ticket");
    private final JToggleButton placeCheckpointBtn = new JToggleButton("Place Checkpoint");
    private final JToggleButton placeHoldroomBtn = new JToggleButton("Place Holdroom");

    private final JToggleButton drawTicketQueueBtn = new JToggleButton("Draw Ticket Queue");
    private final JToggleButton drawCheckpointQueueBtn = new JToggleButton("Draw Checkpoint Queue");
    private final JToggleButton drawHoldroomAreaBtn = new JToggleButton("Draw Holdroom Area");

    private final JButton deleteSelectedBtn = new JButton("Delete Selected");
    private final JButton validateLockBtn = new JButton("Validate & Lock");
    private final JButton unlockBtn = new JButton("Unlock (Edit)");

    // ✅ Milestone 5 (Step 3): Save/Load FloorplanProject
    private final JButton saveProjectBtn = new JButton("Save Project");
    private final JButton loadProjectBtn = new JButton("Load Project");

    private final JSpinner brushSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 120, 1));
    private final JButton resetViewBtn = new JButton("Reset View");
    private final JCheckBox overlayToggle = new JCheckBox("Mask Overlay", true);

    private final JLabel statusLabel = new JLabel("No project loaded.");
    private final JLabel helpLabel = new JLabel(" ");

    private File currentPdf;
    private BufferedImage currentImage;
    private WalkMask currentMask;

    private Zone selectedZone;

    // ✅ Test route state
    private Point routeStart = null;
    private Point routeEnd = null;
    private List<Point> routePath = null;
    private SwingWorker<List<Point>, Void> routeWorker = null;

    public FloorplanEditorPanel() {
        super(new BorderLayout(10, 10));

        add(buildControlsNorth(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottomStatus(), BorderLayout.SOUTH);

        dpiCombo.setSelectedItem(200);

        thresholdSlider.setPaintTicks(true);
        thresholdSlider.setPaintLabels(true);
        thresholdSlider.setMajorTickSpacing(50);
        thresholdSlider.setMinorTickSpacing(10);
        thresholdSlider.setPreferredSize(new Dimension(220, thresholdSlider.getPreferredSize().height));

        // default tool
        panToolBtn.setSelected(true);
        canvas.setTool(FloorplanCanvas.Tool.PAN);
        canvas.setBrushRadiusPx(((Number) brushSpinner.getValue()).intValue());

        // wire canvas callbacks
        canvas.setOnPointAction((tool, pt) -> handlePointTool(tool, pt));
        canvas.setOnPolygonFinished((tool, poly) -> handlePolygonTool(tool, poly));
        canvas.setOnSelectionChanged(z -> {
            selectedZone = z;
            updateStatusSelection();
        });
        canvas.setOnDeleteRequested(this::deleteSelected);

        hookEvents();
        updateHelp();
        syncZonesToCanvas();
        setEditingEnabled(true);
    }

    /** Fix “buttons cut off”: wrap the controls in a scroll container and split into rows. */
    private JComponent buildControlsNorth() {
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        controls.add(buildRowPdf());
        controls.add(buildRowMask());
        controls.add(buildRowToolsMask());
        controls.add(buildRowToolsZones());

        JScrollPane scroller = new JScrollPane(
                controls,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        scroller.setBorder(BorderFactory.createEmptyBorder());
        scroller.getHorizontalScrollBar().setUnitIncrement(16);
        return scroller;
    }

    private JComponent buildRowPdf() {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        r.add(uploadBtn);

        r.add(new JLabel("Page:"));
        r.add(pageSpinner);

        r.add(new JLabel("DPI:"));
        r.add(dpiCombo);

        r.add(renderBtn);
        r.add(Box.createHorizontalStrut(12));
        r.add(overlayToggle);
        return r;
    }

    private JComponent buildRowMask() {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));

        r.add(new JLabel("Threshold:"));
        r.add(thresholdSlider);
        r.add(autoThrToggle);

        r.add(Box.createHorizontalStrut(10));
        r.add(new JLabel("Inflate(px):"));
        r.add(inflateSpinner);

        r.add(Box.createHorizontalStrut(10));
        r.add(removeOutsideToggle);

        r.add(new JLabel("Seal gaps(px):"));
        r.add(sealGapsSpinner);

        r.add(autoMaskBtn);
        return r;
    }

    private JComponent buildRowToolsMask() {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));

        ButtonGroup tools = getUnifiedToolGroup();
        tools.add(selectToolBtn);
        tools.add(panToolBtn);
        tools.add(walkToolBtn);
        tools.add(blockToolBtn);
        tools.add(polyFillBtn);

        // ✅ include Test Route tool in the same radio group (only 1 active tool)
        tools.add(testRouteBtn);

        r.add(new JLabel("Tools:"));
        r.add(selectToolBtn);
        r.add(panToolBtn);
        r.add(walkToolBtn);
        r.add(blockToolBtn);
        r.add(polyFillBtn);

        r.add(Box.createHorizontalStrut(10));
        r.add(testRouteBtn);
        r.add(new JLabel("Stride(px):"));
        r.add(routeStrideSpinner);
        r.add(clearRouteBtn);

        r.add(Box.createHorizontalStrut(12));
        r.add(new JLabel("Brush(px):"));
        r.add(brushSpinner);

        r.add(resetViewBtn);
        return r;
    }

    private JComponent buildRowToolsZones() {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));

        ButtonGroup tools = getUnifiedToolGroup();
        tools.add(placeSpawnBtn);
        tools.add(placeTicketBtn);
        tools.add(placeCheckpointBtn);
        tools.add(placeHoldroomBtn);

        tools.add(drawTicketQueueBtn);
        tools.add(drawCheckpointQueueBtn);
        tools.add(drawHoldroomAreaBtn);

        r.add(new JLabel("Zones:"));
        r.add(placeSpawnBtn);
        r.add(placeTicketBtn);
        r.add(placeCheckpointBtn);
        r.add(placeHoldroomBtn);

        r.add(Box.createHorizontalStrut(8));
        r.add(drawTicketQueueBtn);
        r.add(drawCheckpointQueueBtn);
        r.add(drawHoldroomAreaBtn);

        r.add(Box.createHorizontalStrut(12));
        r.add(deleteSelectedBtn);

        r.add(Box.createHorizontalStrut(12));
        r.add(validateLockBtn);
        r.add(unlockBtn);

        // ✅ Milestone 5 (Step 3): Save/Load buttons
        r.add(Box.createHorizontalStrut(12));
        r.add(saveProjectBtn);
        r.add(loadProjectBtn);

        return r;
    }

    // One unified group so only one tool is active at a time
    private ButtonGroup unifiedToolGroup;
    private ButtonGroup getUnifiedToolGroup() {
        if (unifiedToolGroup == null) unifiedToolGroup = new ButtonGroup();
        return unifiedToolGroup;
    }

    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createTitledBorder("Floorplan Preview"));
        canvas.setPreferredSize(new Dimension(900, 600));

        JScrollPane scroller = new JScrollPane(canvas,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        center.add(scroller, BorderLayout.CENTER);
        return center;
    }

    private JComponent buildBottomStatus() {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        helpLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(statusLabel);
        bottom.add(Box.createVerticalStrut(4));
        bottom.add(helpLabel);
        return bottom;
    }

    private void hookEvents() {
        overlayToggle.addActionListener(e -> canvas.setOverlayEnabled(overlayToggle.isSelected()));

        // Tool switching
        selectToolBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.SELECT); updateHelp(); });
        panToolBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.PAN); updateHelp(); });

        walkToolBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.PAINT_WALKABLE); updateHelp(); });
        blockToolBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.PAINT_BLOCKED); updateHelp(); });
        polyFillBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.POLY_FILL_WALKABLE); updateHelp(); });

        // ✅ Test Route tool
        testRouteBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.TEST_ROUTE); updateHelp(); });

        placeSpawnBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.PLACE_SPAWN); updateHelp(); });
        placeTicketBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.PLACE_TICKET_COUNTER); updateHelp(); });
        placeCheckpointBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.PLACE_CHECKPOINT); updateHelp(); });
        placeHoldroomBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.PLACE_HOLDROOM); updateHelp(); });

        drawTicketQueueBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.DRAW_TICKET_QUEUE); updateHelp(); });
        drawCheckpointQueueBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.DRAW_CHECKPOINT_QUEUE); updateHelp(); });
        drawHoldroomAreaBtn.addActionListener(e -> { canvas.setTool(FloorplanCanvas.Tool.DRAW_HOLDROOM_AREA); updateHelp(); });

        brushSpinner.addChangeListener(e -> canvas.setBrushRadiusPx(((Number) brushSpinner.getValue()).intValue()));
        resetViewBtn.addActionListener(e -> canvas.resetView());

        clearRouteBtn.addActionListener(e -> clearRoute());

        uploadBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select floorplan PDF");
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

            int res = chooser.showOpenDialog(FloorplanEditorPanel.this);
            if (res == JFileChooser.APPROVE_OPTION) {
                currentPdf = chooser.getSelectedFile();
                statusLabel.setText("Selected PDF: " + currentPdf.getName() + " (click Render)");
            }
        });

        renderBtn.addActionListener(e -> doRender());

        autoMaskBtn.addActionListener(e -> {
            if (currentImage == null) {
                JOptionPane.showMessageDialog(this, "Render a page first.", "Auto Mask", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (locked) {
                JOptionPane.showMessageDialog(this, "Unlock to edit mask.", "Locked", JOptionPane.WARNING_MESSAGE);
                return;
            }
            rebuildMaskFromControls();
        });

        deleteSelectedBtn.addActionListener(e -> deleteSelected());

        validateLockBtn.addActionListener(e -> validateAndLock());

        unlockBtn.addActionListener(e -> {
            if (!locked) return;
            int ok = JOptionPane.showConfirmDialog(this,
                    "Unlocking allows edits. This may invalidate the floorplan.\n\nUnlock now?",
                    "Unlock Floorplan",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (ok == JOptionPane.OK_OPTION) unlockForEditing();
        });

        // ✅ Milestone 5 (Step 3): Save/Load project
        saveProjectBtn.addActionListener(e -> doSaveProject());
        loadProjectBtn.addActionListener(e -> doLoadProject());
    }

    private void updateHelp() {
        FloorplanCanvas.Tool t = canvas.getTool();

        if (t == FloorplanCanvas.Tool.POLY_FILL_WALKABLE) {
            helpLabel.setText("Poly Fill: click points, double-click/Enter/right-click to close & fill. Backspace=undo, Esc=cancel. Right-drag pans.");
        } else if (t == FloorplanCanvas.Tool.DRAW_TICKET_QUEUE
                || t == FloorplanCanvas.Tool.DRAW_CHECKPOINT_QUEUE
                || t == FloorplanCanvas.Tool.DRAW_HOLDROOM_AREA) {
            helpLabel.setText("Draw Area: click points, double-click/Enter/right-click to close. Backspace=undo, Esc=cancel. Select anchor first to attach area.");
        } else if (t == FloorplanCanvas.Tool.PLACE_SPAWN
                || t == FloorplanCanvas.Tool.PLACE_TICKET_COUNTER
                || t == FloorplanCanvas.Tool.PLACE_CHECKPOINT
                || t == FloorplanCanvas.Tool.PLACE_HOLDROOM) {
            helpLabel.setText("Place Anchor: left-click to place. Use Select to choose anchors. Right-drag pans, wheel zoom.");
        } else if (t == FloorplanCanvas.Tool.TEST_ROUTE) {
            helpLabel.setText("Test Route: click START then END. A* runs on the walk mask and draws the path. Larger stride = faster but less precise.");
        } else if (t == FloorplanCanvas.Tool.SELECT) {
            helpLabel.setText("Select: click an anchor (near dot) or click inside an area polygon. Delete key removes selected. Right-drag pans.");
        } else if (t == FloorplanCanvas.Tool.PAINT_WALKABLE || t == FloorplanCanvas.Tool.PAINT_BLOCKED) {
            helpLabel.setText("Brush: left-drag to paint. Right-drag to pan. Mouse wheel zoom.");
        } else {
            helpLabel.setText("Pan: right-drag (or Pan tool). Mouse wheel zoom.");
        }
    }

    private void doRender() {
        List<String> errors = new ArrayList<>();
        if (currentPdf == null) errors.add("No PDF selected. Click 'Upload PDF' first.");

        Integer dpi = (Integer) dpiCombo.getSelectedItem();
        if (dpi == null) errors.add("DPI not selected.");
        int pageIndex = ((Number) pageSpinner.getValue()).intValue();

        if (!errors.isEmpty()) {
            showErrors(errors);
            return;
        }

        try {
            BufferedImage img = PdfFloorplanImporter.renderPage(currentPdf, pageIndex, dpi);
            currentImage = img;

            canvas.setImage(currentImage);

            // reset lock + zones on new render (coords likely changed)
            locked = false;
            selectedZone = null;
            lastValidationErrors = new ArrayList<>();
            project.getZones().clear();

            // clear route
            clearRoute();

            // update project metadata
            project.setPdfFile(currentPdf);
            project.setPageIndex(pageIndex);
            project.setDpi(dpi);
            project.setFloorplanImage(currentImage);

            // auto-generate mask after render
            rebuildMaskFromControls();

            canvas.setOverlayEnabled(overlayToggle.isSelected());
            syncZonesToCanvas();
            canvas.setLocked(false);
            setEditingEnabled(true);

            statusLabel.setText("Rendered: " + currentPdf.getName()
                    + " | page " + pageIndex + " | " + dpi + " DPI | mask/zones editable");

        } catch (Exception ex) {
            ex.printStackTrace();
            errors.add("Failed to render PDF: " + ex.getMessage());
            showErrors(errors);
        }
    }

    private void rebuildMaskFromControls() {
        int thr = thresholdSlider.getValue();
        int inflatePx = ((Number) inflateSpinner.getValue()).intValue();
        int sealPx = ((Number) sealGapsSpinner.getValue()).intValue();

        try {
            AutoMaskGenerator.Params p = new AutoMaskGenerator.Params();
            p.threshold = thr;
            p.autoThreshold = autoThrToggle.isSelected();
            p.inflatePx = inflatePx;
            p.removeOutside = removeOutsideToggle.isSelected();
            p.sealGapsPx = sealPx;

            currentMask = AutoMaskGenerator.generate(currentImage, p);
            canvas.setMask(currentMask);

            project.setMask(currentMask == null ? null : currentMask.copy());

            // route may become invalid after changing mask
            clearRoute();

            statusLabel.setText("Mask ready | thr " + (p.autoThreshold ? "AUTO" : thr)
                    + " | inflate " + inflatePx + "px | removeOutside=" + p.removeOutside
                    + " | sealGaps " + sealPx + "px");
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Auto-mask failed: " + ex.getMessage(),
                    "Auto Mask",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showErrors(List<String> errors) {
        JOptionPane.showMessageDialog(
                this,
                String.join("\n", errors),
                "Floorplan Editor",
                JOptionPane.WARNING_MESSAGE
        );
    }

    // ==========================================================
    // ✅ Milestone 5 (Step 3): Save / Load FloorplanProject
    // ==========================================================

    private void doSaveProject() {
        try {
            List<String> pre = validateProject();
            if (!pre.isEmpty()) {
                showErrors(pre);
                return;
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save Floorplan Project (*.fsp)");
            chooser.setSelectedFile(new File("floorplan.fsp"));

            int res = chooser.showSaveDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            // ensure latest runtime state is in the project before saving
            project.setPdfFile(currentPdf);
            project.setPageIndex(((Number) pageSpinner.getValue()).intValue());
            project.setDpi((Integer) dpiCombo.getSelectedItem());
            project.setFloorplanImage(currentImage);
            project.setMask(currentMask == null ? null : currentMask.copy());

            FloorplanProjectIO.saveToFile(project.copy(), chooser.getSelectedFile());
            statusLabel.setText("✅ Saved project: " + chooser.getSelectedFile().getName());

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Save failed: " + ex.getMessage(),
                    "Save Project",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doLoadProject() {
        try {
            if (locked) {
                JOptionPane.showMessageDialog(this,
                        "Unlock before loading a different project.",
                        "Locked",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Load Floorplan Project (*.fsp)");

            int res = chooser.showOpenDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            FloorplanProject loaded = FloorplanProjectIO.loadFromFile(chooser.getSelectedFile());

            // apply to UI/editor state
            currentPdf = loaded.getPdfFile(); // may be null (or missing on disk)
            currentImage = loaded.getFloorplanImage();
            currentMask = loaded.getMask();

            // update project object in-place
            project.setPdfFile(currentPdf);
            project.setPageIndex(loaded.getPageIndex());
            project.setDpi(loaded.getDpi());
            project.setFloorplanImage(currentImage);
            project.setMask(currentMask == null ? null : currentMask.copy());
            project.setZones(loaded.getZones());

            // update UI widgets if metadata exists
            pageSpinner.setValue(loaded.getPageIndex());
            if (loaded.getDpi() != null) dpiCombo.setSelectedItem(loaded.getDpi());

            // push into canvas
            canvas.setImage(currentImage);
            canvas.setMask(currentMask);
            canvas.setOverlayEnabled(overlayToggle.isSelected());

            // clear route
            clearRoute();

            selectedZone = null;
            canvas.setSelectedZone(null);
            syncZonesToCanvas();

            // reset lock state
            locked = false;
            lastValidationErrors = new ArrayList<>();
            canvas.setLocked(false);
            setEditingEnabled(true);

            // pick a sane default tool after load
            panToolBtn.setSelected(true);
            canvas.setTool(FloorplanCanvas.Tool.PAN);
            updateHelp();

            statusLabel.setText("✅ Loaded project: " + chooser.getSelectedFile().getName()
                    + (currentPdf != null ? (" | PDF link: " + currentPdf.getName()) : " | (PDF path not found; image/mask loaded)"));

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Load failed: " + ex.getMessage(),
                    "Load Project",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // ==========================================================
    // Point/Polygon tool handlers (from canvas)
    // ==========================================================

    private void handlePointTool(FloorplanCanvas.Tool tool, Point imgPt) {
        if (currentMask == null || currentImage == null) return;
        if (imgPt == null) return;

        // bounds
        if (imgPt.x < 0 || imgPt.y < 0 || imgPt.x >= currentImage.getWidth() || imgPt.y >= currentImage.getHeight()) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        // ✅ Test route works even when locked
        if (tool == FloorplanCanvas.Tool.TEST_ROUTE) {
            handleTestRouteClick(imgPt);
            return;
        }

        // everything else respects locked
        if (locked) return;

        // require walkable anchor placement
        if (!currentMask.isWalkable(imgPt.x, imgPt.y)) {
            Toolkit.getDefaultToolkit().beep();
            statusLabel.setText("Anchor must be on GREEN walkable pixel.");
            return;
        }

        ZoneType type = null;
        String prefix = null;

        if (tool == FloorplanCanvas.Tool.PLACE_SPAWN) { type = ZoneType.SPAWN; prefix = "S"; }
        if (tool == FloorplanCanvas.Tool.PLACE_TICKET_COUNTER) { type = ZoneType.TICKET_COUNTER; prefix = "T"; }
        if (tool == FloorplanCanvas.Tool.PLACE_CHECKPOINT) { type = ZoneType.CHECKPOINT; prefix = "C"; }
        if (tool == FloorplanCanvas.Tool.PLACE_HOLDROOM) { type = ZoneType.HOLDROOM; prefix = "H"; }

        if (type == null || prefix == null) return;

        String id = nextId(prefix);
        Zone z = Zone.anchorZone(id, type, imgPt);
        project.addZone(z);

        selectedZone = z;
        canvas.setSelectedZone(z);
        syncZonesToCanvas();

        statusLabel.setText("Placed " + type.getLabel() + " " + id + " at (" + imgPt.x + "," + imgPt.y + ")");
    }

    // ✅ Test Route click logic
    private void handleTestRouteClick(Point imgPt) {
        if (currentMask == null || currentImage == null) return;

        // click 1 = start, click 2 = end, click 3 = new start
        if (routeStart == null || (routeStart != null && routeEnd != null)) {
            routeStart = new Point(imgPt);
            routeEnd = null;
            routePath = null;
            cancelRouteWorker();
            canvas.setTestRoute(routeStart, null, null);

            statusLabel.setText("Route START set at (" + routeStart.x + "," + routeStart.y + "). Click an end point.");
            return;
        }

        routeEnd = new Point(imgPt);
        routePath = null;
        canvas.setTestRoute(routeStart, routeEnd, null);

        int stride = ((Number) routeStrideSpinner.getValue()).intValue();
        statusLabel.setText("Routing (A*)... stride=" + stride + "px");

        cancelRouteWorker();

        routeWorker = new SwingWorker<>() {
            @Override
            protected List<Point> doInBackground() {
                // NOTE: maxExpanded is a safety cap; adjust if you have huge plans
                return AStarRouter.findPath(
                        currentMask,
                        routeStart,
                        routeEnd,
                        stride,
                        2_000_000,
                        true
                );
            }

            @Override
            protected void done() {
                if (isCancelled()) return;
                try {
                    List<Point> path = get();
                    routePath = path;

                    if (path == null || path.size() < 2) {
                        canvas.setTestRoute(routeStart, routeEnd, null);
                        statusLabel.setText("❌ No path found. Try higher inflate / lower stride / fix mask gaps.");
                    } else {
                        canvas.setTestRoute(routeStart, routeEnd, path);
                        statusLabel.setText("✅ Path found: " + path.size() + " pts (stride=" + stride + ")");
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    canvas.setTestRoute(routeStart, routeEnd, null);
                    statusLabel.setText("❌ Routing failed: " + ex.getMessage());
                }
            }
        };

        routeWorker.execute();
    }

    private void cancelRouteWorker() {
        if (routeWorker != null) {
            routeWorker.cancel(true);
            routeWorker = null;
        }
    }

    private void clearRoute() {
        cancelRouteWorker();
        routeStart = null;
        routeEnd = null;
        routePath = null;
        canvas.clearTestRoute();
    }

    private void handlePolygonTool(FloorplanCanvas.Tool tool, Polygon poly) {
        if (locked) return;
        if (poly == null || poly.npoints < 3) return;

        // Must have an anchor selected to attach to
        if (selectedZone == null || selectedZone.getType() == null || !selectedZone.getType().hasAnchor()) {
            Toolkit.getDefaultToolkit().beep();
            JOptionPane.showMessageDialog(this,
                    "Select an anchor (Ticket/Checkpoint/Holdroom) first, then draw its area.",
                    "No Anchor Selected",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        ZoneType areaType = null;
        String areaId = null;

        if (tool == FloorplanCanvas.Tool.DRAW_TICKET_QUEUE) {
            if (selectedZone.getType() != ZoneType.TICKET_COUNTER) {
                warnWrongAnchor("Ticket Counter");
                return;
            }
            areaType = ZoneType.TICKET_QUEUE_AREA;
            areaId = selectedZone.getId() + "_QUEUE";
        } else if (tool == FloorplanCanvas.Tool.DRAW_CHECKPOINT_QUEUE) {
            if (selectedZone.getType() != ZoneType.CHECKPOINT) {
                warnWrongAnchor("Checkpoint");
                return;
            }
            areaType = ZoneType.CHECKPOINT_QUEUE_AREA;
            areaId = selectedZone.getId() + "_QUEUE";
        } else if (tool == FloorplanCanvas.Tool.DRAW_HOLDROOM_AREA) {
            if (selectedZone.getType() != ZoneType.HOLDROOM) {
                warnWrongAnchor("Holdroom");
                return;
            }
            areaType = ZoneType.HOLDROOM_AREA;
            areaId = selectedZone.getId() + "_AREA";
        } else {
            return;
        }

        // Replace existing area zone if present
        Zone existing = findZone(areaId, areaType);
        if (existing != null) {
            existing.setArea(poly);
            selectedZone = existing;
            canvas.setSelectedZone(existing);
        } else {
            Zone area = Zone.areaZone(areaId, areaType, poly);
            project.addZone(area);
            selectedZone = area;
            canvas.setSelectedZone(area);
        }

        syncZonesToCanvas();
        statusLabel.setText("Set " + areaType.getLabel() + " for " + areaId);
    }

    private void warnWrongAnchor(String expected) {
        Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(this,
                "This draw tool requires a selected " + expected + " anchor.\n\n" +
                        "Use Select and click the correct anchor dot first.",
                "Wrong Anchor Selected",
                JOptionPane.WARNING_MESSAGE);
    }

    private Zone findZone(String id, ZoneType type) {
        for (Zone z : project.getZones()) {
            if (z == null) continue;
            if (z.getType() == type && id != null && id.equals(z.getId())) return z;
        }
        return null;
    }

    private String nextId(String prefix) {
        int max = 0;
        for (Zone z : project.getZones()) {
            if (z == null || z.getId() == null) continue;
            String id = z.getId().trim();
            if (!id.startsWith(prefix)) continue;
            String rest = id.substring(prefix.length());
            try {
                int n = Integer.parseInt(rest);
                if (n > max) max = n;
            } catch (Exception ignored) {}
        }
        return prefix + (max + 1);
    }

    private void deleteSelected() {
        if (locked) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        if (selectedZone == null) return;

        Zone z = selectedZone;
        selectedZone = null;
        canvas.setSelectedZone(null);

        // If deleting an anchor, also delete its matching area zone
        if (z.getType() == ZoneType.TICKET_COUNTER) {
            removeZoneById(z.getId() + "_QUEUE", ZoneType.TICKET_QUEUE_AREA);
        } else if (z.getType() == ZoneType.CHECKPOINT) {
            removeZoneById(z.getId() + "_QUEUE", ZoneType.CHECKPOINT_QUEUE_AREA);
        } else if (z.getType() == ZoneType.HOLDROOM) {
            removeZoneById(z.getId() + "_AREA", ZoneType.HOLDROOM_AREA);
        }

        project.removeZone(z);
        syncZonesToCanvas();
        statusLabel.setText("Deleted: " + z);
    }

    private void removeZoneById(String id, ZoneType t) {
        Zone target = findZone(id, t);
        if (target != null) project.removeZone(target);
    }

    private void syncZonesToCanvas() {
        canvas.setZones(project.getZones());
        canvas.repaint();
    }

    private void updateStatusSelection() {
        if (selectedZone == null) return;
        statusLabel.setText("Selected: " + selectedZone.toString());
    }

    // ==========================================================
    // Validate + Lock API (used by MainFrame)
    // ==========================================================

    public boolean validateAndLock() {
        if (locked) return true;

        // basic checks
        List<String> pre = validateProject();
        if (!pre.isEmpty()) {
            showErrors(pre);
            return false;
        }

        lastValidationErrors = project.validate();
        if (!lastValidationErrors.isEmpty()) {
            showErrors(lastValidationErrors);
            return false;
        }

        locked = true;
        canvas.setLocked(true);
        setEditingEnabled(false);

        statusLabel.setText("✅ Floorplan validated & LOCKED.");
        return true;
    }

    public boolean isLocked() { return locked; }

    public List<String> getLastValidationErrors() { return new ArrayList<>(lastValidationErrors); }

    private void unlockForEditing() {
        locked = false;
        canvas.setLocked(false);
        setEditingEnabled(true);
        statusLabel.setText("Unlocked. Edits allowed (re-validate before starting).");
    }

    private void setEditingEnabled(boolean enabled) {
        // allow view actions always:
        resetViewBtn.setEnabled(true);
        overlayToggle.setEnabled(true);

        // ✅ Test route always enabled (even locked)
        testRouteBtn.setEnabled(true);
        routeStrideSpinner.setEnabled(true);
        clearRouteBtn.setEnabled(true);

        // mask tools
        walkToolBtn.setEnabled(enabled);
        blockToolBtn.setEnabled(enabled);
        polyFillBtn.setEnabled(enabled);

        // zone tools
        placeSpawnBtn.setEnabled(enabled);
        placeTicketBtn.setEnabled(enabled);
        placeCheckpointBtn.setEnabled(enabled);
        placeHoldroomBtn.setEnabled(enabled);

        drawTicketQueueBtn.setEnabled(enabled);
        drawCheckpointQueueBtn.setEnabled(enabled);
        drawHoldroomAreaBtn.setEnabled(enabled);

        deleteSelectedBtn.setEnabled(enabled);

        // generators
        autoMaskBtn.setEnabled(enabled);
        thresholdSlider.setEnabled(enabled);
        autoThrToggle.setEnabled(enabled);
        inflateSpinner.setEnabled(enabled);
        removeOutsideToggle.setEnabled(enabled);
        sealGapsSpinner.setEnabled(enabled);

        validateLockBtn.setEnabled(enabled);
        unlockBtn.setEnabled(!enabled);

        // save/load should still work when locked (save definitely; load guarded by code)
        saveProjectBtn.setEnabled(true);
        loadProjectBtn.setEnabled(true);

        // if we disabled the currently-selected tool, revert to Pan
        if (!enabled) {
            if (canvas.getTool() != FloorplanCanvas.Tool.TEST_ROUTE) {
                panToolBtn.setSelected(true);
                canvas.setTool(FloorplanCanvas.Tool.PAN);
                updateHelp();
            } else {
                updateHelp();
            }
        }
    }

    // ==========================================================
    // Existing Required API for your App Tabs flow
    // ==========================================================

    public boolean hasValidProject() {
        return validateProject().isEmpty();
    }

    /**
     * ✅ Milestone 5 (Step 3): do NOT require a PDF, because loaded projects may not
     * have the original PDF available on disk. Image + mask are what matter.
     */
    public List<String> validateProject() {
        List<String> errs = new ArrayList<>();
        if (currentImage == null) errs.add("No rendered/loaded image. Render a PDF page or Load Project.");
        if (currentMask == null) errs.add("No mask generated/loaded.");
        return errs;
    }

    public FloorplanProject getProjectCopy() {
        return project.copy();
    }

    public void setMask(WalkMask mask) {
        if (locked) {
            JOptionPane.showMessageDialog(this, "Unlock to edit mask.", "Locked", JOptionPane.WARNING_MESSAGE);
            return;
        }
        this.currentMask = mask;
        canvas.setMask(mask);
        project.setMask(mask == null ? null : mask.copy());

        // route may be invalid after mask changes
        clearRoute();

        statusLabel.setText(statusLabel.getText() + " | mask updated");
    }
}
