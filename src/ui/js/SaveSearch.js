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

var SaveSearch = function () {
	var self = this;

	var defaultState = {
		searchPath: ''
		, saves: []
		, searching: false
		, opening: false
		, selected: -1
		, busy: false
	};

	var saveName = info => {
		var userSaveName = info.userSaveName ? ' (' + info.userSaveName + ')' : '';
		return info.playerName + ' - ' + info.systemName + userSaveName;
	};

	var populateSaveBlocks = (container, template, data, opening, selected) => {
		data.forEach((info, i) => {
			var tile = CloneFactory.clone(template);
			var portraits = info.portraits.map(
					portrait => '<img src="data:image/png;base64,' + portrait + '">');

			if (opening === i) {
				tile.find('i').show();
			}

			tile.toggleClass('selected', selected === i);
			tile.find('.screenshot img').attr('src', 'data:image/png;base64,' + info.screenshot);
			tile.find('.name').text(saveName(info));
			tile.find('.date').text(info.date);
			tile.find('.portraits').html(portraits.join(' '));
			tile.click(self.select.bind(self, i));
			tile.dblclick(self.open.bind(self, info, i));
			container.append(tile);
		});
	};

	// The original .savegame archive corresponding to an extracted save.
	self.saveFilePath = info => {
		var base = info.absolutePath.split(/[\\\/]/).pop();
		var root = self.state.searchPath.replace(/[\\\/]+$/, '');
		return root + '\\' + base;
	};

	self.selectedInfo = () =>
		self.state.selected >= 0 ? self.state.saves[self.state.selected] : null;

	self.state = $.extend({}, defaultState);
	self.html = {};

	self.init = () => {
		self.html.searchForSavedGames.click(self.search.bind(self));
		self.html.saveActionLoad.off('click').click(() => {
			var info = self.selectedInfo();
			if (info) {
				self.open(info, self.state.selected);
			}
		});
		self.html.saveActionRename.off('click').click(self.renamePrompt.bind(self));
		self.html.saveActionDelete.off('click').click(self.deletePrompt.bind(self));
		self.html.renameSaveConfirm.off('click').click(self.renameConfirmed.bind(self));
		self.html.deleteSaveConfirm.off('click').click(self.deleteConfirmed.bind(self));
		self.html.renameSaveDialog.on(
			'shown.bs.modal', () => self.html.renameSaveInput[0].select());
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.html.savedGameLocation.val(self.state.searchPath);
		self.html.saveBlocks.empty();
		self.html.saveBlocks.show();
		Eternity.render({listView: true});
		populateSaveBlocks(
			self.html.saveBlocks
			, self.html.saveBlockClone
			, self.state.saves
			, self.state.opening
			, self.state.selected);

		var actionable = self.state.selected >= 0;
		self.html.saveActions.toggle(actionable);
		self.html.saveActions.find('button').prop(
			'disabled', self.state.busy || self.state.opening !== false);

		if (self.state.searching) {
			self.html.searchForSavedGames.prop('disabled', true);
			self.html.searchForSavedGames.find('i').css('display', 'inline-block');
			self.html.searchForSavedGames.find('span').text('Searching...');
		} else {
			self.html.searchForSavedGames.prop('disabled', false);
			self.html.searchForSavedGames.find('i').hide();
			self.html.searchForSavedGames.find('span').text('Search');
		}
	};
};

SaveSearch.prototype.select = function (i) {
	var self = this;

	if (self.state.busy) {
		return;
	}

	self.transition({selected: self.state.selected === i ? -1 : i});
};

SaveSearch.prototype.search = function () {
	var self = this;
	var interval = null;
	var searchPath = this.html.savedGameLocation.val();

	var success = response => {
		clearInterval(interval);
		Eternity.Progress.render({});
		self.transition({searching: false});

		var saves = JSON.parse(response);
		if (saves.error) {
			Eternity.GenericError.render({msg: 'No saves found.'});
			return;
		}

		self.transition({saves: saves, selected: -1});
	};

	var failure = () => {
		clearInterval(interval);
		Eternity.Progress.render({});
		self.transition({searching: false});
		console.error('Listing saved games failed.');
	};

	var pollForUpdate = () => {
		window.checkExtractionProgress({
			request: "true"
			, onSuccess: response =>
				Eternity.Progress.render({percentage: JSON.parse(response).update})
			, onFailure: console.error.bind(console, 'Error checking for extraction progress.')
		});
	};

	if (searchPath.length < 1 || self.state.searching) {
		return;
	}

	Eternity.GenericError.render({});
	self.transition({searchPath: searchPath, searching: true, selected: -1});
	interval = setInterval(pollForUpdate, 1000);
	window.listSavedGames({
		request: searchPath
		, onSuccess: success
		, onFailure: failure
	});
};

SaveSearch.prototype.open = function (info, i) {
	var self = this;

	var success = response => {
		self.transition({opening: false});
		response = JSON.parse(response);

		if (response.error) {
			var msg;
			if (response.error === 'DESERIALIZATION_ERR') {
				msg = 'Error deserializing save file, please report your eternity.log file.';
			} else if (response.error === 'NOT_EXISTS') {
				msg = 'Save file "' + info.absolutePath + '" does not exist.';
			} else if (response.msg) {
				msg = response.msg;
			} else {
				msg = 'Unknown error when opening save file, please report your eternity.log file.';
			}

			Eternity.GenericError.render({msg: msg});

			// TODO: move this when flow changes to allow user confirm whether to convert or not
			if (response.error === 'WINDOWS_STORE_SAVE') {
				self.search();
			}
		} else if (response.characters.length < 1) {
			Eternity.GenericError.render({msg: 'No characters found in save game.'});
		} else if (response.isWindowStoreSave) {
			// TODO: redirect to new modal dialog which optionally kicks off the conversion and blocks until conversion is complete
			Eternity.GenericError.render({msg: 'Windows Store save selected'});
		} else {
			Eternity.SavedGame.render({saveData: response, info: info});
		}
	};

	var failure = () => {
		self.transition({opening: false});
		Eternity.GenericError.render({msg: 'Error opening saved game file: ' + info.absolutePath});
	};

	if (self.state.opening !== false || self.state.busy) {
		return;
	}

	Eternity.GenericError.render({});
	self.transition({opening: i, selected: i});
	window.openSavedGame({
		request: info.absolutePath
		, onSuccess: success
		, onFailure: failure
	});
};

SaveSearch.prototype.renamePrompt = function () {
	var self = this;
	var info = self.selectedInfo();

	if (!info || self.state.busy) {
		return;
	}

	self.html.renameSaveInput.val(info.userSaveName || info.systemName);
	self.html.renameSaveDialog.find('.pm-dialog-subject').text(saveNameOf(info));
	self.html.renameSaveDialog.modal({backdrop: 'static', keyboard: false});
};

SaveSearch.prototype.renameConfirmed = function () {
	var self = this;
	var info = self.selectedInfo();
	var newName = self.html.renameSaveInput.val().trim();

	if (!info || newName.length < 1 || self.state.busy) {
		return;
	}

	var setBusy = busy => {
		self.state.busy = busy;
		self.html.renameSaveConfirm
			.prop('disabled', busy)
			.html(busy
				? '<i class="fa fa-spinner fa-pulse"></i> Working&hellip;'
				: '<i>&#10094;</i> Rename <i>&#10095;</i>');
		self.html.renameSaveDialog.find('[data-dismiss]').prop('disabled', busy);
	};

	var success = response => {
		setBusy(false);
		self.html.renameSaveDialog.modal('hide');
		info.userSaveName = JSON.parse(response).userSaveName;
		self.transition({});
	};

	var failure = (errno, response) => {
		setBusy(false);
		self.html.renameSaveDialog.modal('hide');
		Eternity.GenericError.render({msg: response});
	};

	setBusy(true);
	window.renameSavedGame({
		request: JSON.stringify({
			savePath: self.saveFilePath(info)
			, extractedPath: info.absolutePath
			, newName: newName
		})
		, onSuccess: success
		, onFailure: failure
	});
};

SaveSearch.prototype.deletePrompt = function () {
	var self = this;
	var info = self.selectedInfo();

	if (!info || self.state.busy) {
		return;
	}

	self.html.deleteSaveDialog.find('.pm-dialog-subject').text(saveNameOf(info));
	self.html.deleteSaveDialog.modal({backdrop: 'static', keyboard: false});
};

SaveSearch.prototype.deleteConfirmed = function () {
	var self = this;
	var info = self.selectedInfo();

	if (!info || self.state.busy) {
		return;
	}

	var setBusy = busy => {
		self.state.busy = busy;
		self.html.deleteSaveConfirm
			.prop('disabled', busy)
			.html(busy
				? '<i class="fa fa-spinner fa-pulse"></i> Working&hellip;'
				: '<i>&#10094;</i> Delete <i>&#10095;</i>');
		self.html.deleteSaveDialog.find('[data-dismiss]').prop('disabled', busy);
	};

	var success = () => {
		setBusy(false);
		self.html.deleteSaveDialog.modal('hide');

		var saves = self.state.saves.slice();
		saves.splice(self.state.selected, 1);
		self.transition({saves: saves, selected: -1});
	};

	var failure = (errno, response) => {
		setBusy(false);
		self.html.deleteSaveDialog.modal('hide');
		var msg = response;
		try {
			msg = JSON.parse(response).error || response;
		} catch (e) {
			// Not JSON; show as-is.
		}
		Eternity.GenericError.render({msg: msg});
	};

	setBusy(true);
	window.deleteSavedGame({
		request: self.saveFilePath(info)
		, onSuccess: success
		, onFailure: failure
	});
};

// Shared helper for dialog subjects.
function saveNameOf (info) {
	var userSaveName = info.userSaveName ? ' (' + info.userSaveName + ')' : '';
	return info.playerName + ' - ' + info.systemName + userSaveName;
}

$.extend(SaveSearch.prototype, Renderer.prototype);
