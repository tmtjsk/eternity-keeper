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
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.ChangesSaver;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;
import java.util.Locale;

/**
 * Tells the UI exactly where a save is about to land, and lets the user pick
 * somewhere else.
 *
 * <p>The file name the game uses has nothing to do with the name typed into the
 * save dialog — that one only goes into {@code saveinfo.xml}. On disk a save is
 * {@code <sessionID> <gameID> <SceneTitle>.savegame}, which is exactly why
 * people struggle to find the file they just wrote. Two actions:
 * {@code {action:"preview", oldSave}} answers with the real file name and
 * folder, and {@code {action:"choose"}} opens a folder picker and stores the
 * result as the new saves location.
 */
public class SaveTarget extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(SaveTarget.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		final String action;
		final String oldSave;

		try {
			final JSONObject json = new JSONObject(request);
			action = json.optString("action", "preview");
			oldSave = json.optString("oldSave", "");
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return true;
		}

		if ("choose".equals(action)) {
			// The bundled JCEF predates CEF's folder-picker mode, so this uses
			// Swing's directory chooser instead. It has to run on the event
			// dispatch thread like any other Swing dialog.
			SwingUtilities.invokeLater(() -> chooseFolder(callback));
			return true;
		}

		Environment.getInstance().workers().execute(() -> preview(oldSave, callback));
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private static String currentLocation () {
		return Settings.getInstance().json.optString("savesLocation", "");
	}

	private void preview (final String oldSave, final CefQueryCallback callback) {
		final JSONObject response = new JSONObject();
		response.put("directory", currentLocation());
		response.put("fileName", ChangesSaver.previewSaveFileName(oldSave));
		callback.success(response.toString());
	}

	// Swing takes its file-chooser wording from the system locale, which would
	// otherwise put a Polish dialog in the middle of an English editor.
	private static void useEnglishChooserLabels () {
		JComponent.setDefaultLocale(Locale.ENGLISH);
		UIManager.put("FileChooser.cancelButtonText", "Cancel");
		UIManager.put("FileChooser.cancelButtonToolTipText", "Cancel");
		UIManager.put("FileChooser.lookInLabelText", "Look in:");
		UIManager.put("FileChooser.folderNameLabelText", "Folder:");
		UIManager.put("FileChooser.fileNameLabelText", "Folder name:");
		UIManager.put("FileChooser.filesOfTypeLabelText", "Files of type:");
		UIManager.put("FileChooser.upFolderToolTipText", "Up one level");
		UIManager.put("FileChooser.homeFolderToolTipText", "Home");
		UIManager.put("FileChooser.newFolderToolTipText", "Create new folder");
		UIManager.put("FileChooser.listViewButtonToolTipText", "List");
		UIManager.put("FileChooser.detailsViewButtonToolTipText", "Details");
		UIManager.put("FileChooser.fileNameHeaderText", "Name");
		UIManager.put("FileChooser.fileSizeHeaderText", "Size");
		UIManager.put("FileChooser.fileTypeHeaderText", "Type");
		UIManager.put("FileChooser.fileDateHeaderText", "Modified");
	}

	private void chooseFolder (final CefQueryCallback callback) {
		useEnglishChooserLabels();

		final JFileChooser chooser = new JFileChooser();
		chooser.setLocale(Locale.ENGLISH);
		chooser.setDialogTitle("Choose where to save");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setAcceptAllFileFilterUsed(false);

		final String current = currentLocation();
		if (!current.isEmpty()) {
			final File directory = new File(current);
			if (directory.isDirectory()) {
				chooser.setCurrentDirectory(directory);
			}
		}

		if (chooser.showDialog(null, "Save here") != JFileChooser.APPROVE_OPTION) {
			callback.failure(-1, "NO_FOLDER");
			return;
		}

		final File chosen = chooser.getSelectedFile();
		if (chosen == null || !chosen.isDirectory()) {
			callback.failure(-1, "NOT_A_DIRECTORY");
			return;
		}

		Settings.getInstance().json.put("savesLocation", chosen.getAbsolutePath());
		Settings.getInstance().save();

		final JSONObject response = new JSONObject();
		response.put("directory", chosen.getAbsolutePath());
		callback.success(response.toString());
	}
}
