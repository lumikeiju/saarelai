// SPDX-License-Identifier: GPL-2.0-or-later

package org.openstreetmap.josm.plugins.easypan;

import static org.openstreetmap.josm.tools.I18n.tr;

import org.openstreetmap.josm.actions.PreferencesAction;
import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

/** Entry point for the Easy Pan JOSM plugin. */
public final class EasyPanPlugin extends Plugin {

	private EasyPanMapMode mapMode;

	public EasyPanPlugin(PluginInformation info) {
		super(info);
		MainMenu.add(MainApplication.getMenu().moreToolsMenu,
				PreferencesAction.forPreferenceTab(
						tr("Easy Pan settings\u2026"),
						tr("Open the Easy Pan plugin preferences"),
						EasyPanPreferencesDialog.class));
	}

	@Override
	public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
		if (oldFrame == null && newFrame != null) {
			mapMode = new EasyPanMapMode();
			MainApplication.getMap().addMapMode(new IconToggleButton(mapMode, false));
		}
	}

	@Override
	public PreferenceSetting getPreferenceSetting() {
		return new EasyPanPreferencesDialog();
	}
}
