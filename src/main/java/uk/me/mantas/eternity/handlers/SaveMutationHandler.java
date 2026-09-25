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
import uk.me.mantas.eternity.save.SavedGameOpener;
import uk.me.mantas.eternity.serializer.ShortReadException;

import java.io.File;
import java.io.IOException;

/**
 * A handler that edits the working save and hands the reopened save back.
 *
 * <p>Every one of them takes {@code {oldSave, savedYet, ...}} and does the same
 * thing around its edit: runs on the mutation worker (invariant 9 — two edits
 * can never write the same file at once), asks {@code WorkingSave} which copy
 * of the save to edit, refuses one that is not there, and reopens it through
 * {@link SavedGameOpener} so the UI gets exactly what opening it would give.
 * Subclasses supply only {@link #mutate}.
 *
 * <p>These were seven copies of the same fifty lines, kept consistent only by
 * every copy remembering to do it.
 */
public abstract class SaveMutationHandler extends CefMessageRouterHandlerAdapter {
	private final Logger logger = Logger.getLogger(getClass());

	/**
	 * Makes the edit.
	 *
	 * @param save    the live working copy of the save
	 * @param request the whole request, for whatever this handler reads from it
	 * @return {@code null} on success, or what to tell the user when the edit
	 *         was refused
	 * @throws IOException when the save could not be read or written; the
	 *         message reaches the user. A {@link ShortReadException} -- a save
	 *         that could be read only in part -- reaches them as it stands.
	 */
	protected abstract String mutate (File save, JSONObject request) throws IOException;

	@Override
	public final boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		Environment.getInstance().mutationWorker().execute(() -> handle(request, callback));
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void handle (final String request, final CefQueryCallback callback) {
		final JSONObject json;
		final File opened;
		final boolean savedYet;

		try {
			json = new JSONObject(request);
			opened = new File(json.getString("oldSave"));
			savedYet = json.getBoolean("savedYet");
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		}

		File save = opened;
		try {
			// Never the directory the list unpacked: an edit the user goes on
			// to discard must not be there when they open the save again.
			save = Environment.getInstance().state().workingSave().forEditing(opened, savedYet);
			if (!save.exists()) {
				callback.failure(-1, "Unable to find your save file.");
				return;
			}

			final String problem = mutate(save, json);
			if (problem != null) {
				callback.failure(-1, problem);
				return;
			}
		} catch (final JSONException e) {
			logger.error("Error reading request %s: %s%n", request, e.getMessage());
			callback.failure(-1, "Error parsing JSON request.");
			return;
		} catch (final ShortReadException e) {
			// Nothing was written, so "could not write" would blame the wrong
			// step. The exception already says what happened, in the user's terms.
			logger.error("Editing %s refused: %s%n", save.getAbsolutePath(), e.getMessage());
			callback.failure(-1, e.getMessage());
			return;
		} catch (final IOException e) {
			logger.error("Editing %s failed: %s%n", save.getAbsolutePath(), e.getMessage());
			callback.failure(-1, "Could not write the save: " + e.getMessage());
			return;
		}

		new SavedGameOpener(save.getAbsolutePath(), callback).run();
	}
}
