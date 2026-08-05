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
import uk.me.mantas.eternity.save.AbilityManager;
import uk.me.mantas.eternity.save.AbilityManager.Change;
import uk.me.mantas.eternity.save.AbilityManager.NewAbility;
import uk.me.mantas.eternity.save.SavedGameOpener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Adds and removes abilities, spells and talents. The request carries
// {oldSave, savedYet, changes: [{kind, character, ...}]} where kind is one of
// addAbility / removeAbility / addTalent / removeTalent. Removal addresses an
// ability by its own object GUID, since a wizard can carry two copies of the
// same spell; addition carries everything needed to mint the object, resolved
// from the catalog by the UI. On success the modified save is re-opened and
// returned like openSavedGame.
public class UpdateAbilities extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(UpdateAbilities.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		Environment.getInstance().mutationWorker().execute(() -> update(request, callback));
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
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
				final String character = change.getString("character");

				switch (kind) {
					case "addAbility":
						changes.add(Change.addAbility(character, newAbility(change)));
						break;

					case "removeAbility":
						changes.add(Change.removeAbility(
							character, change.getString("abilityGuid")));

						break;

					case "addTalent":
						changes.add(Change.addTalent(
							character
							, change.getString("talent")
							, grants(change.optJSONArray("grants"))
							, skills(change.optJSONObject("skills"))));

						break;

					case "removeTalent":
						changes.add(Change.removeTalent(
							character
							, change.getString("talent")
							, prefabs(change.optJSONArray("grants"))
							, skills(change.optJSONObject("skills"))));

						break;

					default:
						logger.error("Unknown ability change kind '%s'.%n", kind);
						callback.failure(-1, "Unknown ability change '" + kind + "'.");
						return;
				}
			}

			if (!new AbilityManager(saveFile).apply(changes)) {
				callback.failure(-1, "Ability update failed. See eternity.log for details.");
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

	private static NewAbility newAbility (final JSONObject json) {
		return new NewAbility(
			json.getString("prefab")
			, json.optString("path", "")
			, json.optString("component", "GenericAbility")
			, json.optInt("effect", 5)
			, json.optBoolean("spell", false)
			, json.optString("class", ""));
	}

	private static List<NewAbility> grants (final JSONArray json) {
		final List<NewAbility> abilities = new ArrayList<>();
		if (json == null) {
			return abilities;
		}

		for (int i = 0; i < json.length(); i++) {
			abilities.add(newAbility(json.getJSONObject(i)));
		}

		return abilities;
	}

	/** Removal only needs the names of the objects the talent put there. */
	private static List<String> prefabs (final JSONArray json) {
		final List<String> names = new ArrayList<>();
		if (json == null) {
			return names;
		}

		for (int i = 0; i < json.length(); i++) {
			final Object entry = json.get(i);
			names.add(entry instanceof JSONObject
				? ((JSONObject) entry).getString("prefab") : String.valueOf(entry));
		}

		return names;
	}

	private static Map<String, Integer> skills (final JSONObject json) {
		final Map<String, Integer> bonuses = new LinkedHashMap<>();
		if (json == null) {
			return bonuses;
		}

		for (final String skill : json.keySet()) {
			bonuses.put(skill, json.optInt(skill, 0));
		}

		return bonuses;
	}
}
