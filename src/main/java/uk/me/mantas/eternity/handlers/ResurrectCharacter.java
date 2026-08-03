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
import uk.me.mantas.eternity.save.CompanionRegistry;
import uk.me.mantas.eternity.save.Resurrector;
import uk.me.mantas.eternity.save.SavedGameOpener;

import java.io.File;
import java.io.IOException;

// Resurrects a companion who died in-game. The request carries
// {oldSave, savedYet, companion} where companion is a CompanionRegistry key
// (the UI takes it from the synthetic "dead:<key>" character GUID). The
// donor save is found automatically among same-playthrough saves. On success
// the modified save is re-opened and returned like openSavedGame.
public class ResurrectCharacter extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(ResurrectCharacter.class);

	@Override
	public boolean onQuery (
		CefBrowser browser
		, long id
		, String request
		, boolean persistent
		, CefQueryCallback callback) {

		Environment.getInstance().mutationWorker().execute(() -> resurrect(request, callback));
		return true;
	}

	@Override
	public void onQueryCanceled (CefBrowser browser, long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void resurrect (final String request, final CefQueryCallback callback) {
		try {
			final JSONObject json = new JSONObject(request);
			final String oldSavePath = json.getString("oldSave");
			final boolean savedYet = json.getBoolean("savedYet");
			final String companionKey = json.getString("companion");

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

			final String displayName = CompanionRegistry.byKey(companionKey)
				.map(c -> c.displayName)
				.orElse(companionKey);

			switch (new Resurrector(saveFile).resurrect(companionKey)) {
				case OK:
					new SavedGameOpener(saveFile.getAbsolutePath(), callback).run();
					return;

				case UNKNOWN_COMPANION:
					callback.failure(-1, "Unknown companion: " + companionKey);
					return;

				case NO_DONOR:
					callback.failure(-1, String.format(
						"No other save from this playthrough contains %s alive. "
						+ "Resurrection needs a save made while they still lived."
						, displayName));
					return;

				case FAILED:
				default:
					callback.failure(-1, "Resurrection failed. See eternity.log for details.");
			}
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
		} catch (final IOException e) {
			logger.error("%s%n", e.getMessage());
			callback.failure(-1, "Error modifying temporary MobileObjects.save");
		}
	}
}
