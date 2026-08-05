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
import uk.me.mantas.eternity.game.GenericAbility.AbilityType;
import uk.me.mantas.eternity.save.AbilityCatalog;

import java.util.List;
import java.util.Map;

/**
 * Serves the ability catalog to the "add an ability or talent" browser.
 *
 * <p>Paged for the same reason {@link BrowseItems} is — 1,400 entries with
 * their icons would be megabytes of base64 through one JCEF query.
 *
 * <p>The interesting parameter is {@code progressionTable}: pass a class (and
 * optionally a companion's own table) and the results are restricted to what
 * that character could actually take, straight from the game's own
 * AbilityProgressionTable rather than a guess based on prefab paths.
 */
public class BrowseAbilities extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(BrowseAbilities.class);
	private static final int MAX_LIMIT = 200;

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		Environment.getInstance().workers().execute(() -> browse(request, callback));
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void browse (final String request, final CefQueryCallback callback) {
		final String search;
		final String kind;
		final String characterClass;
		final String companion;
		final String subrace;
		final boolean isPlayer;
		final boolean anyClass;
		final int offset;
		final int limit;
		final JSONArray iconKeys;

		try {
			final JSONObject json = new JSONObject(request);
			search = json.optString("search", "").toLowerCase().trim();
			kind = json.optString("kind", "");
			characterClass = json.optString("characterClass", "");
			companion = json.optString("progressionTable", "");
			subrace = json.optString("subrace", "");
			isPlayer = json.optBoolean("isPlayer", false);
			anyClass = json.optBoolean("anyClass", false);
			offset = Math.max(0, json.optInt("offset", 0));
			limit = Math.min(MAX_LIMIT, Math.max(1, json.optInt("limit", 60)));
			iconKeys = json.optJSONArray("iconKeys");
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		}

		final AbilityCatalog catalog = AbilityCatalog.getInstance();

		// Icon-only mode. The opener deliberately ships ability icons by name
		// rather than by value — a full party's worth of art pushed its single
		// reply past what the JCEF bridge carries — so the UI asks for the
		// handful it is about to draw.
		if (iconKeys != null) {
			final JSONObject icons = new JSONObject();
			for (int i = 0; i < iconKeys.length(); i++) {
				final String key = iconKeys.optString(i, "");
				catalog.lookup(key)
					.filter(entry -> !entry.icon.isEmpty())
					.ifPresent(entry -> {
						final String data = catalog.iconData(entry.icon);
						if (!data.isEmpty()) {
							icons.put(key.toLowerCase(), data);
						}
					});
			}

			final JSONObject response = new JSONObject();
			response.put("icons", icons);
			response.put("available", catalog.size() > 0);
			callback.success(response.toString());
			return;
		}

		// "Any class" drops the progression filter entirely: the save format
		// happily holds an ability its owner could never have learned, and
		// refusing to show them would be the editor deciding for the user.
		final Map<String, AbilityCatalog.Unlock> allowed =
			anyClass ? null
				: catalog.unlocksFor(characterClass, companion, subrace, isPlayer);

		final List<Map.Entry<String, AbilityCatalog.Entry>> matches =
			catalog.search(search, kind, allowed);

		final JSONArray abilities = new JSONArray();
		for (int i = offset; i < matches.size() && abilities.length() < limit; i++) {
			final Map.Entry<String, AbilityCatalog.Entry> match = matches.get(i);
			final AbilityCatalog.Entry entry = match.getValue();

			final JSONObject ability = new JSONObject();
			ability.put("key", match.getKey());
			ability.put("displayName", entry.name);
			ability.put("description", entry.description);
			ability.put("kind", entry.kind);
			ability.put("component", entry.component);
			ability.put("effect", entry.effect);
			ability.put("path", entry.path);
			ability.put("prefab", prefabNameOf(entry.path, match.getKey()));
			ability.put("class", entry.characterClass);
			ability.put("spell", "spell".equals(entry.kind));
			ability.put("spellLevel", entry.spellLevel);
			ability.put("level", entry.level);
			ability.put("passive", entry.passive);
			ability.put("category", entry.category);
			ability.put("talentType", entry.talentType);
			ability.put("icon", catalog.iconData(entry.icon));

			// What adding this talent has to mint alongside it, resolved here so
			// the UI never has to know how a talent becomes an ability.
			ability.put("grants", grantsToJSON(catalog, entry));

			final JSONArray modifies = new JSONArray();
			entry.modifies.forEach(modifies::put);
			ability.put("modifies", modifies);

			final JSONObject skills = new JSONObject();
			entry.skills.forEach(skills::put);
			ability.put("skills", skills);

			final AbilityCatalog.Unlock unlock =
				allowed == null ? null : allowed.get(match.getKey());

			if (unlock != null) {
				ability.put("unlockLevel", unlock.level);
				ability.put("unlockCategory", unlock.category);
				ability.put("automatic", unlock.automatic);
			}

			abilities.put(ability);
		}

		final JSONObject response = new JSONObject();
		response.put("total", matches.size());
		response.put("offset", offset);
		response.put("abilities", abilities);
		response.put("available", catalog.size() > 0);
		callback.success(response.toString());
	}

	private static JSONArray grantsToJSON (
		final AbilityCatalog catalog, final AbilityCatalog.Entry talent) {

		final JSONArray grants = new JSONArray();
		for (final String key : talent.grants) {
			final JSONObject granted = new JSONObject();
			granted.put("key", key);

			// InstantiateAbility stamps the source onto the object, so an
			// ability that arrived with a talent has to say so — the prefab's
			// own EffectType says Ability, which is what it is when a class
			// grants it directly.
			granted.put("effect", AbilityType.Talent.ordinal());

			final java.util.Optional<AbilityCatalog.Entry> entry = catalog.lookup(key);
			if (entry.isPresent()) {
				granted.put("prefab", prefabNameOf(entry.get().path, key));
				granted.put("path", entry.get().path);
				granted.put("component", entry.get().component);
				granted.put("displayName", entry.get().name);
				granted.put("spell", "spell".equals(entry.get().kind));
				granted.put("class", entry.get().characterClass);
			} else {
				// No catalog entry: the game only cares about the file name, so
				// the key alone still resolves at load time.
				granted.put("prefab", key);
				granted.put("path", "");
				granted.put("component", "GenericAbility");
			}

			grants.put(granted);
		}

		return grants;
	}

	/**
	 * The prefab file name with its real casing, which is what an object's
	 * ObjectName has to read. Bundle keys are lowercased, so the path is the
	 * only place the original casing survives.
	 */
	private static String prefabNameOf (final String path, final String fallback) {
		if (path == null || path.isEmpty()) {
			return fallback;
		}

		final String file = path.substring(path.lastIndexOf('/') + 1);
		return file.endsWith(".prefab")
			? file.substring(0, file.length() - ".prefab".length()) : file;
	}
}
