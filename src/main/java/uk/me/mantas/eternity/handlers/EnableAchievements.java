/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2015 the authors.
 *
 *  Eternity Keeper is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  Eternity Keeper is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package uk.me.mantas.eternity.handlers;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.AchievementsEnabler;
import uk.me.mantas.eternity.save.SavedGameOpener;

import java.io.File;
import java.io.IOException;

// Clears the cheat markers (GameState.CheatsEnabled and
// AchievementTracker.m_disableAchievements) so Steam achievements work again
// in a save touched by the in-game console. The request carries
// {oldSave, savedYet}; on success the modified save is re-opened and
// returned like openSavedGame.
public class EnableAchievements extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(EnableAchievements.class);

	@Override
	public boolean onQuery (
		CefBrowser browser
		, long id
		, String request
		, boolean persistent
		, CefQueryCallback callback) {

		Environment.getInstance().mutationWorker().execute(() -> enable(request, callback));
		return true;
	}

	@Override
	public void onQueryCanceled (CefBrowser browser, long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void enable (final String request, final CefQueryCallback callback) {
		try {
			final JSONObject json = new JSONObject(request);
			final String oldSavePath = json.getString("oldSave");
			final boolean savedYet = json.getBoolean("savedYet");
			// Toggle: true re-enables achievements, false marks the save as
			// cheated. Missing means enable, for backwards compatibility.
			final boolean enable = json.optBoolean("enable", true);

			File saveFile = new File(oldSavePath);
			if (savedYet) {
				final File previouslySaved =
					Environment.getInstance().state().previousSaveDirectory();

				if (previouslySaved == null) {
					logger.error(
						"Client reported we had already saved "
						+ "but directory didn't exist!%n");
				} else {
					saveFile = previouslySaved;
				}
			}

			if (!saveFile.exists()) {
				callback.failure(-1, "Unable to find your save file.");
				return;
			}

			if (!new AchievementsEnabler(saveFile).set(enable)) {
				callback.failure(-1,
					"Could not update the cheat flags. See eternity.log for details.");
				return;
			}

			new SavedGameOpener(saveFile.getAbsolutePath(), callback).run();
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
		} catch (final IOException e) {
			logger.error("%s%n", e.getMessage());
			callback.failure(-1, "Error modifying temporary MobileObjects.save");
		}
	}
}
