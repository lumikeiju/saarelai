// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.improvenode;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.util.Collection;
import java.util.Collections;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.data.preferences.NamedColorProperty;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.util.ModifierExListener;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.PreferenceChangeEvent;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Map mode for one-click correction of standalone nodes.
 *
 * <p>The mode intentionally limits itself to nodes that are not members of any
 * way, which keeps light poles, trees, utility points, and similar imported POI
 * datasets separate from topological way editing.</p>
 *
 * @author Lumikeiju, 2026
 */
public class ImproveNodeAccuracyAction extends MapMode implements MapViewPaintable,
        DataSelectionListener, ModifierExListener {

    private static final long serialVersionUID = 1L;

    private transient MapView mapView;
    private transient Node candidateNode;

    private Point mousePos;
    private boolean dragging;
    private boolean shiftDown;
    private boolean ctrlDown;
    private boolean altDown;

    private final Cursor cursorMove;
    private final Cursor cursorMoveLock;
    private final Cursor cursorAdd;
    private final Cursor cursorDelete;

    private Color guideColor;
    private Color addColor;
    private Color deleteColor;
    private transient Stroke moveStroke;
    private transient Stroke addStroke;
    private transient Stroke deleteStroke;
    private int dotSize;
    private int hitRadiusPixels;

    private String oldModeHelpText = "";

    /**
     * Constructs a new {@code ImproveNodeAccuracyAction}.
     */
    public ImproveNodeAccuracyAction() {
        super(tr("Improve Node"), "improvenode",
                tr("Improve standalone node positions"),
                Shortcut.registerShortcut("mapmode:ImproveNode",
                        tr("Mode: {0}", tr("Improve Node")),
                        KeyEvent.VK_N, Shortcut.DIRECT),
                Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

        cursorMove = ImageProvider.getCursor("crosshair", null);
        cursorMoveLock = ImageProvider.getCursor("crosshair", "lock");
        cursorAdd = ImageProvider.getCursor("crosshair", "addnode");
        cursorDelete = ImageProvider.getCursor("crosshair", "delete_node");
        readPreferences();
    }

    @Override
    public void enterMode() {
        if (!isEnabled()) {
            return;
        }
        super.enterMode();

        MapFrame map = MainApplication.getMap();
        if (map == null || getLayerManager().getEditDataSet() == null) {
            return;
        }

        mapView = map.mapView;
        mousePos = null;
        candidateNode = null;
        oldModeHelpText = "";

        map.mapView.addMouseListener(this);
        map.mapView.addMouseMotionListener(this);
        map.mapView.addTemporaryLayer(this);
        map.keyDetector.addModifierExListener(this);
        SelectionEventManager.getInstance().addSelectionListener(this);

        updateCursor();
        updateStatusLine();
    }

    @Override
    public void exitMode() {
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.removeMouseListener(this);
            map.mapView.removeMouseMotionListener(this);
            map.mapView.removeTemporaryLayer(this);
            map.keyDetector.removeModifierExListener(this);
        }
        SelectionEventManager.getInstance().removeSelectionListener(this);
        candidateNode = null;
        mousePos = null;
        MainApplication.getLayerManager().invalidateEditLayer();
        super.exitMode();
    }

    @Override
    protected void readPreferences() {
        guideColor = new NamedColorProperty(marktr("improve node guide line"), Color.RED).get();
        addColor = new NamedColorProperty(marktr("improve node add preview"), new Color(80, 180, 80, 220)).get();
        deleteColor = new NamedColorProperty(marktr("improve node delete preview"), new Color(220, 80, 80, 220)).get();
        moveStroke = GuiHelper.getCustomizedStroke(Config.getPref().get("improvenode.stroke.move-node", "2"));
        addStroke = GuiHelper.getCustomizedStroke(Config.getPref().get("improvenode.stroke.add-node", "1 4"));
        deleteStroke = GuiHelper.getCustomizedStroke(Config.getPref().get("improvenode.stroke.delete-node", "2"));
        dotSize = Config.getPref().getInt("improvenode.dot-size", 8);
        hitRadiusPixels = Config.getPref().getInt("improvenode.hit-radius-pixels", 48);
    }

    @Override
    public boolean layerIsSupported(Layer layer) {
        return layer instanceof OsmDataLayer;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(getLayerManager().getEditLayer() != null);
    }

    @Override
    protected void updateStatusLine() {
        MapFrame map = MainApplication.getMap();
        if (map == null) {
            return;
        }

        String newModeHelpText = getModeHelpText();
        if (!newModeHelpText.equals(oldModeHelpText)) {
            oldModeHelpText = newModeHelpText;
            map.statusLine.setHelpText(newModeHelpText);
            map.statusLine.repaint();
        }
    }

    @Override
    public String getModeHelpText() {
        if (ctrlDown && !altDown) {
            return tr("Click to add a new standalone node. Release Ctrl to move existing standalone nodes.");
        }
        if (altDown && !ctrlDown) {
            return tr("Click to delete the highlighted untagged standalone node. Release Alt to move nodes.");
        }
        if (candidateNode != null) {
            return tr("Click to move the highlighted standalone node here. Hold Shift to keep it locked while aiming.");
        }
        return tr("Move near a standalone node, then click its corrected position. Ctrl adds, Alt deletes untagged nodes.");
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        EastNorth newPointEN = getNewPointEN();
        Point newPoint = newPointEN == null ? null : mv.getPoint(newPointEN);

        if (ctrlDown && !altDown && newPoint != null) {
            g.setColor(addColor);
            g.setStroke(addStroke);
            drawDot(g, newPoint, dotSize);
            return;
        }

        if (candidateNode == null) {
            return;
        }

        Point candidatePoint = mv.getPoint(candidateNode);
        g.setColor(altDown && !ctrlDown ? deleteColor : guideColor);
        drawDot(g, candidatePoint, dotSize);

        if (altDown && !ctrlDown) {
            g.setStroke(deleteStroke);
            int radius = Math.max(dotSize, 10);
            g.draw(new Line2D.Double(candidatePoint.x - radius, candidatePoint.y - radius,
                    candidatePoint.x + radius, candidatePoint.y + radius));
            g.draw(new Line2D.Double(candidatePoint.x - radius, candidatePoint.y + radius,
                    candidatePoint.x + radius, candidatePoint.y - radius));
            return;
        }

        if (newPoint != null) {
            g.setStroke(moveStroke);
            g.draw(new Line2D.Double(candidatePoint, newPoint));
            drawDot(g, newPoint, Math.max(4, dotSize - 2));
        }
    }

    @Override
    public void modifiersExChanged(int modifiers) {
        if (!MainApplication.isDisplayingMapView()) {
            return;
        }
        updateModifierState(modifiers);
        updateCandidateIfNeeded();
        updateCursor();
        updateStatusLine();
        MainApplication.getLayerManager().invalidateEditLayer();
    }

    @Override
    public void selectionChanged(SelectionChangeEvent event) {
        updateCandidateIfNeeded();
        updateCursor();
        updateStatusLine();
        MainApplication.getLayerManager().invalidateEditLayer();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        dragging = true;
        mouseMoved(e);
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (!isEnabled()) {
            return;
        }
        mousePos = e.getPoint();
        updateModifierState(e.getModifiersEx());
        updateCandidateIfNeeded();
        updateCursor();
        updateStatusLine();
        MainApplication.getLayerManager().invalidateEditLayer();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        boolean wasDragging = dragging;
        dragging = false;

        if (!isEnabled() || e.getButton() != MouseEvent.BUTTON1) {
            return;
        }

        mousePos = e.getPoint();
        updateModifierState(e.getModifiersEx());
        if (!shiftDown && !wasDragging) {
            updateCandidateIfNeeded();
        }

        EastNorth newPointEN = getNewPointEN();
        if (newPointEN == null) {
            return;
        }

        if (altDown && !ctrlDown) {
            deleteCandidateNode();
        } else {
            if (new Node(newPointEN).isOutSideWorld()) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                        tr("Cannot place a node outside of the world."),
                        tr("Warning"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (ctrlDown) {
                addStandaloneNode(newPointEN);
            } else {
                moveCandidateNode(newPointEN);
            }
        }

        updateCandidateIfNeeded();
        updateCursor();
        updateStatusLine();
        MainApplication.getLayerManager().invalidateEditLayer();
    }

    @Override
    public void mouseExited(MouseEvent e) {
        if (!isEnabled()) {
            return;
        }
        if (!dragging && !shiftDown) {
            mousePos = null;
            candidateNode = null;
        }
        updateStatusLine();
        MainApplication.getLayerManager().invalidateEditLayer();
    }

    private EastNorth getNewPointEN() {
        if (mapView == null || mousePos == null) {
            return null;
        }
        return mapView.getEastNorth(mousePos.x, mousePos.y);
    }

    private void updateCandidateIfNeeded() {
        if (ctrlDown && !altDown) {
            candidateNode = null;
            return;
        }
        if ((shiftDown || dragging) && candidateNode != null) {
            return;
        }
        if (mousePos == null || mapView == null) {
            candidateNode = null;
            return;
        }

        DataSet dataSet = getLayerManager().getEditDataSet();
        Collection<Node> selectedNodes = ImproveNodeAccuracyHelper.getValidSelectedNodes(dataSet);
        if (!selectedNodes.isEmpty()) {
            candidateNode = ImproveNodeAccuracyHelper.findClosestNode(mapView, selectedNodes, mousePos, 0);
        } else {
            candidateNode = ImproveNodeAccuracyHelper.findCandidateNode(mapView, dataSet, mousePos, hitRadiusPixels);
        }
    }

    private void updateCursor() {
        if (mapView == null) {
            return;
        }
        if (!isEnabled()) {
            mapView.setNewCursor(null, this);
        } else if (altDown && !ctrlDown) {
            mapView.setNewCursor(cursorDelete, this);
        } else if (ctrlDown && !altDown) {
            mapView.setNewCursor(cursorAdd, this);
        } else if (shiftDown || dragging) {
            mapView.setNewCursor(cursorMoveLock, this);
        } else {
            mapView.setNewCursor(cursorMove, this);
        }
    }

    private void updateModifierState(int modifiers) {
        shiftDown = (modifiers & KeyEvent.SHIFT_DOWN_MASK) != 0;
        ctrlDown = (modifiers & KeyEvent.CTRL_DOWN_MASK) != 0;
        altDown = (modifiers & KeyEvent.ALT_DOWN_MASK) != 0;
    }

    private void addStandaloneNode(EastNorth newPointEN) {
        DataSet dataSet = getLayerManager().getEditDataSet();
        if (dataSet == null) {
            return;
        }
        Node newNode = new Node(ProjectionRegistry.getProjection().eastNorth2latlon(newPointEN));
        UndoRedoHandler.getInstance().add(new AddCommand(dataSet, newNode));
    }

    private void moveCandidateNode(EastNorth newPointEN) {
        if (candidateNode == null) {
            return;
        }
        EastNorth nodeEastNorth = candidateNode.getEastNorth();
        if (nodeEastNorth == null || nodeEastNorth.equals(newPointEN)) {
            return;
        }
        UndoRedoHandler.getInstance().add(new MoveCommand(candidateNode,
                newPointEN.east() - nodeEastNorth.east(),
                newPointEN.north() - nodeEastNorth.north()));
    }

    private void deleteCandidateNode() {
        if (candidateNode == null) {
            return;
        }
        if (candidateNode.isTagged()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    tr("Cannot delete a tagged node."),
                    tr("Error"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        Command deleteCommand = DeleteCommand.delete(Collections.singletonList(candidateNode), true);
        if (deleteCommand != null) {
            UndoRedoHandler.getInstance().add(deleteCommand);
        }
    }

    private static void drawDot(Graphics2D g, Point point, int size) {
        int dotSize = Math.max(2, size);
        Stroke oldStroke = g.getStroke();
        g.setStroke(new BasicStroke(1.0f));
        g.fillRect(point.x - dotSize / 2, point.y - dotSize / 2, dotSize, dotSize);
        g.setStroke(oldStroke);
    }

    @Override
    public void preferenceChanged(PreferenceChangeEvent event) {
        super.preferenceChanged(event);
        if (event.getKey().startsWith("improvenode")
                || event.getKey().startsWith("color.improve.node")) {
            readPreferences();
            MainApplication.getLayerManager().invalidateEditLayer();
        }
    }
}
