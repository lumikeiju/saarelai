// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.improvenode;

import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

public class ImproveNodePlugin extends Plugin {

    public ImproveNodePlugin(final PluginInformation info) {
        super(info);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (oldFrame == null && newFrame != null) {
            MainApplication.getMap().addMapMode(new IconToggleButton(new ImproveNodeAccuracyAction(), false));
        }
    }
}
