/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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

// Undo and Redo, beside the Save button and on Ctrl+Z and Ctrl+Y.
//
// The history itself is SaveHistory's. This puts it on screen and keeps it
// fed: every change that arms the Save button is noticed (Modifications.render
// calls notice), every Apply's reply is a step (SavedGame.adopt calls applied),
// and opening or writing a save starts it again.
//
// A tab's staged changes are not in it: they are a draft until Apply, with the
// tab's own Revert. While one has any, or a request is on its way, nothing is
// undone -- an Apply's undo reopens the save, and the draft would be built on a
// save that is no longer there -- and the reason is said instead.
var EditHistory = function () {
	var self = this;

	var defaultState = {
		enabled: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	var history = new SaveHistory.History();
	var busy = false;
	var pendingLabel = null;
	var made = null;
	var toastTimer = null;

	// The save a reply was merged into, until it is the one on screen. Some
	// tabs arm the Save button before they render what they adopted, and a
	// look at the old copy then would take the reply's changes for the user's.
	var expected = null;

	var saveData = () => Eternity.SavedGame.state.saveData;

	// The tabs whose changes are staged rather than typed in, by the name the
	// navigation bar gives them.
	var drafts = () => [
		['the Inventory tab', Eternity.InventoryEditor]
		, ['the Abilities tab', Eternity.AbilityEditor]
		, ['the Stronghold tab', Eternity.StrongholdEditor]
		, ['the Grimoire tab', Eternity.GrimoireEditor]
		, ['the Vendors tab', Eternity.VendorEditor]
	];

	/** What stops an undo now, in words, or null. */
	self.blocked = () => {
		if (busy) {
			return 'The last undo is still on its way';
		}

		if (Eternity.Modifications.state.saving) {
			return 'The save is being written';
		}

		var reason = null;
		drafts().forEach(entry => {
			var tab = entry[1];
			if (reason || !tab) {
				return;
			}

			if (tab.state.working) {
				reason = 'Wait for ' + entry[0] + ' to finish applying';
			} else if (tab.unapplied && tab.unapplied()) {
				reason = 'Apply or revert the changes in ' + entry[0] + ' first';
			}
		});

		if (reason) {
			return reason;
		}

		if (Eternity.ImportCharacter.state.importing || Eternity.PartyManagement.state.saving) {
			return 'Wait for the change on its way to finish';
		}

		return null;
	};

	var toast = (text, warning) => {
		self.html.historyToast.text(text).toggleClass('history-toast-warning', !!warning)
			.addClass('show');
		clearTimeout(toastTimer);
		toastTimer = setTimeout(() => self.html.historyToast.removeClass('show'), 2600);
	};

	var paint = () => {
		if (!self.html.undoButton) {
			return;
		}

		var undo = history.nextUndo();
		var redo = history.nextRedo();
		var on = self.state.enabled && !busy;

		self.html.undoButton.prop('disabled', !on || !undo)
			.attr('title', undo ? 'Undo ' + undo.label + ' (Ctrl+Z)' : 'Nothing to undo');
		self.html.redoButton.prop('disabled', !on || !redo)
			.attr('title', redo ? 'Redo ' + redo.label + ' (Ctrl+Y)' : 'Nothing to redo');
	};

	// ---- feeding the history -------------------------------------------------

	/** Something may have changed in the page's copy of the save. */
	self.notice = () => {
		if (!self.state.enabled || !saveData()) {
			return;
		}

		if (expected) {
			if (saveData() !== expected) {
				return;
			}

			expected = null;
		}

		var label = pendingLabel;
		var step = history.notice(saveData(), {
			at: Date.now()
			, element: document.activeElement
			, label: label
		});

		if (step && label) {
			made = step;
			pendingLabel = null;
		}

		paint();
	};

	/**
	 * Runs {@code change} and names the step it makes. Returns that step, or
	 * null when nothing changed.
	 */
	self.labelled = (label, change) => {
		pendingLabel = label;
		made = null;
		try {
			change();
			self.notice();
		} finally {
			pendingLabel = null;
		}

		return made;
	};

	/** An Apply's reply, adopted as {@code merged}: one step. */
	self.applied = (step, label, merged) => {
		history.applied(step, label || 'Applied changes', merged);
		expected = merged;
		paint();
	};

	/** A save has been opened or written: the history starts again. */
	self.opened = save => {
		history.reset(save);
		expected = null;
		paint();
	};

	/**
	 * Takes a step out of the history as if it had not happened: the inventory
	 * reverting a sale takes back the money the sale credited.
	 */
	self.discard = step => {
		var index = history.undo.indexOf(step);
		if (index >= 0) {
			SaveHistory.revert(saveData(), step);
			history.undo.splice(index, 1);
		} else if (history.redo.indexOf(step) >= 0) {
			history.redo.splice(history.redo.indexOf(step), 1);
		}

		history.rebase(saveData());
		paint();
	};

	// ---- undoing and redoing ---------------------------------------------------

	var finished = (undoing, step) => {
		paint();
		toast((undoing ? 'Undone: ' : 'Redone: ') + step.label);
	};

	var go = undoing => {
		var step = undoing ? history.nextUndo() : history.nextRedo();
		if (!self.state.enabled || !step) {
			return;
		}

		var reason = self.blocked();
		if (reason) {
			toast(reason + '.', true);
			return;
		}

		if (step.kind === 'values') {
			(undoing ? SaveHistory.revert : SaveHistory.reapply)(saveData(), step);
			history.settle(step, undoing, saveData());
			// The sheet and the tables read their numbers out of saveData,
			// so redrawing is what makes the change visible.
			Eternity.SavedGame.transition({});
			Eternity.Modifications.transition({modifications: true});
			finished(undoing, step);
			return;
		}

		busy = true;
		paint();
		var asked = history.ask();

		window.changeHistory({
			request: JSON.stringify({
				oldSave: Eternity.SavedGame.state.info.absolutePath
				, savedYet: Eternity.Modifications.state.savedYet
				, action: undoing ? 'undo' : 'redo'
				, step: step.step
			})
			, onSuccess: response => {
				busy = false;

				// The save was closed while this was on its way: there is
				// nothing on screen to put it into.
				if (!self.state.enabled) {
					return;
				}

				var merged = Eternity.SavedGame.adopt(JSON.parse(response));
				history.settle(step, undoing, merged, asked);
				expected = merged;

				// Nothing was staged (or this would not have run), so each tab
				// simply builds itself again from the save as it now is.
				drafts().forEach(entry => entry[1] && entry[1].reset && entry[1].reset());

				var active = Eternity.SavedGame.state.activeCharacter;
				if (active && !(merged.characters || []).some(c => c.GUID === active)) {
					active = false;
				}

				Eternity.SavedGame.transition({saveData: merged, activeCharacter: active});
				Eternity.Modifications.transition({modifications: true});
				finished(undoing, step);
			}
			, onFailure: (code, message) => {
				busy = false;
				// The page and the server no longer agree on what can be put
				// back, so the history starts again from here.
				history.reset(saveData());
				paint();
				Eternity.GenericError.render({msg: message});
			}
		});
	};

	self.undo = () => go(true);
	self.redo = () => go(false);

	/** For tests and the suites: the two lists, as labels, last first. */
	self.steps = () => ({
		undo: history.undo.map(step => step.label).reverse()
		, redo: history.redo.map(step => step.label).reverse()
	});

	// ---- the controls ------------------------------------------------------------

	// Ctrl+Z in a text box is that box's own undo; only elsewhere is it the
	// save's. A dialog that is open has the keyboard to itself.
	var typing = element => !!element && (/^(INPUT|TEXTAREA|SELECT)$/.test(element.nodeName)
		|| element.isContentEditable);

	self.init = () => {
		self.html.undoButton.click(self.undo);
		self.html.redoButton.click(self.redo);

		// The title says what would be undone; a draft that appeared since the
		// last change says itself when the pointer arrives.
		self.html.undoButton.add(self.html.redoButton).on('mouseenter', paint);

		$(document).keydown(e => {
			if (!self.state.enabled || !e.ctrlKey || e.altKey || e.metaKey) {
				return;
			}

			var undo = e.keyCode === 90 && !e.shiftKey;
			var redo = e.keyCode === 89 || (e.keyCode === 90 && e.shiftKey);
			if ((!undo && !redo) || typing(e.target) || $('.modal.in').length > 0) {
				return;
			}

			e.preventDefault();
			go(undo);
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.html.undoButton.toggle(self.state.enabled);
		self.html.redoButton.toggle(self.state.enabled);

		if (!self.state.enabled) {
			history.reset({});
			self.html.historyToast.removeClass('show');
		}

		paint();
	};
};

$.extend(EditHistory.prototype, Renderer.prototype);
