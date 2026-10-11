// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.improvenode;

import java.awt.Point;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MapView;

/**
 * Static utility class containing helper methods for standalone node improvement operations.
 *
 * @author Lumikeiju, 2026
 */
final class ImproveNodeAccuracyHelper {

    private ImproveNodeAccuracyHelper() {
        // Hide default constructor for utils classes
    }

    /**
     * Checks if a node is valid for improvement operations.
    * A node is valid if it is not deleted, is selectable, has valid coordinates,
    * and is not part of any parent way.
     *
     * @param node the node to validate
     * @return true if the node can be improved, false otherwise
     */
    public static boolean isNodeValidForImprovement(Node node) {
        if (node == null || node.isDeleted() || node.isIncomplete()
            || !node.isSelectable() || hasParentWay(node)) {
            return false;
        }

        EastNorth coords = node.getEastNorth();
        return coords != null;
    }

    private static boolean hasParentWay(Node node) {
        for (OsmPrimitive referrer : node.getReferrers()) {
            if (referrer instanceof Way && !referrer.isDeleted()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets all selected nodes from the dataset that are valid for improvement.
     *
     * @param dataSet the current dataset
     * @return collection of valid selected nodes
     */
    public static Collection<Node> getValidSelectedNodes(DataSet dataSet) {
        if (dataSet == null) {
            return Collections.emptyList();
        }

        return dataSet.getSelectedNodes().stream()
                .filter(ImproveNodeAccuracyHelper::isNodeValidForImprovement)
                .collect(Collectors.toList());
    }

    /**
     * Finds the closest valid standalone node near a screen point.
     *
     * @param mapView the current map view
     * @param dataSet the current edit data set
     * @param point the cursor position
     * @param maxDistancePixels maximum hit distance in screen pixels
     * @return the closest valid node, or null if none is close enough
     */
    public static Node findCandidateNode(MapView mapView, DataSet dataSet, Point point,
            int maxDistancePixels) {
        if (dataSet == null) {
            return null;
        }
        return findClosestNode(mapView, dataSet.getNodes(), point, maxDistancePixels);
    }

    /**
     * Finds the closest valid standalone node from a known candidate collection.
     * Non-positive distance values mean the collection is not distance-limited.
     *
     * @param mapView the current map view
     * @param nodes nodes to search
     * @param point the cursor position
     * @param maxDistancePixels maximum hit distance in screen pixels, or unlimited when non-positive
     * @return the closest valid node, or null if none is available
     */
    public static Node findClosestNode(MapView mapView, Collection<Node> nodes, Point point,
            int maxDistancePixels) {
        if (mapView == null || nodes == null || point == null) {
            return null;
        }

        double bestDistanceSq = maxDistancePixels > 0
                ? maxDistancePixels * (double) maxDistancePixels
                : Double.MAX_VALUE;
        Node bestNode = null;

        for (Node node : nodes) {
            if (!isNodeValidForImprovement(node)) {
                continue;
            }
            EastNorth nodeEastNorth = node.getEastNorth();
            Point nodePoint = mapView.getPoint(nodeEastNorth);
            double distanceSq = point.distanceSq(nodePoint);
            if (distanceSq <= bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestNode = node;
            }
        }

        return bestNode;
    }
}
