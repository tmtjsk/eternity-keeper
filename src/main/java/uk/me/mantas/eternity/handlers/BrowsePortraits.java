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
import uk.me.mantas.eternity.save.PortraitCatalog;
import uk.me.mantas.eternity.save.PortraitCatalog.Portrait;

import java.util.ArrayList;
import java.util.List;

/**
 * Serves the portraits on the player's own install to the picker.
 *
 * <p>Paged, and for the same reason {@link BrowseItems} is: the shipped game
 * has 118 pairs and the small images alone are about 1.9 MB, which is well
 * past what one JCEF reply carries comfortably.
 *
 * <p>Only the small image travels — 76×96, the one the party bar uses. The
 * large is 210×330 and seven times the bytes, and the picker is a grid.
 * The chosen portrait's large image is already in the character payload.
 */
public class BrowsePortraits extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(BrowsePortraits.class);
	private static final int MAX_LIMIT = 60;

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
		final String category;
		final int offset;
		final int limit;
		final boolean wantLarge;
		final String largePath;

		try {
			final JSONObject json = new JSONObject(request);
			search = json.optString("search", "").toLowerCase().trim();
			category = json.optString("category", "");
			offset = Math.max(0, json.optInt("offset", 0));
			limit = Math.min(MAX_LIMIT, Math.max(1, json.optInt("limit", 40)));

			// The character view needs one full-size image after a pick, since
			// it draws the large portrait rather than the party-bar thumbnail.
			largePath = json.optString("largePath", "");
			wantLarge = !largePath.isEmpty();
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		}

		final PortraitCatalog catalog = PortraitCatalog.getInstance();

		if (wantLarge) {
			final JSONObject response = new JSONObject();
			response.put("largePath", largePath);
			response.put("image", catalog.imageData(largePath));
			response.put("available", catalog.size() > 0);
			callback.success(response.toString());
			return;
		}

		final List<Portrait> matches = new ArrayList<>();
		for (final Portrait portrait : catalog.all()) {
			if (!category.isEmpty() && !category.equals(portrait.category)) {
				continue;
			}

			if (!search.isEmpty()
				&& !portrait.name.toLowerCase().contains(search)) {

				continue;
			}

			matches.add(portrait);
		}

		final JSONArray portraits = new JSONArray();
		for (int i = offset; i < matches.size() && portraits.length() < limit; i++) {
			final Portrait portrait = matches.get(i);
			final JSONObject entry = new JSONObject();

			entry.put("key", portrait.key);
			entry.put("name", portrait.name);
			entry.put("category", portrait.category);
			entry.put("large", portrait.large);
			entry.put("small", portrait.small);
			entry.put("image", catalog.imageData(portrait.small));
			portraits.put(entry);
		}

		final JSONObject response = new JSONObject();
		response.put("portraits", portraits);
		response.put("total", matches.size());
		final JSONArray categories = new JSONArray();
		catalog.categories().forEach(categories::put);
		response.put("categories", categories);
		response.put("available", catalog.size() > 0);
		callback.success(response.toString());
	}
}
