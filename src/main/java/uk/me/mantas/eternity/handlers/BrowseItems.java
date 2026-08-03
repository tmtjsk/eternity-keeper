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
import uk.me.mantas.eternity.save.ItemCatalog;

import java.util.List;
import java.util.Map;

/**
 * Serves the item catalog to the "add any item" browser.
 *
 * <p>Sending all ~2200 items with their icons in one go would be several
 * megabytes of base64 through a JCEF query, so this pages: the UI asks for a
 * slice matching the current search and category and gets back only those,
 * icons included.
 */
public class BrowseItems extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(BrowseItems.class);
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
		final int filter;
		final int offset;
		final int limit;

		try {
			final JSONObject json = new JSONObject(request);
			search = json.optString("search", "").toLowerCase().trim();
			filter = json.optInt("filter", 0);
			offset = Math.max(0, json.optInt("offset", 0));
			limit = Math.min(MAX_LIMIT, Math.max(1, json.optInt("limit", 60)));
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		}

		final ItemCatalog catalog = ItemCatalog.getInstance();
		final List<Map.Entry<String, ItemCatalog.Entry>> matches =
			catalog.search(search, filter);

		final JSONArray items = new JSONArray();
		for (int i = offset; i < matches.size() && items.length() < limit; i++) {
			final Map.Entry<String, ItemCatalog.Entry> match = matches.get(i);
			final ItemCatalog.Entry entry = match.getValue();

			final JSONObject item = new JSONObject();
			item.put("key", match.getKey());
			item.put("displayName", entry.name);
			item.put("baseItem", entry.path);
			item.put("maxStack", entry.maxStack);
			item.put("quality", entry.quality);
			item.put("filter", entry.filter);
			item.put("quest", entry.quest);
			item.put("icon", catalog.iconData(entry.icon));

			final JSONArray slots = new JSONArray();
			entry.slots.forEach(slots::put);
			item.put("slots", slots);

			final JSONArray classes = new JSONArray();
			entry.classes.forEach(classes::put);
			item.put("classes", classes);

			items.put(item);
		}

		final JSONObject response = new JSONObject();
		response.put("total", matches.size());
		response.put("offset", offset);
		response.put("items", items);
		response.put("available", catalog.size() > 0);
		callback.success(response.toString());
	}
}
