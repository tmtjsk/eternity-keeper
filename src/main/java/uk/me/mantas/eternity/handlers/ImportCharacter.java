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
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.handler.CefDialogHandler.FileDialogMode;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.CharacterImporter;
import uk.me.mantas.eternity.save.CharacterImporter.ImportConflict;
import uk.me.mantas.eternity.save.SavedGameOpener;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;
import java.util.Vector;

public class ImportCharacter extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(ImportCharacter.class);

	@Override
	public boolean onQuery (
		CefBrowser browser
		, long id
		, String request
		, boolean persistent
		, CefQueryCallback callback) {

		// Second phase of an import: the client already knows which CHR file
		// to use (typically after the user confirmed an overwrite), so no
		// file dialog is needed.
		String confirmedChrPath = null;
		boolean overwrite = false;
		try {
			final JSONObject json = new JSONObject(request);
			if (json.has("chrPath")) {
				confirmedChrPath = json.getString("chrPath");
				overwrite = json.optBoolean("overwrite", false);
			}
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return true;
		}

		if (confirmedChrPath != null) {
			final String chrPath = confirmedChrPath;
			final boolean overwriteExisting = overwrite;
			Environment.getInstance().mutationWorker().execute(
				() -> doImport(request, callback, chrPath, overwriteExisting));
		} else {
			// The file dialog itself must not block the mutation queue; only
			// the import work moves there once a file has been chosen.
			Environment.getInstance().workers().execute(
				new SelectChrFile(browser, request, callback));
		}

		return true;
	}

	@Override
	public void onQueryCanceled (CefBrowser browser, long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private class SelectChrFile implements Runnable {
		private final CefBrowser browser;
		private final String request;
		private final CefQueryCallback callback;

		public SelectChrFile (
			CefBrowser browser
			, String request
			, CefQueryCallback callback) {

			this.browser = browser;
			this.request = request;
			this.callback = callback;
		}

		@Override
		public void run () {
			browser.runFileDialog(
				FileDialogMode.FILE_DIALOG_OPEN
				, "Choose a character"
				, ""
				, new Vector<String>(){{add(".chr");}}
				, 0
				, new FileCallback(request, callback));
		}
	}

	private class FileCallback implements CefRunFileDialogCallback {
		private final String request;
		private final CefQueryCallback callback;

		public FileCallback (
			String request
			, CefQueryCallback callback) {

			this.request = request;
			this.callback = callback;
		}

		@Override
		public void onFileDialogDismissed (
			final int selectedAcceptFilter
			, final Vector<String> filenames) {

			if (filenames.size() < 1 || filenames.get(0).length() < 1) {
				callback.failure(-1, "NO_SAVE");
				return;
			}

			final String chrFile = filenames.get(0);
			Environment.getInstance().mutationWorker().execute(
				() -> analyseThenImport(request, callback, chrFile));
		}
	}

	// First phase: if the character already exists in the target save, ask
	// the client to confirm the overwrite instead of importing right away.
	private void analyseThenImport (
		final String request
		, final CefQueryCallback callback
		, final String chrFile) {

		try {
			final CharacterImporter importer = new CharacterImporter(request, chrFile);
			final Optional<ImportConflict> conflict = importer.detectConflict();

			if (conflict.isPresent()) {
				final JSONObject confirm = new JSONObject();
				confirm.put("characterName", conflict.get().characterName);
				confirm.put("isMainCharacter", conflict.get().mainCharacter);
				confirm.put("chrPath", chrFile);

				final JSONObject response = new JSONObject();
				response.put("confirmOverwrite", confirm);
				callback.success(response.toString());
				return;
			}
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		} catch (final FileNotFoundException e) {
			logger.error("File not found: %s%n", e.getMessage());
			callback.failure(-1, "Unable to find your save or CHR file.");
			return;
		} catch (final IOException e) {
			logger.error("%s%n", e.getMessage());
			callback.failure(-1, "Error reading save or CHR file.");
			return;
		}

		doImport(request, callback, chrFile, false);
	}

	private void doImport (
		final String request
		, final CefQueryCallback callback
		, final String chrFile
		, final boolean overwrite) {

		try {
			final CharacterImporter importer = new CharacterImporter(request, chrFile);
			final boolean success = overwrite
				? importer.overwriteCharacter()
				: importer.importCharacter();

			if (success) {
				final SavedGameOpener opener = new SavedGameOpener(
					importer.saveFile.getAbsolutePath()
					, callback);

				opener.run();
			} else {
				callback.failure(-1, "Character import failed.");
			}
		} catch (JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
		} catch (FileNotFoundException e) {
			logger.error("File not found: %s%n", e.getMessage());
			callback.failure(-1, "Unable to find your save or CHR file.");
		} catch (IOException e) {
			logger.error("%s%n", e.getMessage());
			callback.failure(-1, "Error modifying temporary MobileObjects.save");
		}
	}
}
