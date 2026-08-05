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

// The Abilities view: what the selected character knows, and a browser for
// everything they could learn.
//
// The two halves of the screen are not the same kind of thing. Abilities and
// spells are real objects in the save, so each one shown has its own GUID and
// removing it deletes that object. A talent is only a name on the character,
// with the abilities it grants living alongside as separate objects — which is
// why adding one has to add those too, and removing one has to take them away.
// The editor hides that difference; the save does not.
//
// Edits are staged and sent together, exactly like the inventory: a save of any
// size takes a couple of seconds to rewrite, and clicking six talents should
// not mean waiting six times.
var AbilityEditor = function () {
	var self = this;

	var defaultState = {
		enabled: false
		, character: false
		, working: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	// Staged changes, in the shape UpdateAbilities parses. Rendering works off
	// the save's own data plus these, so the view always shows the result.
	var pending = [];
	var browseKind = '';       // '', 'ability', 'spell' or 'talent'
	var browseOffset = 0;
	var browseTotal = 0;
	var browseLimit = 40;
	var browseAvailable = true;
	var browseEntries = [];
	var browseAnyClass = false;
	var browseTimer = null;
	var browsedFor = null;   // character the current browse results were fetched for
	var status = '';

	var KINDS = [
		{value: '', label: 'Everything'}
		, {value: 'ability', label: 'Abilities'}
		, {value: 'spell', label: 'Spells'}
		, {value: 'talent', label: 'Talents'}
	];

	var saveData = () => Eternity.SavedGame.state.saveData || {};
	var abilities = () => saveData().abilities || {};

	// Icons are fetched by key rather than arriving with the save: a full
	// party's ability art is over a megabyte, and bundling it into the opener's
	// single reply was enough to stop the save opening at all.
	var iconCache = {};
	var iconsRequested = {};

	var characterInfo = guid =>
		(abilities().characters || []).filter(c => c.guid === guid)[0] || null;

	var characterName = guid => {
		var match = (saveData().characters || []).filter(c => c.GUID === guid)[0];
		return match ? match.name : 'Unknown';
	};

	// The abilities this character would have if everything staged were
	// applied. Talent-granted objects are folded in the same way the server
	// will actually create them.
	var currentAbilities = guid => {
		var character = characterInfo(guid);
		if (!character) return [];

		var removed = {};
		var removedPrefabs = {};
		pending.forEach(change => {
			if (change.character !== guid) return;
			if (change.kind === 'removeAbility') removed[change.abilityGuid] = true;
			if (change.kind === 'removeTalent') {
				(change.grants || []).forEach(g => removedPrefabs[g.prefab] = true);
			}
		});

		var list = (character.abilities || []).filter(ability => {
			if (removed[ability.guid]) return false;
			// A talent's own object goes when the talent does, but only one
			// copy: the same ability can legitimately be there twice.
			if (removedPrefabs[ability.prefab]) {
				delete removedPrefabs[ability.prefab];
				return false;
			}

			return true;
		}).map(ability => $.extend({}, ability));

		pending.forEach(change => {
			if (change.character !== guid) return;
			if (change.kind === 'addAbility') {
				list.push(staged(change));
			}

			if (change.kind === 'addTalent') {
				(change.grants || []).forEach(g => list.push(staged(g)));
			}
		});

		return list;
	};

	var staged = source => ({
		guid: ''
		, prefab: source.prefab
		, key: source.key || (source.prefab || '').toLowerCase()
		, name: source.displayName || source.name || source.prefab
		, kind: source.spell ? 'spell' : 'ability'
		, icon: source.icon || ''
		, iconData: source.iconData || ''
		, description: source.description || ''
		, staged: true
	});

	var currentTalents = guid => {
		var character = characterInfo(guid);
		if (!character) return [];

		var removed = {};
		pending.forEach(change => {
			if (change.character === guid && change.kind === 'removeTalent') {
				removed[change.talent] = true;
			}
		});

		var list = (character.talents || [])
			.filter(talent => !removed[talent.prefab])
			.map(talent => $.extend({}, talent));

		pending.forEach(change => {
			if (change.character !== guid || change.kind !== 'addTalent') return;
			list.push({
				prefab: change.talent
				, key: change.key
				, name: change.displayName
				, icon: ''
				, iconData: change.iconData || ''
				, description: change.description || ''
				, staged: true
			});
		});

		return list;
	};

	var hasTalent = (guid, prefab) =>
		currentTalents(guid).filter(t => t.prefab === prefab).length > 0;

	// Icons arrive two ways: base64 attached to a browser result, and the cache
	// filled in by requestIcons for whatever the save already contains.
	var iconSource = entry => {
		if (entry.iconData) return 'data:image/png;base64,' + entry.iconData;
		var data = entry.key ? iconCache[entry.key] : '';
		return data ? 'data:image/png;base64,' + data : '';
	};

	// Asks for the icons of everything currently on screen that we don't have
	// yet, then redraws once they land.
	var requestIcons = entries => {
		var wanted = [];
		entries.forEach(entry => {
			var key = entry.key;
			if (!key || !entry.icon || iconCache[key] || iconsRequested[key]) return;
			iconsRequested[key] = true;
			wanted.push(key);
		});

		if (wanted.length < 1) return;

		window.browseAbilities({
			request: JSON.stringify({iconKeys: wanted})
			, onSuccess: response => {
				var result = JSON.parse(response);
				var received = result.icons || {};
				var any = false;
				for (var key in received) {
					if (!received.hasOwnProperty(key)) continue;
					iconCache[key] = received[key];
					any = true;
				}

				if (any) renderCharacter();
			}
			, onFailure: () => {
				// Missing art is cosmetic; leave the placeholder tiles alone.
				wanted.forEach(key => delete iconsRequested[key]);
			}
		});
	};

	var iconTile = entry => {
		var tile = $('<span>').addClass('abl-icon');
		var source = iconSource(entry);
		if (source) {
			tile.append($('<img>').attr('src', source).attr('alt', ''));
		} else {
			tile.addClass('abl-icon-empty');
		}

		return tile;
	};

	var metaLine = entry => {
		var parts = [];
		if (entry.kind === 'spell' && entry.spellLevel) {
			parts.push('Level ' + entry.spellLevel + ' spell');
		} else if (entry.kind === 'talent') {
			parts.push(entry.category ? entry.category + ' talent' : 'Talent');
		} else if (entry.effect === 'Racial') {
			parts.push('Racial');
		} else if (entry.kind) {
			parts.push(entry.kind.charAt(0).toUpperCase() + entry.kind.slice(1));
		}

		if (entry['class']) parts.push(entry['class']);
		if (entry.passive) parts.push('passive');
		if (entry.staged) parts.push('not applied yet');
		return parts.join(' · ');
	};

	var row = (entry, onRemove) => {
		var line = $('<div>').addClass('abl-row');
		if (entry.staged) line.addClass('abl-row-staged');

		line.append(iconTile(entry));

		var text = $('<span>').addClass('abl-text');
		text.append($('<span>').addClass('abl-name').text(entry.name || entry.prefab));
		text.append($('<span>').addClass('abl-meta').text(metaLine(entry)));
		line.append(text);

		if (entry.description) {
			line.attr('title', entry.description);
		}

		line.append($('<button>')
			.addClass('btn btn-xs abl-remove')
			.attr('type', 'button')
			.attr('title', 'Remove')
			.html('<i class="fa fa-times"></i>')
			.click(() => onRemove(entry)));

		return line;
	};

	// Abilities the game gave a character because of a talent are listed with
	// the talent, not on their own — removing them individually would leave the
	// talent claiming an ability that no longer exists.
	var isTalentAbility = entry => entry.effect === 'Talent';

	var renderCharacter = () => {
		var guid = self.state.character;
		var character = characterInfo(guid);

		self.html.ablCharacterName.text(characterName(guid));
		self.html.ablCharacterMeta.text(character
			? [character.characterClass, 'level ' + character.level]
				.filter(part => part && part !== 'level 0').join(' · ')
			: '');

		var owned = currentAbilities(guid);
		var talents = currentTalents(guid);

		var list = self.html.ablList.empty();
		var granted = owned.filter(isTalentAbility);
		var learned = owned.filter(entry => !isTalentAbility(entry));

		if (learned.length < 1) {
			list.append($('<div>').addClass('abl-empty')
				.text('This character has no abilities or spells of their own.'));
		}

		learned.sort(byName).forEach(entry => list.append(row(entry, removeAbility)));

		self.html.ablCount.text(learned.length + ' abilities and spells');

		var talentList = self.html.ablTalents.empty();
		if (talents.length < 1) {
			talentList.append($('<div>').addClass('abl-empty').text('No talents.'));
		}

		talents.sort(byName).forEach(entry =>
			talentList.append(row(entry, removeTalent)));

		self.html.ablTalentCount.text(talents.length + ' talents');

		// Shown read-only so it's obvious where the extra abilities came from.
		var grantedList = self.html.ablGranted.empty();
		if (granted.length < 1) {
			grantedList.append($('<div>').addClass('abl-empty')
				.text('Nothing granted by talents.'));
		}

		granted.sort(byName).forEach(entry => {
			var line = $('<div>').addClass('abl-row abl-row-locked');
			line.append(iconTile(entry));
			var text = $('<span>').addClass('abl-text');
			text.append($('<span>').addClass('abl-name').text(entry.name || entry.prefab));
			text.append($('<span>').addClass('abl-meta').text('from a talent'));
			line.append(text);
			if (entry.description) line.attr('title', entry.description);
			grantedList.append(line);
		});

		self.html.ablStatus.text(status);
		self.html.ablApply.prop('disabled', pending.length < 1 || !!self.state.working);
		self.html.ablRevert.prop('disabled', pending.length < 1 || !!self.state.working);
		self.html.ablPending.text(pending.length < 1
			? '' : pending.length + ' staged change(s)');

		requestIcons(owned.concat(talents));
	};

	var byName = (a, b) =>
		(a.name || a.prefab || '').localeCompare(b.name || b.prefab || '');

	var removeAbility = entry => {
		if (entry.staged) {
			// Undo the staged addition rather than recording a removal for
			// something the save has never heard of.
			pending = pending.filter(change =>
				!(change.kind === 'addAbility'
					&& change.character === self.state.character
					&& change.prefab === entry.prefab));

			status = 'Removed the staged ' + (entry.name || entry.prefab) + '.';
			redraw();
			return;
		}

		pending.push({
			kind: 'removeAbility'
			, character: self.state.character
			, abilityGuid: entry.guid
		});

		status = 'Staged removal of ' + (entry.name || entry.prefab) + '.';
		redraw();
	};

	var removeTalent = entry => {
		if (entry.staged) {
			pending = pending.filter(change =>
				!(change.kind === 'addTalent'
					&& change.character === self.state.character
					&& change.talent === entry.prefab));

			status = 'Removed the staged ' + (entry.name || entry.prefab) + '.';
			redraw();
			return;
		}

		// The grants come from the catalog entry, which the browser has and the
		// save's own data does not — look it up so removal can take the talent's
		// abilities away with it.
		var catalogued = browseEntries.filter(e => e.prefab === entry.prefab)[0];
		pending.push({
			kind: 'removeTalent'
			, character: self.state.character
			, talent: entry.prefab
			, grants: catalogued ? catalogued.grants : []
			, skills: catalogued ? catalogued.skills : {}
		});

		status = catalogued
			? 'Staged removal of ' + (entry.name || entry.prefab) + '.'
			: 'Staged removal of ' + (entry.name || entry.prefab)
				+ ' — search for it below first if you want its granted '
				+ 'abilities removed too.';

		redraw();
	};

	var add = entry => {
		if (!self.state.character) return;

		if (entry.kind === 'talent') {
			if (hasTalent(self.state.character, entry.prefab)) {
				status = characterName(self.state.character)
					+ ' already has ' + entry.displayName + '.';

				redraw();
				return;
			}

			pending.push({
				kind: 'addTalent'
				, character: self.state.character
				, talent: entry.prefab
				, key: entry.key
				, displayName: entry.displayName
				, description: entry.description
				, iconData: entry.icon
				, grants: entry.grants || []
				, skills: entry.skills || {}
			});

			status = 'Staged ' + entry.displayName + '.';
			redraw();
			return;
		}

		pending.push({
			kind: 'addAbility'
			, character: self.state.character
			, prefab: entry.prefab
			, key: entry.key
			, path: entry.path
			, component: entry.component
			, effect: entry.effect
			, spell: entry.spell
			, 'class': entry['class']
			, displayName: entry.displayName
			, description: entry.description
			, iconData: entry.icon
		});

		status = 'Staged ' + entry.displayName + '.';
		redraw();
	};

	var renderKindFilter = () => {
		var host = self.html.ablBrowseKinds.empty();
		KINDS.forEach(kind => {
			var button = $('<button>')
				.addClass('btn btn-xs abl-kind')
				.attr('type', 'button')
				.text(kind.label);

			if (browseKind === kind.value) button.addClass('abl-kind-on');
			button.click(() => {
				browseKind = kind.value;
				browseOffset = 0;
				requestBrowse();
			});

			host.append(button);
		});
	};

	var scheduleBrowse = () => {
		window.clearTimeout(browseTimer);
		browseTimer = window.setTimeout(() => {
			browseOffset = 0;
			requestBrowse();
		}, 250);
	};

	var requestBrowse = () => {
		var character = characterInfo(self.state.character) || {};

		window.browseAbilities({
			request: JSON.stringify({
				search: self.html.ablBrowseSearch.val() || ''
				, kind: browseKind
				, characterClass: character.characterClass || ''
				, progressionTable: character.progressionTable || ''
				, subrace: character.characterSubrace || ''
				, isPlayer: !!character.isPlayer
				, anyClass: browseAnyClass
				, offset: browseOffset
				, limit: browseLimit
			})
			, onSuccess: response => {
				var result = JSON.parse(response);
				browseEntries = result.abilities || [];
				browseTotal = result.total || 0;
				browseAvailable = !!result.available;
				renderBrowse();
			}
			, onFailure: (code, message) => {
				status = 'Could not read the ability catalog: ' + message;
				redraw();
			}
		});
	};

	var renderBrowse = () => {
		var grid = self.html.ablBrowseGrid.empty();

		if (!browseAvailable) {
			self.html.ablBrowseCount.text('no ability catalog installed');
			self.html.ablBrowsePage.text('');
			grid.append($('<div>').addClass('abl-empty').text(
				'The ability catalog is built from your game install. '
				+ 'Without it the editor can still list what a save contains, '
				+ 'but it cannot offer anything new.'));

			return;
		}

		self.html.ablBrowseCount.text(browseTotal + ' matches');
		self.html.ablBrowsePage.text(browseTotal < 1 ? '' : (browseOffset + 1)
			+ '–' + Math.min(browseOffset + browseLimit, browseTotal));

		self.html.ablBrowsePrev.prop('disabled', browseOffset <= 0);
		self.html.ablBrowseNext.prop(
			'disabled', browseOffset + browseLimit >= browseTotal);

		if (browseEntries.length < 1) {
			grid.append($('<div>').addClass('abl-empty').text('Nothing matches.'));
			return;
		}

		// Anything the character already has is shown but not offered again, the
		// way the game greys out a talent you have already bought.
		var owned = {};
		currentAbilities(self.state.character).forEach(a => owned[a.prefab] = true);
		currentTalents(self.state.character).forEach(t => owned[t.prefab] = true);

		browseEntries.forEach(entry => {
			var line = $('<div>').addClass('abl-row abl-row-add');
			var already = !!owned[entry.prefab];
			if (already) line.addClass('abl-row-owned');
			line.append(iconTile({icon: '', iconData: entry.icon}));

			var text = $('<span>').addClass('abl-text');
			text.append($('<span>').addClass('abl-name').text(entry.displayName));
			text.append($('<span>').addClass('abl-meta').text(metaLine({
				kind: entry.kind
				, spellLevel: entry.spellLevel
				, category: entry.category
				, 'class': entry['class']
				, passive: entry.passive
			}) + (entry.unlockLevel > 1 ? ' · from level ' + entry.unlockLevel : '')
				+ (already ? ' · already known' : '')));

			line.append(text);
			if (entry.description) line.attr('title', entry.description);

			line.append($('<button>')
				.addClass('btn btn-xs abl-add')
				.attr('type', 'button')
				.attr('title', already
					? 'This character already has it'
					: 'Give this to the selected character')
				.prop('disabled', already)
				.html('<i class="fa fa-' + (already ? 'check' : 'plus') + '"></i>')
				.click(() => { if (!already) add(entry); }));

			grid.append(line);
		});
	};

	var redraw = () => {
		renderCharacter();
		renderKindFilter();
	};

	self.reset = () => {
		pending = [];
		status = '';
		browsedFor = null;
	};

	self.setStatus = message => {
		status = message;
		self.html.ablStatus.text(message);
	};

	self.buildChanges = () => pending.slice();

	self.init = () => {
		self.html.ablApply.click(() => self.apply());
		self.html.ablRevert.click(() => {
			self.reset();
			status = 'Discarded the staged changes.';
			redraw();
		});

		self.html.ablBrowseSearch.keyup(scheduleBrowse);
		self.html.ablAnyClass.change(function () {
			browseAnyClass = $(this).is(':checked');
			browseOffset = 0;
			requestBrowse();
		});

		self.html.ablBrowsePrev.click(() => {
			browseOffset = Math.max(0, browseOffset - browseLimit);
			requestBrowse();
		});

		self.html.ablBrowseNext.click(() => {
			browseOffset += browseLimit;
			requestBrowse();
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);

		if (!self.state.enabled) {
			// Going back to the save list must not carry staged edits into
			// whatever is opened next.
			self.reset();
			return;
		}

		if (!self.html.abilitiesView.is(':visible')) {
			return;
		}

		if (!self.state.character) {
			var first = (abilities().characters || [])[0];
			if (first) self.state.character = first.guid;
		}

		redraw();

		// The browser's contents depend on whose class is selected, so it is
		// refetched when the character changes and not on every render.
		if (browsedFor !== self.state.character) {
			browsedFor = self.state.character;
			browseOffset = 0;
			requestBrowse();
		}
	};
};

AbilityEditor.prototype.apply = function () {
	var self = this;
	var changes = self.buildChanges();

	if (changes.length < 1) {
		self.setStatus('No ability changes to apply.');
		return;
	}

	self.transition({working: true, character: self.state.character});
	self.setStatus('Applying ' + changes.length + ' change(s)…');

	window.updateAbilities({
		request: JSON.stringify({
			oldSave: Eternity.SavedGame.state.info.absolutePath
			, savedYet: Eternity.Modifications.state.savedYet
			, changes: changes
		})
		, onSuccess: response => {
			var previous = Eternity.SavedGame.state.saveData;
			var updated = JSON.parse(response);

			// Never replace saveData wholesale — unsaved edits made in the
			// other editors live only in that object. Nothing here touches
			// stats, currency or globals, so carry those across untouched.
			updated.characters.forEach(character => {
				var before = previous.characters.filter(c => c.GUID === character.GUID)[0];
				if (before && before.stats) {
					character.stats = before.stats;
				}
			});

			updated.currency = previous.currency;
			updated.globals = previous.globals;

			self.reset();
			self.state.working = false;
			Eternity.Modifications.transition({modifications: true});
			Eternity.SavedGame.render({
				saveData: updated
				, info: Eternity.SavedGame.state.info
				, activeCharacter: Eternity.SavedGame.state.activeCharacter
				, view: Eternity.SavedGame.views.ABILITIES
			});
		}
		, onFailure: (code, message) => {
			self.transition({working: false, character: self.state.character});
			self.setStatus('Ability update failed: ' + message);
		}
	});
};

$.extend(AbilityEditor.prototype, Renderer.prototype);
