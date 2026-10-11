// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.arkkisnappi;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.KeyPressReleaseListener;
import org.openstreetmap.josm.gui.util.ModifierExListener;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * The main map mode for arkki-snappi.
 *
 * <p>Implements a multi-phase interaction for building footprint creation:</p>
 * <ol>
 *   <li><b>IDLE</b> — waiting for the user to click the first corner.</li>
 *   <li><b>PHASE_ANCHOR</b> — anchor placed; second click defines either the
 *       diagonal corner (2-click, when a reference orientation exists) or the
 *       adjacent corner (3-click, freehand orientation).</li>
 *   <li><b>PHASE_DEPTH</b> — (3-click only) edge defined; mouse controls
 *       perpendicular depth; third click commits the rectangle.</li>
 *   <li><b>PHASE_EXTRUDE</b> — rectangle committed; user can drag edge handles
 *       to extrude; Shift-click handles to select multiple parallel edges;
 *       Ctrl-click a handle to split its edge into three equal sections; click
 *       on an edge to insert a node and split it.</li>
 * </ol>
 *
 * <p>Keyboard controls:</p>
 * <ul>
 *   <li><b>A</b> — toggle angle-snapping (cardinal-aligned grid)</li>
 *   <li><b>Shift</b> — axis-lock (snap only in the dominant axis)</li>
 *   <li><b>Shift while finishing/extruding</b> — keep collinear nodes for that operation</li>
 *   <li><b>Ctrl</b> — free mode (disable snapping); overridden back to snapped
 *       when Shift is held at the same time, so it doesn't fight the default
 *       "new polygon" chord below</li>
 *   <li><b>Shift-click an edge handle</b> — select or deselect a parallel edge
 *       for simultaneous extrusion</li>
 *   <li><b>Ctrl-click an edge handle</b> — split the edge into three equal
 *       sections instead of the usual two</li>
 *   <li><b>The configured "new polygon" modifier while extruding</b> (default
 *       {@code Ctrl+Shift}, rebindable via {@link SnappiPreferences#getNewPolygonModifier()})
 *       — commit the drag as a new, separately-tagged way sewn to the dragged
 *       edge instead of growing the current way</li>
 *   <li><b>C</b> — halve the active snap step size</li>
 *   <li><b>V</b> — double the active snap step size</li>
 *   <li><b>Enter</b> — finish the current shape</li>
 *   <li><b>Esc</b> — cancel and return to IDLE</li>
 * </ul>
 *
 * @author Lumikeiju
 * @see SnappiGrid
 * @see SnappiPreferences
 */
public class SnappiMode extends MapMode
        implements MapViewPaintable, ModifierExListener, KeyPressReleaseListener {

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    private enum Phase {
        IDLE,
        PHASE_ANCHOR,
        PHASE_DEPTH,
        PHASE_EXTRUDE
    }

    private Phase phase = Phase.IDLE;

    // ------------------------------------------------------------------
    // Transient state
    // ------------------------------------------------------------------

    /** The anchor corner (click 1) in EastNorth. */
    private EastNorth anchorEN;

    /** Current snapped target corner. */
    private EastNorth snappedTarget;

    /** The u- and v-axis unit vectors for the current grid frame. */
    private EastNorth uAxis;
    private EastNorth vAxis;

    // --- 3-click mode (PHASE_DEPTH) ---

    /** Adjacent corner from click 2 in 3-click mode. */
    private EastNorth adjacentEN;

    /** Fixed u-length from click 2 (signed, in metres along u-axis). */
    private double fixedULen;

    // --- Reference orientation ---

    /** True when the grid axes are locked (angle-snap, selected way, or nearby building). */
    private boolean hasReferenceOrientation;

    /** Cardinal angle-snap toggle (A key). */
    private boolean angleSnap;

    /** Reference way for grid overlay inside existing building. */
    private Way referenceWay;

    /** Cached EastNorth corners of the reference way (for grid extent). */
    private EastNorth[] referenceCorners;

    // --- Committed building ---

    private Way createdWay;
    private EastNorth[] wayCorners;

    /** Edge index the mouse is hovering over (-1 = none). */
    private int hoveredEdge = -1;

    /** Parallel edge indices selected for the next extrusion. */
    private final Set<Integer> selectedEdges = new LinkedHashSet<>();

    /** True while the user is drag-extruding an edge. */
    private boolean dragging;

    /** True if the mouse moved further than MIN_DRAG_PIXELS since mousePressed. */
    private boolean wasDragged;

    /** Screen-pixel position where the drag press started (for threshold check). */
    private Point dragPressPoint;

    /** Mouse EastNorth when the drag started. */
    private EastNorth dragStart;

    /** Edge that started the current drag. */
    private int dragEdge = -1;

    /** Snapshot of the selected edges being extruded by the current drag. */
    private final List<Integer> dragEdges = new ArrayList<>();

    /** Whether Shift was held when the current drag started. */
    private boolean shiftDownAtPress;

    /** Whether Ctrl was held when the current handle click started. */
    private boolean splitIntoThirdsAtPress;

    /** Nodes created for one edge during a multi-edge extrusion. */
    private static final class EdgeExtrusion {
        private final int edgeIndex;
        private final Node newNode0;
        private final Node newNode1;

        EdgeExtrusion(int edgeIndex, Node newNode0, Node newNode1) {
            this.edgeIndex = edgeIndex;
            this.newNode0 = newNode0;
            this.newNode1 = newNode1;
        }
    }

    /** New way and corner cache created for one edge during a split extrusion. */
    private static final class NewPolygonExtrusion {
        private final int edgeIndex;
        private final Way way;
        private final EastNorth[] corners;
        private final Node newNode0;
        private final Node newNode1;

        NewPolygonExtrusion(int edgeIndex, Way way, EastNorth[] corners,
                            Node newNode0, Node newNode1) {
            this.edgeIndex = edgeIndex;
            this.way = way;
            this.corners = corners;
            this.newNode0 = newNode0;
            this.newNode1 = newNode1;
        }
    }

    // --- Step sizes ---

    private double activeStepU;
    private double activeStepV;

    /**
     * EastNorth units per real-world metre at the current anchor point.
     * Computed once when the anchor is placed to correct for projection
     * distortion (e.g. Mercator scale factor).
     */
    private double projectionScale = 1.0;

    /** Returns the u-step scaled to EastNorth units. */
    private double enStepU() {
        return activeStepU * projectionScale;
    }

    /** Returns the v-step scaled to EastNorth units. */
    private double enStepV() {
        return activeStepV * projectionScale;
    }

    // --- Modifier keys ---

    private boolean shiftDown;
    private boolean ctrlDown;
    private boolean altDown;

    /** Current index into the step presets list (for Alt cycling). */
    private int presetIndex = -1;

    private static final double MIN_EXTRUDE_THRESHOLD = 1e-6;

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /**
     * Creates the Snappi map mode with default keyboard shortcut B.
     */
    public SnappiMode() {
        super(tr("Snappi"), "arkkisnappi",
                tr("Draw buildings with a snap grid (click corners, then extrude edges)"),
                Shortcut.registerShortcut("mapmode:snappi",
                        tr("Mode: {0}", tr("Snappi")),
                        KeyEvent.VK_B, Shortcut.DIRECT),
                ImageProvider.getCursor("crosshair", null));
    }

    // ------------------------------------------------------------------
    // Mode lifecycle
    // ------------------------------------------------------------------

    @Override
    public void enterMode() {
        super.enterMode();
        MapFrame map = MainApplication.getMap();
        if (map == null) return;

        map.mapView.addMouseListener(this);
        map.mapView.addMouseMotionListener(this);
        map.mapView.addTemporaryLayer(this);
        map.keyDetector.addModifierExListener(this);
        map.keyDetector.addKeyListener(this);

        activeStepU = SnappiPreferences.getStepXMetres();
        activeStepV = SnappiPreferences.getStepYMetres();
        resetState();
        refreshStatusText();
    }

    @Override
    public void exitMode() {
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.removeMouseListener(this);
            map.mapView.removeMouseMotionListener(this);
            map.mapView.removeTemporaryLayer(this);
            map.keyDetector.removeModifierExListener(this);
            map.keyDetector.removeKeyListener(this);
        }
        resetState();
        if (map != null) {
            map.mapView.repaint();
        }
        super.exitMode();
    }

    @Override
    public boolean layerIsSupported(Layer layer) {
        return layer instanceof OsmDataLayer;
    }

    // ------------------------------------------------------------------
    // Mouse events
    // ------------------------------------------------------------------

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) return;

        MapView mv = MainApplication.getMap().mapView;
        EastNorth mouseEN = mv.getEastNorth(e.getX(), e.getY());

        if (phase == Phase.PHASE_EXTRUDE) {
            syncWayCorners();
            if (createdWay != null && wayCorners != null) {
                // Single-click on an edge (not a handle) inserts a node,
                // splitting it for subsequent extrusion.
                int edgeIdx = SnappiGrid.hitTestEdge(e.getPoint(), mv, wayCorners,
                        SnappiPreferences.getHandleRadius() * 2);
                if (edgeIdx >= 0 && hoveredEdge < 0) {
                    if (e.isShiftDown() || shiftDown) {
                        toggleEdgeSelection(edgeIdx);
                    } else {
                        handleEdgeClickExtrude(e.getPoint(), mouseEN);
                    }
                    mv.repaint();
                    return;
                }
            }
            if (hoveredEdge < 0) {
                // Click away from building: finish current, start new
                // building preserving the current axis orientation
                shrinkwrapWay();
                simplifyWay();
                EastNorth prevU = uAxis;
                EastNorth prevV = vAxis;
                resetState();
                // Restore axes so the next building uses the same orientation
                uAxis = prevU;
                vAxis = prevV;
                if (uAxis != null && vAxis != null) {
                    hasReferenceOrientation = true;
                }
                handleClickIdle(mouseEN, true);
                mv.repaint();
            }
            return;
        }

        switch (phase) {
            case IDLE:
                handleClickIdle(mouseEN, false);
                break;
            case PHASE_ANCHOR:
                handleClickAnchor(mouseEN);
                break;
            case PHASE_DEPTH:
                handleClickDepth(mouseEN);
                break;
            default:
                break;
        }
        mv.repaint();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) return;
        if (phase == Phase.PHASE_EXTRUDE && hoveredEdge >= 0) {
            syncWayCorners();
            if (phase != Phase.PHASE_EXTRUDE || wayCorners == null) return;
            if (!isValidEdgeIndex(hoveredEdge)) {
                hoveredEdge = -1;
                return;
            }
            MapView mv = MainApplication.getMap().mapView;
            dragStart = mv.getEastNorth(e.getX(), e.getY());
            dragPressPoint = e.getPoint();
            dragEdge = hoveredEdge;
            prepareDragEdges(dragEdge);
            shiftDownAtPress = shiftDown || e.isShiftDown();
            splitIntoThirdsAtPress = ctrlDown || e.isControlDown();
            dragging = true;
            wasDragged = false;
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) return;
        if (dragging && phase == Phase.PHASE_EXTRUDE) {
            MapView mv = MainApplication.getMap().mapView;
            if (wasDragged) {
                // Real drag — commit the extrusion. Holding the configured
                // "new polygon" modifier spins the extruded section off into
                // its own way instead of growing the current one.
                EastNorth mouseEN = mv.getEastNorth(e.getX(), e.getY());
                if (isNewPolygonModifierDown()) {
                    commitExtrudeAsNewPolygon(mouseEN);
                } else {
                    commitExtrude(mouseEN);
                }
            } else if (shiftDownAtPress) {
                // Shift-clicking a handle toggles its membership in the
                // parallel-edge extrusion selection.
                toggleEdgeSelection(dragEdge);
            } else if (splitIntoThirdsAtPress) {
                // Ctrl-clicking a handle inserts trisection nodes so each
                // resulting segment can be extruded independently.
                splitEdgeIntoThirds(dragEdge);
            } else {
                // Bare click on a handle — insert a node at the midpoint
                // of that edge so it can be extruded independently
                if (dragEdge >= 0 && wayCorners != null) {
                    int i1 = (dragEdge + 1) % wayCorners.length;
                    EastNorth midEN = SnappiGrid.midpoint(
                            wayCorners[dragEdge], wayCorners[i1]);
                    Point midScreen = mv.getPoint(midEN);
                    handleEdgeClickExtrude(midScreen, midEN);
                }
            }
            clearDragState();
            mv.repaint();
        }
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        MapView mv = MainApplication.getMap().mapView;
        EastNorth mouseEN = mv.getEastNorth(e.getX(), e.getY());

        switch (phase) {
            case PHASE_ANCHOR:
                updateSnapTargetAnchorPhase(mouseEN);
                break;
            case PHASE_DEPTH:
                updateSnapTargetDepthPhase(mouseEN);
                break;
            case PHASE_EXTRUDE:
                if (!dragging && wayCorners != null) {
                    syncWayCorners();
                    // syncWayCorners may call resetState() if the way was deleted
                    // (e.g. user pressed Delete), which nullifies wayCorners.
                    if (wayCorners != null) {
                        sanitizeSelectedEdges();
                        hoveredEdge = SnappiGrid.hitTestEdgeHandle(e.getPoint(), mv, wayCorners);
                    }
                }
                break;
            default:
                break;
        }
        refreshStatusText();
        mv.repaint();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (dragging && phase == Phase.PHASE_EXTRUDE) {
            if (!wasDragged && dragPressPoint != null) {
                double dx = e.getX() - dragPressPoint.x;
                double dy = e.getY() - dragPressPoint.y;
                double threshold = SnappiPreferences.getDragThresholdPx();
                if (dx * dx + dy * dy >= threshold * threshold) {
                    wasDragged = true;
                }
            }
            MainApplication.getMap().mapView.repaint();
        }
    }

    // ------------------------------------------------------------------
    // Keyboard events
    // ------------------------------------------------------------------

    @Override
    public void modifiersExChanged(int modifiers) {
        shiftDown = (modifiers & KeyEvent.SHIFT_DOWN_MASK) != 0;
        ctrlDown = (modifiers & KeyEvent.CTRL_DOWN_MASK) != 0;
        altDown = (modifiers & KeyEvent.ALT_DOWN_MASK) != 0;
    }

    @Override
    public void doKeyPressed(KeyEvent e) {
        MapFrame map = MainApplication.getMap();
        if (map == null) return;
        MapView mv = map.mapView;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE:
                if (phase != Phase.IDLE) {
                    resetState();
                    mv.repaint();
                }
                break;
            case KeyEvent.VK_ENTER:
                finishShape();
                mv.repaint();
                break;
            case KeyEvent.VK_A:
                toggleAngleSnap();
                mv.repaint();
                break;
            case KeyEvent.VK_C:
                halveStep();
                mv.repaint();
                break;
            case KeyEvent.VK_V:
                doubleStep();
                mv.repaint();
                break;
            case KeyEvent.VK_ALT:
                cycleStep();
                mv.repaint();
                break;
            default:
                break;
        }
    }

    @Override
    public void doKeyReleased(KeyEvent e) {
        // no action needed
    }

    private void toggleAngleSnap() {
        angleSnap = !angleSnap;
        if (angleSnap) {
            uAxis = new EastNorth(1, 0);
            vAxis = new EastNorth(0, 1);
            hasReferenceOrientation = true;
        } else {
            // Only remove reference if it came from angle-snap (not from a way)
            if (referenceWay == null) {
                hasReferenceOrientation = false;
            }
        }
        refreshStatusText();
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bounds) {
        switch (phase) {
            case IDLE:
                break;
            case PHASE_ANCHOR:
                paintAnchorPhase(g, mv);
                break;
            case PHASE_DEPTH:
                paintDepthPhase(g, mv);
                break;
            case PHASE_EXTRUDE:
                paintExtrudePhase(g, mv);
                break;
            default:
                break;
        }
    }

    private void paintAnchorPhase(Graphics2D g, MapView mv) {
        if (anchorEN == null || snappedTarget == null || uAxis == null || vAxis == null) return;

        if (hasReferenceOrientation) {
            // 2-click mode: full grid + rectangle preview + length labels
            SnappiGrid.paintGrid(g, mv, anchorEN, snappedTarget,
                    uAxis, vAxis, enStepU(), enStepV(), referenceCorners);
            EastNorth[] preview = SnappiGrid.computeCorners(
                    anchorEN, snappedTarget, uAxis, vAxis, true);
            SnappiGrid.paintLengthLabels(g, mv, preview);
        } else {
            // 3-click mode: line preview (u-axis only)
            SnappiGrid.paintLinePreview(g, mv, anchorEN, snappedTarget,
                    uAxis, enStepU());
        }
    }

    private void paintDepthPhase(Graphics2D g, MapView mv) {
        if (anchorEN == null || snappedTarget == null || uAxis == null || vAxis == null) return;

        SnappiGrid.paintGrid(g, mv, anchorEN, snappedTarget,
                uAxis, vAxis, enStepU(), enStepV(), referenceCorners);
        EastNorth[] preview = SnappiGrid.computeCorners(
                anchorEN, snappedTarget, uAxis, vAxis, true);
        SnappiGrid.paintLengthLabels(g, mv, preview);

        // Also draw adjacent corner dot
        if (adjacentEN != null) {
            SnappiGrid.paintDot(g, mv, adjacentEN, new java.awt.Color(255, 160, 0));
        }
    }

    private void paintExtrudePhase(Graphics2D g, MapView mv) {
        if (wayCorners == null || anchorEN == null || uAxis == null || vAxis == null) return;

        // Combine wayCorners with any referenceCorners so that paintGridLines
        // projects every corner onto the u/v axes and covers the full building
        // bbox — the Euclidean-farthest heuristic fails for L-shaped footprints.
        int refLen = referenceCorners != null ? referenceCorners.length : 0;
        EastNorth[] allCorners = new EastNorth[wayCorners.length + refLen];
        System.arraycopy(wayCorners, 0, allCorners, 0, wayCorners.length);
        if (refLen > 0) {
            System.arraycopy(referenceCorners, 0, allCorners, wayCorners.length, refLen);
        }

        // Draw only grid lines (no simple rect preview) — the actual
        // building polygon may no longer be a simple rectangle after extrusions.
        // target=anchorEN gives zero base extent; allCorners extends it to the
        // full bounding box of the shape.
        SnappiGrid.paintGridLines(g, mv, anchorEN, anchorEN,
                uAxis, vAxis, enStepU(), enStepV(), allCorners);

        // Draw the actual building polygon outline
        SnappiGrid.paintPolygonOutline(g, mv, wayCorners);

        // Edge handles
        SnappiGrid.paintEdgeHandles(g, mv, wayCorners, hoveredEdge, selectedEdges);

        // Length labels on the committed building
        SnappiGrid.paintLengthLabels(g, mv, wayCorners);

        // Extrude preview while dragging
        if (dragging && dragEdge >= 0 && dragStart != null) {
            Point mouseScreen = mv.getMousePosition();
            if (mouseScreen != null) {
                EastNorth mouseEN = mv.getEastNorth(mouseScreen.x, mouseScreen.y);
                double offset = computeExtrudeOffset(mouseEN, dragEdge);
                for (int edge : dragEdges) {
                    if (isValidEdgeIndex(edge)) {
                        SnappiGrid.paintExtrudePreview(g, mv, anchorEN, uAxis, vAxis,
                                enStepU(), enStepV(), wayCorners, edge, offset);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase handlers
    // ------------------------------------------------------------------

    /**
     * IDLE → click: detect reference orientation, place anchor, enter PHASE_ANCHOR.
     *
     * @param mouseEN      click position in EastNorth
     * @param preserveAxes if true, keep the current uAxis/vAxis/hasReferenceOrientation
     *                     (used when starting a new building after click-away)
     */
    private void handleClickIdle(EastNorth mouseEN, boolean preserveAxes) {
        anchorEN = mouseEN;

        // Snap anchor to nearby existing node for precise connections
        Node nearAnchor = findNearNode(anchorEN, SnappiPreferences.getNodeSnapRadius());
        if (nearAnchor != null) {
            anchorEN = nearAnchor.getEastNorth();
        }

        activeStepU = SnappiPreferences.getStepXMetres();
        activeStepV = SnappiPreferences.getStepYMetres();

        // Compute local projection scale factor at the anchor point
        projectionScale = SnappiGrid.projectionScale(anchorEN);

        if (!preserveAxes) {
            // Fresh detection: derive axes from angle-snap, selected way, or nearby building.
            MapView mv = MainApplication.getMap() != null ? MainApplication.getMap().mapView : null;
            ReferenceOrientationDetector.Result ref = ReferenceOrientationDetector.detect(
                    anchorEN, angleSnap, getLayerManager().getEditDataSet(), mv);
            hasReferenceOrientation = ref.hasReferenceOrientation;
            uAxis = ref.uAxis;
            vAxis = ref.vAxis;
            referenceWay = ref.referenceWay;
            referenceCorners = ref.referenceCorners;
        } else {
            // Preserve the previous building's axes; clear way-specific state.
            // Angle-snap still overrides if currently active.
            referenceWay = null;
            referenceCorners = null;
            if (angleSnap) {
                uAxis = new EastNorth(1, 0);
                vAxis = new EastNorth(0, 1);
                hasReferenceOrientation = true;
            }
        }

        phase = Phase.PHASE_ANCHOR;
        updateSnapTargetAnchorPhase(mouseEN);
    }

    /**
     * PHASE_ANCHOR → click: either commit rectangle (2-click) or lock edge (3-click).
     */
    private void handleClickAnchor(EastNorth mouseEN) {
        updateSnapTargetAnchorPhase(mouseEN);
        if (snappedTarget == null || uAxis == null) return;

        if (hasReferenceOrientation) {
            // 2-click mode: commit rectangle directly
            commitRectangle();
            phase = Phase.PHASE_EXTRUDE;
        } else {
            // 3-click mode: lock u-axis direction and u-length, enter PHASE_DEPTH
            adjacentEN = snappedTarget;
            double dx = adjacentEN.east() - anchorEN.east();
            double dy = adjacentEN.north() - anchorEN.north();
            fixedULen = dx * uAxis.east() + dy * uAxis.north();

            // Lock axes now
            hasReferenceOrientation = true;
            phase = Phase.PHASE_DEPTH;
        }
    }

    /**
     * PHASE_DEPTH → click: commit rectangle with the chosen depth.
     */
    private void handleClickDepth(EastNorth mouseEN) {
        updateSnapTargetDepthPhase(mouseEN);
        if (snappedTarget == null) return;
        commitRectangle();
        phase = Phase.PHASE_EXTRUDE;
    }

    // ------------------------------------------------------------------
    // Snap target updates
    // ------------------------------------------------------------------

    /**
     * Updates the snapped target during PHASE_ANCHOR.
     * In 2-click mode (reference orientation): snaps both u and v.
     * In 3-click mode (no reference): axes follow mouse, snaps u only.
     */
    private void updateSnapTargetAnchorPhase(EastNorth mouseEN) {
        if (anchorEN == null) return;

        if (!hasReferenceOrientation) {
            // 3-click mode: axes follow mouse direction, snap u only (line)
            EastNorth[] axes = SnappiGrid.computeAxes(anchorEN, mouseEN);
            uAxis = axes[0];
            vAxis = axes[1];
            snappedTarget = SnappiGrid.snap(mouseEN, anchorEN, uAxis, vAxis,
                    enStepU(), enStepV(), 1, ctrlDown);
        } else {
            // 2-click mode: axes fixed, snap both
            int lockAxis = 0;
            if (shiftDown) {
                double dx = mouseEN.east() - anchorEN.east();
                double dy = mouseEN.north() - anchorEN.north();
                double uProj = Math.abs(dx * uAxis.east() + dy * uAxis.north());
                double vProj = Math.abs(dx * vAxis.east() + dy * vAxis.north());
                lockAxis = (uProj >= vProj) ? 1 : 2;
            }
            snappedTarget = SnappiGrid.snap(mouseEN, anchorEN, uAxis, vAxis,
                    enStepU(), enStepV(), lockAxis, ctrlDown);
        }
    }

    /**
     * Updates the snapped target during PHASE_DEPTH.
     * u-extent is fixed; only v changes.
     */
    private void updateSnapTargetDepthPhase(EastNorth mouseEN) {
        if (anchorEN == null || uAxis == null || vAxis == null) return;

        double dx = mouseEN.east() - anchorEN.east();
        double dy = mouseEN.north() - anchorEN.north();
        double vProj = dx * vAxis.east() + dy * vAxis.north();
        double vSnap = ctrlDown ? vProj : SnappiGrid.snapScalar(vProj, enStepV());

        snappedTarget = new EastNorth(
                anchorEN.east() + fixedULen * uAxis.east() + vSnap * vAxis.east(),
                anchorEN.north() + fixedULen * uAxis.north() + vSnap * vAxis.north());
    }

    // ------------------------------------------------------------------
    // Reference orientation
    // ------------------------------------------------------------------

    /**
     * Finds the nearest existing OSM node within a screen-pixel radius
     * of the given position, for snapping corners to existing features.
     *
     * @param en       the position to search near
     * @param radiusPx hit radius in screen pixels
     * @return the nearest node, or null if none within radius
     */
    private Node findNearNode(EastNorth en, double radiusPx) {
        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null) return null;

        MapView mv = MainApplication.getMap().mapView;
        Point screen = mv.getPoint(en);
        double radiusSq = radiusPx * radiusPx;

        Node best = null;
        double bestDist = radiusSq;

        for (Node n : ds.getNodes()) {
            if (n.isDeleted() || n.isIncomplete()) continue;
            Point np = mv.getPoint(n.getEastNorth());
            double dSq = screen.distanceSq(np);
            if (dSq < bestDist) {
                bestDist = dSq;
                best = n;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Edge click (split edge + prepare for extrusion)
    // ------------------------------------------------------------------

    /**
     * Re-synchronises {@code wayCorners} from the actual way data.
     * This is necessary because undo/redo can revert the way's node list
     * while {@code wayCorners} still holds stale data.
     */
    private void syncWayCorners() {
        if (createdWay == null || createdWay.isDeleted()) {
            resetState();
            return;
        }
        List<Node> nodes = createdWay.getNodes();
        int count = createdWay.isClosed() ? nodes.size() - 1 : nodes.size();
        if (count < 3) {
            resetState();
            return;
        }
        wayCorners = new EastNorth[count];
        for (int i = 0; i < count; i++) {
            wayCorners[i] = nodes.get(i).getEastNorth();
        }
    }

    /**
     * Handles a click on the building outline during PHASE_EXTRUDE.
     * Adds a new node at the nearest grid point on the clicked edge,
     * splitting the edge so the new segment can be extruded.
     */
    private void handleEdgeClickExtrude(Point screenPoint, EastNorth mouseEN) {
        if (wayCorners == null || createdWay == null) return;

        MapView mv = MainApplication.getMap().mapView;
        int edgeIdx = SnappiGrid.hitTestEdge(screenPoint, mv, wayCorners,
                SnappiPreferences.getHandleRadius() * 2);
        if (edgeIdx < 0) return;

        // Find nearest grid point on this edge
        EastNorth gridPt = SnappiGrid.nearestGridPointOnEdge(
                mouseEN, wayCorners, edgeIdx,
                anchorEN, uAxis, vAxis, enStepU(), enStepV());
        if (gridPt == null) return;

        splitEdgeAtPoints(edgeIdx, new EastNorth[]{gridPt},
                tr("Split edge (arkki-snappi)"));
    }

    /**
     * Splits the selected edge at its one-third and two-thirds points.
     *
     * @param edgeIdx edge index in {@code wayCorners}
     */
    private void splitEdgeIntoThirds(int edgeIdx) {
        if (!isValidEdgeIndex(edgeIdx)) return;

        int nextIndex = (edgeIdx + 1) % wayCorners.length;
        EastNorth[] points = SnappiGrid.trisectionPoints(
                wayCorners[edgeIdx], wayCorners[nextIndex]);
        splitEdgeAtPoints(edgeIdx, points, tr("Split edge into thirds (arkki-snappi)"));
    }

    /**
     * Inserts one or more ordered points into an edge using undoable JOSM
     * commands and keeps the cached corner list synchronized.
     *
     * @param edgeIdx     edge index in {@code wayCorners}
     * @param splitPoints points along the edge, ordered from its first endpoint
     * @param commandLabel undo command label
     */
    private void splitEdgeAtPoints(int edgeIdx, EastNorth[] splitPoints,
                                   String commandLabel) {
        if (!isValidEdgeIndex(edgeIdx) || splitPoints == null || splitPoints.length == 0
                || createdWay == null) return;

        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null) return;

        List<Node> insertedNodes = new ArrayList<>(splitPoints.length);
        for (EastNorth splitPoint : splitPoints) {
            if (splitPoint == null) return;
            LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(splitPoint);
            insertedNodes.add(new Node(ll));
        }

        // Build updated node list for the way
        List<Node> oldNodes = createdWay.getNodes();
        List<Node> newNodes = new ArrayList<>(oldNodes);
        // Insert after edgeIdx (which is the index in the closed-way node list).
        newNodes.addAll(edgeIdx + 1, insertedNodes);

        Way updatedWay = new Way(createdWay);
        updatedWay.setNodes(newNodes);

        List<Command> cmds = new ArrayList<>();
        for (Node insertedNode : insertedNodes) {
            cmds.add(new AddCommand(ds, insertedNode));
        }
        cmds.add(new ChangeCommand(createdWay, updatedWay));

        UndoRedoHandler.getInstance().add(
                new SequenceCommand(commandLabel, cmds));

        syncWayCorners();
        selectedEdges.clear();

        Logging.debug("arkki-snappi: split edge {0}, polygon now has {1} corners",
                edgeIdx, wayCorners != null ? wayCorners.length : 0);
    }

    // ------------------------------------------------------------------
    // Data mutations (JOSM Command pattern)
    // ------------------------------------------------------------------

    /**
     * Creates four nodes and a closed way for the snapped rectangle,
     * applying default tags.
     */
    private void commitRectangle() {
        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null) return;

        boolean ccw = SnappiPreferences.isCcwWinding();
        EastNorth[] corners = SnappiGrid.computeCorners(
                anchorEN, snappedTarget, uAxis, vAxis, ccw);

        List<Command> cmds = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();

        for (int i = 0; i < corners.length; i++) {
            Node nearNode = findNearNode(corners[i], SnappiPreferences.getNodeSnapRadius());
            if (nearNode != null) {
                nodes.add(nearNode);
                corners[i] = nearNode.getEastNorth();
            } else {
                LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(corners[i]);
                Node n = new Node(ll);
                cmds.add(new AddCommand(ds, n));
                nodes.add(n);
            }
        }

        Way way = new Way();
        List<Node> wayNodes = new ArrayList<>(nodes);
        wayNodes.add(nodes.get(0)); // close the way
        way.setNodes(wayNodes);

        for (Map.Entry<String, String> tag : SnappiPreferences.getDefaultTags()) {
            way.put(tag.getKey(), tag.getValue());
        }

        cmds.add(new AddCommand(ds, way));

        UndoRedoHandler.getInstance().add(
                new SequenceCommand(tr("Create building (arkki-snappi)"), cmds));

        createdWay = way;
        wayCorners = corners;

        if (SnappiPreferences.isAutoSelect()) {
            ds.setSelected(Collections.singleton(way));
        }

        Logging.debug("arkki-snappi: created way {0} with {1} nodes",
                way.getUniqueId(), corners.length);
    }

    /**
     * Commits one or more parallel edge extrusions in a single undoable
     * command, then applies the normal shrinkwrap and simplification cleanup.
     *
     * <p>The insert-then-cleanup approach handles both full-edge extrusions
     * and sub-edge extrusions after a split. Inserting every selected edge
     * before running cleanup also keeps a multi-edge drag atomic from the
     * user's point of view.</p>
     */
    private void commitExtrude(EastNorth mouseEN) {
        if (wayCorners == null || createdWay == null) return;

        List<Integer> edges = currentExtrusionEdges();
        if (edges.isEmpty()) return;

        int activeEdge = dragEdge >= 0 ? dragEdge : edges.get(0);
        double offset = computeExtrudeOffset(mouseEN, activeEdge);
        if (Math.abs(offset) < MIN_EXTRUDE_THRESHOLD) return;

        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null) return;

        List<EdgeExtrusion> extrusions = new ArrayList<>();
        List<Node> existingNodes = createdWay.getNodes();
        for (int edgeIndex : edges) {
            if (!isValidEdgeIndex(edgeIndex)) continue;

            EastNorth normal = SnappiGrid.computeOutwardNormal(wayCorners, edgeIndex);
            double moveE = offset * normal.east();
            double moveN = offset * normal.north();
            int nextIndex = (edgeIndex + 1) % wayCorners.length;
            EastNorth start = wayCorners[edgeIndex];
            EastNorth end = wayCorners[nextIndex];

            Node newNode0 = new Node(ProjectionRegistry.getProjection().eastNorth2latlon(
                    new EastNorth(start.east() + moveE, start.north() + moveN)));
            Node newNode1 = new Node(ProjectionRegistry.getProjection().eastNorth2latlon(
                    new EastNorth(end.east() + moveE, end.north() + moveN)));
            extrusions.add(new EdgeExtrusion(edgeIndex, newNode0, newNode1));
        }
        if (extrusions.isEmpty()) return;

        // Insert from the end of the way so original edge indices remain valid.
        extrusions.sort(Comparator.comparingInt(e -> -e.edgeIndex));
        List<Node> updatedNodes = new ArrayList<>(existingNodes);
        for (EdgeExtrusion extrusion : extrusions) {
            updatedNodes.add(extrusion.edgeIndex + 1, extrusion.newNode1);
            updatedNodes.add(extrusion.edgeIndex + 1, extrusion.newNode0);
        }

        Way updatedWay = new Way(createdWay);
        updatedWay.setNodes(updatedNodes);

        List<Command> cmds = new ArrayList<>();
        for (EdgeExtrusion extrusion : extrusions) {
            cmds.add(new AddCommand(ds, extrusion.newNode0));
            cmds.add(new AddCommand(ds, extrusion.newNode1));
        }
        cmds.add(new ChangeCommand(createdWay, updatedWay));

        String label = extrusions.size() == 1
                ? tr("Extrude edge (arkki-snappi)")
                : tr("Extrude {0} parallel edges (arkki-snappi)", extrusions.size());
        UndoRedoHandler.getInstance().add(new SequenceCommand(label, cmds));

        syncWayCorners();
        shrinkwrapWay();
        simplifyWay();

        selectedEdges.clear();
        hoveredEdge = -1;

        Logging.debug("arkki-snappi: extruded {0} parallel edge(s) by {1} m, "
                        + "polygon now has {2} corners",
                extrusions.size(), offset, wayCorners != null ? wayCorners.length : 0);
    }

    /**
     * Commits one or more edge extrusions as new tagged ways instead of
     * growing the current way. The selected edges each produce a separate
     * sewn-together polygon, and the polygon from the dragged edge remains
     * active for continued chaining.
     *
     * @param mouseEN mouse position at drag release
     */
    private void commitExtrudeAsNewPolygon(EastNorth mouseEN) {
        if (wayCorners == null || createdWay == null) return;

        List<Integer> edges = currentExtrusionEdges();
        if (edges.isEmpty()) return;

        int activeEdge = dragEdge >= 0 ? dragEdge : edges.get(0);
        double offset = computeExtrudeOffset(mouseEN, activeEdge);
        if (Math.abs(offset) < MIN_EXTRUDE_THRESHOLD) return;

        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null) return;

        // The shared edge nodes come straight from the existing way's node
        // list, so every new polygon is sewn to the source way.
        List<Node> existingNodes = createdWay.getNodes();
        List<NewPolygonExtrusion> extrusions = new ArrayList<>();
        for (int edgeIndex : edges) {
            if (!isValidEdgeIndex(edgeIndex)) continue;

            EastNorth normal = SnappiGrid.computeOutwardNormal(wayCorners, edgeIndex);
            EastNorth[] splitCorners = SnappiGrid.computeSplitPolygonCorners(
                    wayCorners, edgeIndex, offset * normal.east(), offset * normal.north());
            int nextIndex = (edgeIndex + 1) % wayCorners.length;
            Node sharedNode0 = existingNodes.get(edgeIndex);
            Node sharedNode1 = existingNodes.get(nextIndex);
            Node newNode0 = new Node(
                    ProjectionRegistry.getProjection().eastNorth2latlon(splitCorners[1]));
            Node newNode1 = new Node(
                    ProjectionRegistry.getProjection().eastNorth2latlon(splitCorners[2]));

            List<Node> newWayNodes = new ArrayList<>(5);
            newWayNodes.add(sharedNode0);
            newWayNodes.add(newNode0);
            newWayNodes.add(newNode1);
            newWayNodes.add(sharedNode1);
            newWayNodes.add(sharedNode0); // close the way

            Way newWay = new Way();
            newWay.setNodes(newWayNodes);
            for (Map.Entry<String, String> tag : SnappiPreferences.getDefaultTags()) {
                newWay.put(tag.getKey(), tag.getValue());
            }
            extrusions.add(new NewPolygonExtrusion(edgeIndex, newWay, splitCorners,
                    newNode0, newNode1));
        }
        if (extrusions.isEmpty()) return;

        List<Command> cmds = new ArrayList<>();
        List<Way> newWays = new ArrayList<>();
        for (NewPolygonExtrusion extrusion : extrusions) {
            cmds.add(new AddCommand(ds, extrusion.newNode0));
            cmds.add(new AddCommand(ds, extrusion.newNode1));
            cmds.add(new AddCommand(ds, extrusion.way));
            newWays.add(extrusion.way);
        }

        String label = extrusions.size() == 1
                ? tr("Extrude new polygon (arkki-snappi)")
                : tr("Extrude {0} new polygons (arkki-snappi)", extrusions.size());
        UndoRedoHandler.getInstance().add(new SequenceCommand(label, cmds));

        NewPolygonExtrusion active = extrusions.get(0);
        for (NewPolygonExtrusion extrusion : extrusions) {
            if (extrusion.edgeIndex == activeEdge) {
                active = extrusion;
                break;
            }
        }
        createdWay = active.way;
        wayCorners = active.corners;

        if (SnappiPreferences.isAutoSelect()) {
            ds.setSelected(newWays);
        }

        shrinkwrapWay();
        simplifyWay();

        selectedEdges.clear();
        hoveredEdge = -1;

        Logging.debug("arkki-snappi: extruded {0} edge(s) by {1} m into new way {2}",
                extrusions.size(), offset, active.way.getUniqueId());
    }

    /** Returns the edge snapshot for the current drag or the active edge. */
    private List<Integer> currentExtrusionEdges() {
        if (!dragEdges.isEmpty()) {
            return new ArrayList<>(dragEdges);
        }
        if (dragEdge >= 0) {
            return Collections.singletonList(dragEdge);
        }
        if (hoveredEdge >= 0) {
            return Collections.singletonList(hoveredEdge);
        }
        return Collections.emptyList();
    }

    /** Returns whether an edge index addresses a real edge in the current way. */
    private boolean isValidEdgeIndex(int edgeIndex) {
        return wayCorners != null && edgeIndex >= 0 && edgeIndex < wayCorners.length;
    }

    /**
     * Toggles an edge in the selection used for simultaneous extrusion.
     * Selecting a different orientation starts a new selection group.
     */
    private void toggleEdgeSelection(int edgeIndex) {
        if (!isValidEdgeIndex(edgeIndex)) return;
        if (selectedEdges.remove(edgeIndex)) {
            refreshStatusText();
            return;
        }

        if (!selectedEdges.isEmpty()) {
            int firstEdge = selectedEdges.iterator().next();
            if (!SnappiGrid.areEdgesParallel(wayCorners, firstEdge, edgeIndex)) {
                selectedEdges.clear();
            }
        }
        selectedEdges.add(edgeIndex);
        refreshStatusText();
    }

    /** Captures the selected parallel edges when a drag begins. */
    private void prepareDragEdges(int edgeIndex) {
        dragEdges.clear();
        if (selectedEdges.contains(edgeIndex)) {
            for (int selectedEdge : selectedEdges) {
                if (isValidEdgeIndex(selectedEdge)
                        && SnappiGrid.areEdgesParallel(wayCorners, edgeIndex, selectedEdge)) {
                    dragEdges.add(selectedEdge);
                }
            }
        }
        if (dragEdges.isEmpty()) {
            dragEdges.add(edgeIndex);
        }
    }

    /** Removes selections that no longer refer to the current way. */
    private void sanitizeSelectedEdges() {
        if (wayCorners == null) {
            selectedEdges.clear();
            return;
        }
        selectedEdges.removeIf(edge -> !isValidEdgeIndex(edge));
        if (selectedEdges.size() > 1) {
            int firstEdge = selectedEdges.iterator().next();
            selectedEdges.removeIf(edge ->
                    !SnappiGrid.areEdgesParallel(wayCorners, firstEdge, edge));
        }
    }

    /** Clears all state associated with a completed or cancelled drag. */
    private void clearDragState() {
        dragging = false;
        wasDragged = false;
        dragStart = null;
        dragPressPoint = null;
        dragEdge = -1;
        dragEdges.clear();
        shiftDownAtPress = false;
        splitIntoThirdsAtPress = false;
    }

    /**
     * Returns true when the configured "extrude as new polygon" modifier
     * (see {@link SnappiPreferences#getNewPolygonModifier()}) is currently
     * held down.
     */
    private boolean isNewPolygonModifierDown() {
        switch (SnappiPreferences.getNewPolygonModifier()) {
            case "Ctrl":
                return ctrlDown;
            case "Alt":
                return altDown;
            case "None":
                return false;
            case "Shift":
                return shiftDown;
            case "Ctrl+Shift":
            default:
                return ctrlDown && shiftDown;
        }
    }

    /** Computes the snapped offset using a specific edge as the drag source. */
    private double computeExtrudeOffset(EastNorth mouseEN, int edgeIndex) {
        if (dragStart == null || mouseEN == null || !isValidEdgeIndex(edgeIndex)
                || uAxis == null || vAxis == null) return 0;

        EastNorth normal = SnappiGrid.computeOutwardNormal(wayCorners, edgeIndex);
        double dx = mouseEN.east() - dragStart.east();
        double dy = mouseEN.north() - dragStart.north();
        double rawOffset = dx * normal.east() + dy * normal.north();

        // Plain Ctrl means free mode (no snapping). Ctrl+Shift is the default
        // "extrude as new polygon" chord, and that feature needs precise grid
        // steps (e.g. regularly spaced verge sections) — so Shift being held
        // alongside Ctrl keeps snapping active instead of falling back to free mode.
        if (ctrlDown && !shiftDown) return rawOffset;

        // Determine which step to use based on normal alignment with axes
        double uDot = Math.abs(normal.east() * uAxis.east() + normal.north() * uAxis.north());
        double step = uDot > 0.5 ? enStepU() : enStepV();
        return SnappiGrid.snapScalar(rawOffset, step);
    }

    // ------------------------------------------------------------------
    // Step size adjustment
    // ------------------------------------------------------------------

    /**
     * Halves the active snap step size (C key).
     */
    private void halveStep() {
        activeStepU /= 2.0;
        activeStepV /= 2.0;
        refreshStatusText();
    }

    /**
     * Doubles the active snap step size (V key).
     */
    private void doubleStep() {
        activeStepU *= 2.0;
        activeStepV *= 2.0;
        refreshStatusText();
    }

    /**
     * Cycles the active snap step through the preset list (Alt key).
     * Both U and V steps are set to the same preset value.
     */
    private void cycleStep() {
        List<Double> presets = SnappiPreferences.getStepPresets();
        if (presets.isEmpty()) return;
        presetIndex = (presetIndex + 1) % presets.size();
        double step = presets.get(presetIndex);
        activeStepU = step;
        activeStepV = step;
        refreshStatusText();
    }

    // ------------------------------------------------------------------
    // Status bar
    // ------------------------------------------------------------------

    private void refreshStatusText() {
        MapFrame map = MainApplication.getMap();
        if (map == null) return;

        String stepStr = SnappiPreferences.formatStepPair(activeStepU, activeStepV);
        String angleStr = angleSnap ? " [A: cardinal]" : "";

        switch (phase) {
            case IDLE:
                map.statusLine.setHelpText(
                        tr("Click to place the first building corner. Step: {0}{1}",
                                stepStr, angleStr));
                break;
            case PHASE_ANCHOR:
                if (hasReferenceOrientation) {
                    if (snappedTarget != null && anchorEN != null) {
                        double dist = SnappiGrid.realWorldDistance(anchorEN, snappedTarget);
                        map.statusLine.setHelpText(
                                tr("Click to place the opposite corner. "
                                        + "Distance: {0}. Step: {1}{2}",
                                        SnappiPreferences.formatStep(dist),
                                        stepStr, angleStr));
                    } else {
                        map.statusLine.setHelpText(
                                tr("Move mouse to the opposite corner. Step: {0}{1}",
                                        stepStr, angleStr));
                    }
                } else {
                    map.statusLine.setHelpText(
                            tr("Click to set the adjacent corner (edge direction). Step: {0}{1}",
                                    stepStr, angleStr));
                }
                break;
            case PHASE_DEPTH:
                map.statusLine.setHelpText(
                        tr("Move perpendicular, click to set building depth. Step: {0}{1}",
                                stepStr, angleStr));
                break;
            case PHASE_EXTRUDE:
                String newPolyModifier = SnappiPreferences.getNewPolygonModifier();
                String newPolyStr = "None".equals(newPolyModifier)
                        ? ""
                        : tr(" {0}: extrude as new polygon.", newPolyModifier);
                String selectionStr = selectedEdges.isEmpty()
                        ? ""
                        : tr(" {0} parallel edge(s) selected.", selectedEdges.size());
                map.statusLine.setHelpText(
                        tr("Drag handle to extrude, Shift-click handles to select "
                                + "parallel edges, Ctrl-click handle splits into thirds, "
                                + "click edge to split, Shift keeps nodes."
                                + "{2}{3} Enter/Esc to finish. Step: {0}{1}",
                                stepStr, angleStr, newPolyStr, selectionStr));
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------

    /**
     * Finishes the current shape: commits any in-progress rectangle,
     * optionally shrinkwraps and simplifies the way, then resets to IDLE.
     * Holding Shift skips simplification for this finish operation only.
     */
    private void finishShape() {
        switch (phase) {
            case PHASE_ANCHOR:
                if (snappedTarget != null && uAxis != null && hasReferenceOrientation) {
                    commitRectangle();
                    shrinkwrapWay();
                    simplifyWay();
                }
                break;
            case PHASE_DEPTH:
                if (snappedTarget != null) {
                    commitRectangle();
                    shrinkwrapWay();
                    simplifyWay();
                }
                break;
            case PHASE_EXTRUDE:
                shrinkwrapWay();
                simplifyWay();
                break;
            default:
                break;
        }
        resetState();
    }

    /**
     * Resolves self-intersections in the committed way by computing
     * its outer boundary (shrinkwrap). Replaces the way's nodes with
     * the outermost outline; any orphaned interior nodes are deleted.
     */
    private void shrinkwrapWay() {
        if (!SnappiPreferences.isAutoShrinkwrap()) return;
        if (createdWay == null || wayCorners == null || wayCorners.length < 4) return;
        if (!SnappiShrinkwrap.isSelfIntersecting(wayCorners)) return;

        EastNorth[] outer = SnappiShrinkwrap.computeOuterBoundary(wayCorners);
        if (outer == null || outer.length < 3) return;

        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null) return;

        // Build new nodes for the outer boundary, reusing existing ones
        // where they coincide
        List<Command> cmds = new ArrayList<>();
        List<Node> oldNodes = createdWay.getNodes();
        List<Node> newWayNodes = new ArrayList<>();

        for (EastNorth en : outer) {
            Node existing = findMatchingNode(oldNodes, en);
            if (existing != null) {
                newWayNodes.add(existing);
            } else {
                LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(en);
                Node n = new Node(ll);
                cmds.add(new AddCommand(ds, n));
                newWayNodes.add(n);
            }
        }
        newWayNodes.add(newWayNodes.get(0)); // close the way

        Way updatedWay = new Way(createdWay);
        updatedWay.setNodes(newWayNodes);
        cmds.add(new ChangeCommand(createdWay, updatedWay));

        // Delete orphaned old nodes that are no longer in the way
        List<Node> nodesToDelete = new ArrayList<>();
        int closedCount = createdWay.isClosed()
                ? oldNodes.size() - 1 : oldNodes.size();
        for (int i = 0; i < closedCount; i++) {
            Node old = oldNodes.get(i);
            if (!newWayNodes.contains(old) && !old.hasKeys()
                    && old.getParentWays().size() <= 1) {
                nodesToDelete.add(old);
            }
        }
        if (!nodesToDelete.isEmpty()) {
            cmds.add(new DeleteCommand(ds, nodesToDelete));
        }

        UndoRedoHandler.getInstance().add(
                new SequenceCommand(tr("Shrinkwrap building (arkki-snappi)"), cmds));

        // Update cached corners
        wayCorners = outer;

        Logging.debug("arkki-snappi: shrinkwrap resolved self-intersection, "
                + "polygon now has {0} corners", outer.length);
    }

    /**
     * Finds a node in the list whose EastNorth position matches the target
     * within a tight tolerance (1 mm).
     */
    private static Node findMatchingNode(List<Node> nodes, EastNorth target) {
        for (Node n : nodes) {
            EastNorth en = n.getEastNorth();
            if (Math.abs(en.east() - target.east()) < 1e-3 &&
                    Math.abs(en.north() - target.north()) < 1e-3) {
                return n;
            }
        }
        return null;
    }

    /**
     * Removes collinear (non-corner) nodes and merges coincident duplicate
     * nodes from the committed way.
     *
     * <p>Collinear nodes (lying on the straight line between their neighbours)
     * are dropped. Coincident nodes — distinct Node objects at (nearly) the
     * same position — are collapsed to a single node. This cleans up the
     * zero-length edges left when an edge is extruded out and immediately
     * back in: the intermediate nodes are collinear, but the new endpoints
     * land back on existing corners, leaving duplicate vertices behind.</p>
     */
    private void simplifyWay() {
        if (!shouldAutoSimplify()) return;
        if (createdWay == null || wayCorners == null || wayCorners.length <= 4) return;

        EastNorth[] simplified = SnappiGrid.simplifyCorners(wayCorners);
        if (simplified == wayCorners || simplified.length == wayCorners.length) return;

        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null) return;

        // Map each simplified corner back to a way node. Coincident
        // duplicates resolve to the most useful node (tagged or shared
        // with other ways).
        List<Node> oldNodes = createdWay.getNodes();
        List<Node> newNodes = new ArrayList<>(simplified.length);
        for (EastNorth en : simplified) {
            Node node = findBestMatchingNode(oldNodes, en);
            if (node == null) return; // defensive; every corner must match
            newNodes.add(node);
        }

        // Delete orphaned nodes: no tags, and after the change no longer
        // referenced by any way.
        List<Node> nodesToDelete = new ArrayList<>();
        for (int i = 0; i < wayCorners.length; i++) {
            Node node = oldNodes.get(i);
            if (!newNodes.contains(node) && !node.hasKeys()
                    && node.getParentWays().size() <= 1) {
                nodesToDelete.add(node);
            }
        }

        newNodes.add(newNodes.get(0)); // close the way

        Way updatedWay = new Way(createdWay);
        updatedWay.setNodes(newNodes);

        List<Command> cmds = new ArrayList<>();
        cmds.add(new ChangeCommand(createdWay, updatedWay));
        if (!nodesToDelete.isEmpty()) {
            cmds.add(new DeleteCommand(ds, nodesToDelete));
        }

        UndoRedoHandler.getInstance().add(
                new SequenceCommand(tr("Simplify building (arkki-snappi)"), cmds));

        int removed = wayCorners.length - simplified.length;
        wayCorners = simplified;

        Logging.debug("arkki-snappi: simplified way, removed {0} nodes", removed);
    }

    /**
     * Finds the node from the given list whose position matches the target
     * within a tight tolerance (1 mm), preferring nodes with tags or shared
     * by other ways so coincident corners keep the most useful node.
     *
     * @return the best matching node, or null if none matches
     */
    private static Node findBestMatchingNode(List<Node> nodes, EastNorth target) {
        Node best = null;
        int bestPriority = -1;
        for (Node n : nodes) {
            EastNorth en = n.getEastNorth();
            if (en == null) continue;
            if (Math.abs(en.east() - target.east()) < 1e-3 &&
                    Math.abs(en.north() - target.north()) < 1e-3) {
                int priority = nodePriority(n);
                if (priority > bestPriority) {
                    bestPriority = priority;
                    best = n;
                }
            }
        }
        return best;
    }

    /**
     * Priority score for choosing between coincident nodes during a merge:
     * prefer nodes with tags or nodes shared with other ways.
     */
    private static int nodePriority(Node n) {
        return (n.hasKeys() ? 1 : 0) + Math.max(0, n.getParentWays().size() - 1);
    }

    /** Returns true when auto-simplification should run for this operation. */
    private boolean shouldAutoSimplify() {
        return SnappiPreferences.isAutoSimplify() && !shiftDown;
    }

    /** Resets all transient state and returns to IDLE. */
    private void resetState() {
        phase = Phase.IDLE;
        anchorEN = null;
        snappedTarget = null;
        uAxis = null;
        vAxis = null;
        adjacentEN = null;
        fixedULen = 0;
        hasReferenceOrientation = angleSnap; // preserve angle-snap toggle
        referenceWay = null;
        referenceCorners = null;
        createdWay = null;
        wayCorners = null;
        hoveredEdge = -1;
        selectedEdges.clear();
        clearDragState();
        projectionScale = 1.0;
        refreshStatusText();
    }
}
