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
import org.json.JSONStringer;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SavedGameOpener;

import java.io.File;
import java.io.IOException;

public class OpenSavedGame extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(OpenSavedGame.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		if (!(new File(request).exists())) {
			notExists(callback);
			return true;
		}

		// Whatever the last save left unsaved goes. Queued behind any edit
		// still writing, so the copy is never deleted out from under one.
		final Environment environment = Environment.getInstance();
		environment.mutationWorker().execute(Answered.to(callback
			, () -> environment.state().workingSave().opening()));

		// The opener reads its way through whatever the save holds, and a save
		// can be damaged into a shape it never met: one byte changed in a real
		// one, and this used to end the thread with the page still waiting.
		environment.workers().execute(Answered.to(callback, () -> {
			// The list unpacked only what it draws; the world state and the
			// areas follow now that the save is wanted.
			try {
				environment.state().unpacked().complete(new File(request));
			} catch (final IOException e) {
				unpackError(callback, e.getMessage());
				return;
			}

			new SavedGameOpener(request, callback).run();
		}));

		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d was cancelled.%n", id);
	}

	public static void notExists (final CefQueryCallback callback) {
		final String json = new JSONStringer()
			.object()
				.key("error").value("NOT_EXISTS")
			.endObject()
			.toString();

		callback.success(json);
	}

	private static void unpackError (final CefQueryCallback callback, final String reason) {
		final String json = new JSONStringer()
			.object()
				.key("error").value("UNPACK_ERR")
				.key("msg").value(reason)
			.endObject()
			.toString();

		callback.success(json);
	}

	public static void deserializationError (final CefQueryCallback callback) {
		final String json = new JSONStringer()
			.object()
				.key("error").value("DESERIALIZATION_ERR")
			.endObject()
			.toString();

		callback.success(json);
	}
}
