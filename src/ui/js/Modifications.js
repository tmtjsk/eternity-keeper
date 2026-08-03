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

var Modifications = function () {
	var self = this;

	var defaultState = {
		modifications: false
		, saveName: null
		, savedYet: false
		, saving: false
		, switching: false
		, closing: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	self.init = () => {
		self.html.saveButton.click(self.save.bind(self));
		self.html.saveChanges.click(self.save.bind(self));
		self.html.dontSaveChanges.click(self.discardChanges.bind(self));
		self.html.saveNameBtn.click(self.saveName.bind(self));
		self.html.saveNameDialog.on('shown.bs.modal', () => {
			self.html.newSaveName[0].select();
			self.refreshSaveTarget();
		});
		self.html.saveTargetBrowse.click(self.chooseSaveFolder.bind(self));
		self.html.menuOpen.click(self.switchPrompt.bind(self));

		$(document).keyup(e => {
			if (e.ctrlKey === true && e.keyCode === 83) { // Ctrl+s
				self.save();
			}
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.html.saveButton.prop('disabled', !self.state.modifications || self.state.saving);
		self.html.saveNameBtn.prop('disabled', self.state.saving);

		// Writing a save takes a moment (clone, reserialize, rezip) — show
		// the user that work is happening.
		self.html.saveButton.html(self.state.saving
			? '<i class="fa fa-spinner fa-pulse"></i> Saving&hellip;'
			: '<i class="fa fa-floppy-o"></i> Save');
		self.html.saveNameBtn.html(self.state.saving
			? '<i class="fa fa-spinner fa-pulse"></i> Saving&hellip;'
			: 'Save changes');
	};
};

Modifications.prototype.suggestSaveName = function (info) {
	return (info.userSaveName || info.sceneTitle || info.systemName) + ' (edited)';
};

// The name typed above only becomes the save's display name; on disk the game
// insists on "<session id> <game id> <SceneTitle>.savegame", which is why the
// file is so hard to find afterwards. Show the real thing.
Modifications.prototype.refreshSaveTarget = function () {
	var self = this;
	var info = Eternity.SavedGame.state.info || {};

	self.html.saveTargetFile.text('working it out…');
	self.html.saveTargetFolder.text('');

	window.saveTarget({
		request: JSON.stringify({action: 'preview', oldSave: info.absolutePath || ''})
		, onSuccess: response => {
			var target = JSON.parse(response);
			self.html.saveTargetFile.text(target.fileName || '(unknown)');
			self.html.saveTargetFolder.text(target.directory || '(not set)')
				.attr('title', target.directory || '');
		}
		, onFailure: () => {
			self.html.saveTargetFile.text('(unknown)');
			self.html.saveTargetFolder.text('(unknown)');
		}
	});
};

Modifications.prototype.chooseSaveFolder = function () {
	var self = this;

	window.saveTarget({
		request: JSON.stringify({action: 'choose'})
		, onSuccess: response => {
			var target = JSON.parse(response);
			self.html.saveTargetFolder.text(target.directory).attr('title', target.directory);
		}
		, onFailure: () => {
			// Cancelling the picker just leaves the current folder alone.
		}
	});
};

// Fades the confirmation toast in and back out again a moment later.
Modifications.prototype.showSavedToast = function () {
	var self = this;
	self.html.saveToast.addClass('show');
	clearTimeout(self.toastTimer);
	self.toastTimer = setTimeout(() => self.html.saveToast.removeClass('show'), 2600);
};

Modifications.prototype.saveName = function () {
	var self = this;
	self.state.saveName = self.html.newSaveName.val();
	self.save();
};

Modifications.prototype.save = function () {
	var self = this;

	var success = () => {
		self.html.saveNameDialog.modal('hide');
		self.html.saveChangesDialog.modal('hide');

		if (self.state.switching) {
			Eternity.SaveSearch.transition({});
			self.render({});
		} else if (self.state.closing) {
			window.closeWindow({
				request: 'true'
				, onSuccess: () => {}
				, onFailure: () => {}
			});
		} else {
			self.transition({modifications: false, savedYet: true, saving: false});
			self.showSavedToast();
		}
	};

	var failure = (errno, response) => {
		self.html.saveNameDialog.modal('hide');
		self.html.saveChangesDialog.modal('hide');
		self.transition({saving: false});
		Eternity.GenericError.render({msg: JSON.parse(response).error});
	};

	var prepareData = (data) => {
		var newData = $.extend(true, {}, data);
		newData.characters = data.characters.map(character => {
			for (var stat in character.stats) {
				//noinspection JSUnfilteredForInLoop
				character.stats[stat].value = character.stats[stat].value.toString();
			}

			return character;
		});

		for (var p1 in data.globals) {
			if (!data.globals.hasOwnProperty(p1)) {
				continue;
			}

			for (var p2 in data.globals[p1]) {
				if (!data.globals[p1].hasOwnProperty(p2)) {
					continue;
				}

				for (var p3 in data.globals[p1][p2]) {
					if (!data.globals[p1][p2].hasOwnProperty(p3)) {
						continue;
					}

					newData.globals[p1][p2][p3].value = data.globals[p1][p2][p3].value.toString();
				}
			}
		}

		return newData;
	};

	self.html.saveChangesDialog.modal('hide');

	if (self.state.saving) {
		return;
	}

	if (self.state.saveName == null) {
		self.html.saveNameDialog.modal('show');
		return;
	}

	var request = {
		savedYet: self.state.savedYet
		, saveName: self.state.saveName
		, absolutePath: Eternity.SavedGame.state.info.absolutePath
		, saveData: prepareData(Eternity.SavedGame.state.saveData)
	};

	self.transition({saving: true});
	window.saveChanges({
		request: JSON.stringify(request)
		, onSuccess: success
		, onFailure: failure
	});
};

Modifications.prototype.switchPrompt = function () {
	var self = this;
	self.state.switching = true;

	if (self.state.modifications) {
		self.html.saveChangesDialog.modal('show');
	} else {
		self.discardChanges();
	}
};

Modifications.prototype.closePrompt = function () {
	var self = this;
	self.state.closing = true;

	if (self.state.modifications) {
		self.html.saveChangesDialog.modal('show');
	} else {
		self.discardChanges();
	}
};

Modifications.prototype.discardChanges = function () {
	var self = this;

	if (self.state.switching) {
		Eternity.SaveSearch.transition({});
		self.render({});
		self.html.saveChangesDialog.modal('hide');
	} else if (self.state.closing) {
		window.closeWindow({
			request: 'true'
			, onSuccess: () => {}
			, onFailure: () => {}
		});
	}
};

$.extend(Modifications.prototype, Renderer.prototype);

// Called directly from Java code.
var checkForModifications = function () {
	Eternity.Modifications.closePrompt();
};
