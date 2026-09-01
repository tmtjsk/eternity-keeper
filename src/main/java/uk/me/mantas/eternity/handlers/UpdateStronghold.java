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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SavedGameOpener;
import uk.me.mantas.eternity.save.StrongholdManager;
import uk.me.mantas.eternity.save.StrongholdManager.Change;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Edits Caed Nua. The request carries {oldSave, savedYet, changes: [{kind,
// name, number, flag}]} where kind is one of activate / addUpgrade /
// removeUpgrade / setNumber / setFlag.
//
// Order within the list is preserved and meaningful: building an upgrade adds
// its own Prestige and Security on top of whatever the numbers are at that
// moment, exactly as Stronghold.CompleteBuildingUpgrade would have. On success
// the modified save is re-opened and returned like openSavedGame.
public class UpdateStronghold extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(UpdateStronghold.class);

	@Override
	public boolean onQuery (
		CefBrowser browser
		, long id
		, String request
		, boolean persistent
		, CefQueryCallback callback) {

		Environment.getInstance().mutationWorker().execute(() -> update(request, callback));
		return true;
	}

	@Override
	public void onQueryCanceled (CefBrowser browser, long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void update (final String request, final CefQueryCallback callback) {
		try {
			final JSONObject json = new JSONObject(request);
			final String oldSavePath = json.getString("oldSave");
			final boolean savedYet = json.getBoolean("savedYet");
			final JSONArray changesJson = json.getJSONArray("changes");

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

			final List<Change> changes = new ArrayList<>();
			for (int i = 0; i < changesJson.length(); i++) {
				final JSONObject change = changesJson.getJSONObject(i);
				final String kind = change.getString("kind");
				final String name = change.optString("name", "");

				switch (kind) {
					case "activate":
						changes.add(Change.activate(change.optBoolean("flag", true)));
						break;

					case "addUpgrade":
						changes.add(Change.addUpgrade(name));
						break;

					case "removeUpgrade":
						changes.add(Change.removeUpgrade(name));
						break;

					case "setNumber":
						changes.add(Change.setNumber(name, change.optInt("number", 0)));
						break;

					case "setFlag":
						changes.add(Change.setFlag(name, change.optBoolean("flag", false)));
						break;

					default:
						logger.error("Unknown stronghold change kind '%s'.%n", kind);
						break;
				}
			}

			if (!new StrongholdManager(saveFile).apply(changes)) {
				callback.failure(
					-1, "Stronghold update failed. See eternity.log for details.");

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
