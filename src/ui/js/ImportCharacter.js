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

var ImportCharacter = function () {
	var self = this;

	var defaultState = {
		enabled: false
		, importing: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	// The path of the CHR file awaiting overwrite confirmation.
	var pendingChrPath = null;

	var setBusy = busy => {
		self.state.importing = busy;
		// Block the save button (and anything else keyed off `saving`) while
		// the import is writing the extracted save.
		Eternity.Modifications.transition({saving: busy});
	};

	var success = response => {
		setBusy(false);
		response = JSON.parse(response);

		if (response.error) {
			var msg;
			if (response.error === 'DESERIALIZATION_ERR') {
				msg = 'Error deserializing save file, please report your eternity.log file.';
			} else {
				msg = response.error;
			}

			Eternity.GenericError.render({msg: msg});
			return;
		}

		// The character already exists in this save; ask the user whether to
		// overwrite it before doing anything.
		if (response.confirmOverwrite) {
			pendingChrPath = response.confirmOverwrite.chrPath;

			var name = response.confirmOverwrite.characterName;
			var message = response.confirmOverwrite.isMainCharacter
				? 'Importing "' + name + '" will overwrite the existing main character in this'
					+ ' save file, including their stats and equipment.'
				: '"' + name + '" is already in this party. Importing them will overwrite the'
					+ ' existing character, including their stats and equipment.';

			self.html.importOverwriteDialog.find('.overwrite-message').text(message);
			self.html.importOverwriteDialog.modal('show');
			return;
		}

		// The importer modified the extracted save so we re-render the whole
		// save view with the fresh data and flag it as having modifications.
		Eternity.SavedGame.render({
			saveData: response
			, info: Eternity.SavedGame.state.info
		});

		Eternity.Modifications.transition({modifications: true});
	};

	var failure = (errno, response) => {
		setBusy(false);

		// NO_SAVE means the user cancelled the file dialog.
		if (response !== 'NO_SAVE') {
			Eternity.GenericError.render({msg: response});
		}
	};

	var baseRequest = () => {
		return {
			oldSave: Eternity.SavedGame.state.info.absolutePath
			, savedYet: Eternity.Modifications.state.savedYet
		};
	};

	self.init = () => {
		self.html.importOverwriteAgree.click(self.confirmOverwrite.bind(self));
		self.html.importOverwriteDialog.on('hidden.bs.modal', () => {
			pendingChrPath = null;
		});
	};

	self.importCharacter = () => {
		if (self.state.importing) {
			return;
		}

		setBusy(true);
		Eternity.GenericError.render({});

		window.importCharacter({
			request: JSON.stringify(baseRequest())
			, onSuccess: success
			, onFailure: failure
		});
	};

	self.confirmOverwrite = () => {
		var chrPath = pendingChrPath;
		self.html.importOverwriteDialog.modal('hide');

		if (chrPath === null || self.state.importing) {
			return;
		}

		var request = baseRequest();
		request.chrPath = chrPath;
		request.overwrite = true;

		setBusy(true);
		window.importCharacter({
			request: JSON.stringify(request)
			, onSuccess: success
			, onFailure: failure
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		var menuItem = self.html['menu-import-character'];
		menuItem.off();

		if (self.state.enabled) {
			menuItem.parent().removeClass('disabled');
			menuItem.click(self.importCharacter);
		} else {
			menuItem.parent().addClass('disabled');
		}
	};
};

$.extend(ImportCharacter.prototype, Renderer.prototype);
