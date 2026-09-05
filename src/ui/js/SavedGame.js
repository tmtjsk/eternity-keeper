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

var SavedGame = function () {
	var self = this;
	var disabledForCompanions = [
		'BaseMight', 'BaseConstitution', 'BaseDexterity', 'BaseIntellect', 'BasePerception'
		, 'BaseResolve'];

	// The stats players most commonly want to edit, pinned to the top of the
	// Raw view in this order. Everything else follows alphabetically.
	var importantStats = [
		'Experience', 'Level', 'RemainingSkillPoints'
		, 'AthleticsSkill', 'LoreSkill', 'MechanicsSkill', 'StealthSkill'
		, 'SurvivalSkill', 'CraftingSkill'
		, 'MaxHealth', 'MaxStamina'
		, 'BaseMight', 'BaseConstitution', 'BaseDexterity', 'BasePerception'
		, 'BaseIntellect', 'BaseResolve'
		, 'BaseDeflection', 'BaseFortitude', 'BaseReflexes', 'BaseWill'];

	self.views = Object.freeze({
		ATTR: 0, RAW: 1, GLOBALS: 2, CONSOLE: 3, INVENTORY: 4, ABILITIES: 5
		, STRONGHOLD: 6});

	var defaultState = {
		saveData: {}
		, info: {}
		, activeCharacter: false
		, view: self.views.ATTR
	};

	var populateCharacterList = (container, characters) => {
		// The main character always sits at the top marked with a star; the
		// rest of the party is sorted alphabetically.
		var sorted = characters.slice().sort((a, b) => {
			if (!!a.isMainCharacter !== !!b.isMainCharacter) {
				return a.isMainCharacter ? -1 : 1;
			}

			return a.name.localeCompare(b.name);
		});

		container.append(
			sorted.map(character => {
				var row = $('<li>')
					.data('guid', character.GUID)
					.append($('<i>').addClass(
						character.isMainCharacter
							? 'fa fa-star main-character-star'
							: 'fa fa-heartbeat'))
					.append(document.createTextNode(' ' + character.name))
					.addClass(character.isDead ? 'dead' : '')
					.click(self.switchCharacter.bind(self, character.GUID));

				// Every living party member carries their own pack, so give
				// each one a direct way into their inventory rather than
				// making people select the character and then find the tab.
				if (!character.isDead) {
					row.append($('<i>')
						.addClass('fa fa-briefcase character-inventory')
						.attr('title', 'Open ' + character.name + "'s inventory")
						.click(event => {
							event.stopPropagation();
							self.switchCharacter(character.GUID);
							self.switchView(self.views.INVENTORY);
						}));
				}

				return row;
			}));
	};

	var sortData = unsorted => {
		var sortable = [];
		for (var k in unsorted) {
			if (!unsorted.hasOwnProperty(k)) {
				continue;
			}

			sortable.push([k, unsorted[k]]);
		}

		sortable.sort((a, b) => a[0].toLowerCase() > b[0].toLowerCase() ? 1 : -1);
		return sortable;
	};

	var createBooleanEditor = initialValue => {
		return createEnumEditor(['true', 'false'], initialValue);
	};

	var createEnumEditor = (enumValues, initialValue) => {
		return $('<select></select>')
			.addClass('form-control')
			.append(enumValues.map(v =>
				$('<option></option>').prop('selected', v == initialValue).text(v).val(v)));
	};

	var createRawEditor = (key, fullkey, value, locked) => {
		var row = $('<tr></tr>');
		var keyCol = $('<td></td>');
		var valCol = $('<td></td>');
		var editor;

		keyCol.text(key);
		valCol.data('key', key);
		valCol.data('fullkey', fullkey);

		// The game rebuilds companion base attributes from their character
		// template on every load, so editing them here would silently do
		// nothing in-game — show them read-only instead.
		if (locked) {
			valCol.text(value.value)
				.addClass('raw-locked')
				.attr('title', 'The game recalculates companion attributes from their '
					+ 'template on load; this value cannot be edited.');
			row.append(keyCol, valCol);
			return row;
		}

		if (value.type === 'java.lang.Boolean') {
			editor = createBooleanEditor(value.value);
			valCol.append(editor);
			editor.change(self.update.bind(self));
		} else if (Eternity.structures[value.type] !== undefined) {
			editor = createEnumEditor(Eternity.structures[value.type], value.value);
			valCol.append(editor);
			editor.change(self.update.bind(self));
		} else {
			valCol.prop('contenteditable', true);
			valCol.text(value.value);
			valCol.keyup(self.update.bind(self));
		}

		row.append(keyCol, valCol);
		return row;
	};

	var populateGlobals = globals => {
		var globalsTable = self.html.globalsTable.find('tbody');
		globalsTable.empty();

		var flat = flattenObject(globals, 2);
		var tuples = [];
		flat.forEach(tuple => {
			for (var key in tuple[1]) {
				if (!tuple[1].hasOwnProperty(key)) {
					continue;
				}

				tuples.push([tuple[0], key, tuple[1][key]]);
			}
		});

		tuples.sort((a, b) => a[1].toLowerCase() > b[1].toLowerCase() ? 1 : -1);
		tuples.forEach(tuple => {
			var row = createRawEditor(tuple[1], tuple[0] + '.' + tuple[1], tuple[2]);
			globalsTable.append(row);
		});
	};

	// The save stores cumulative skill POINTS, but the character sheet shows a
	// RANK: reaching rank N costs 1+2+...+N points. Editing points directly is
	// why small changes look like they did nothing, so the editor works in
	// ranks and writes the exact point total the game would.
	// (CharacterStats.GetPointsForSkillLevel / CalculateSkillLevelViaPoints.)
	var SKILLS = [
		{stat: 'AthleticsSkill', label: 'Athletics'}
		, {stat: 'StealthSkill', label: 'Stealth'}
		, {stat: 'LoreSkill', label: 'Lore'}
		, {stat: 'MechanicsSkill', label: 'Mechanics'}
		, {stat: 'SurvivalSkill', label: 'Survival'}
		, {stat: 'CraftingSkill', label: 'Crafting'}
	];

	var MAX_SKILL_RANK = 20;

	var pointsForRank = rank => rank * (rank + 1) / 2;

	var rankForPoints = points => {
		var rank = 0;
		var total = 0;
		while (total + (rank + 1) <= points && rank < 100) {
			rank++;
			total += rank;
		}

		return rank;
	};

	// ---- identity -----------------------------------------------------------
	//
	// Race, subrace, culture, class, background and gender are [Persistent]
	// enums on CharacterStats, so they are written on Save by the ordinary
	// scalar path -- there is nothing new to send. What needs care is the
	// consequences, all of them from the decompiled game:
	//
	//   * RacialBodyType is NOT editable. Awake() sets it to CharacterRace for
	//     anyone who is not godlike, and for a godlike it holds the body
	//     underneath -- a Moon Godlike player really does carry an Aumaua
	//     body. Writing it would either be pointless or break that.
	//   * Equipment.HasEquipmentSlot means a grimoire slot exists only for a
	//     wizard and a head slot only for a non-godlike. Nothing in the game
	//     repairs an item left in a slot that stops existing, so the editor
	//     says so rather than stranding it silently.
	//   * Restored() rebuilds m_abilities from whatever ability objects exist;
	//     it never checks them against the class. Changing class therefore
	//     leaves the old class's abilities behind, which is worth saying.

	// Only the races and classes a player character can actually be. The enums
	// carry creature types too (Beast, Spirit, Troll, Ogre...) which have no
	// portraits, no subraces and no progression table.
	var PLAYABLE_RACES = ['Human', 'Elf', 'Dwarf', 'Godlike', 'Orlan', 'Aumaua'];
	var PLAYABLE_CLASSES = [
		'Fighter', 'Rogue', 'Priest', 'Wizard', 'Barbarian', 'Ranger'
		, 'Druid', 'Paladin', 'Monk', 'Cipher', 'Chanter'];

	// Which subraces belong to which race. The godlike set is exactly what
	// CharacterStats.SubraceIsGodlike() tests for.
	var SUBRACES_BY_RACE = {
		Human: ['Meadow_Human', 'Ocean_Human', 'Savannah_Human']
		, Elf: ['Wood_Elf', 'Snow_Elf']
		, Dwarf: ['Mountain_Dwarf', 'Boreal_Dwarf']
		, Godlike: ['Death_Godlike', 'Fire_Godlike', 'Nature_Godlike'
			, 'Moon_Godlike', 'Avian_Godlike']
		, Orlan: ['Hearth_Orlan', 'Wild_Orlan']
		, Aumaua: ['Coastal_Aumaua', 'Island_Aumaua']
	};

	var IDENTITY = [
		{stat: 'CharacterRace', label: 'Race', only: PLAYABLE_RACES}
		, {stat: 'CharacterSubrace', label: 'Subrace'}
		, {stat: 'CharacterClass', label: 'Class', only: PLAYABLE_CLASSES}
		, {stat: 'CharacterCulture', label: 'Culture'}
		, {stat: 'CharacterBackground', label: 'Background'}
		, {stat: 'Gender', label: 'Gender'}
		// Only these two classes have anything to choose.
		, {stat: 'Deity', label: 'Deity', whenClass: 'Priest'}
		, {stat: 'PaladinOrder', label: 'Order', whenClass: 'Paladin'}
	];

	// Sentinels, and the values the game itself marks as unusable.
	var isRealOption = value =>
		value !== 'Count' && value !== 'Undefined' && value.indexOf('DO_NOT_USE') < 0;

	var statValue = (data, stat) =>
		data.stats[stat] === undefined ? '' : String(data.stats[stat].value);

	// Enum constants are either underscored (Moon_Godlike) or run together
	// (IxamitlPlains, GoldpactKnights); the game shows them spaced.
	var humanise = value => String(value)
		.replace(/_/g, ' ')
		.replace(/([a-z])([A-Z])/g, '$1 $2')
		.replace(/\s+/g, ' ')
		.trim();

	// What this character has equipped in a named slot, or null. The inventory
	// payload has already resolved slot names for us.
	var equippedIn = (data, slot) => {
		var inventory = (self.state.saveData || {}).inventory || {};
		var found = (inventory.characters || []).filter(c => c.guid === data.GUID);
		if (found.length < 1) {
			return null;
		}

		// equipment.slots is an ordered array of {slot, flag, index, item},
		// laid out by EquipmentSet.SerializedEquipment rather than keyed by
		// name.
		var slots = (found[0].equipment || {}).slots || [];
		var match = slots.filter(entry => entry.slot === slot);
		var item = match.length > 0 ? match[0].item : null;
		return item && item.displayName ? item.displayName : null;
	};

	var optionsFor = (entry, data) => {
		var type = data.stats[entry.stat] ? data.stats[entry.stat].type : '';
		var all = Eternity.structures[type] || [];

		if (entry.stat === 'CharacterSubrace') {
			var race = statValue(data, 'CharacterRace');
			return SUBRACES_BY_RACE[race] || all.filter(isRealOption);
		}

		if (entry.only) {
			return entry.only.filter(value => all.indexOf(value) > -1);
		}

		return all.filter(isRealOption);
	};

	var identityWarnings = data => {
		var warnings = [];

		if (statValue(data, 'CharacterClass') !== 'Wizard') {
			var grimoire = equippedIn(data, 'Grimoire');
			if (grimoire) {
				warnings.push('A non-wizard has no grimoire slot, so ' + grimoire
					+ ' can no longer be reached. Unequip it in the Inventory tab.');
			}
		}

		if (statValue(data, 'CharacterRace') === 'Godlike') {
			var head = equippedIn(data, 'Head');
			if (head) {
				warnings.push('Godlike have no head slot, so ' + head
					+ ' can no longer be reached. Unequip it in the Inventory tab.');
			}
		}

		return warnings;
	};

	var populateIdentity = data => {
		var grid = self.html.identityGrid.empty();
		var note = self.html.identityNote.empty();

		// A companion the game deleted carries no stats to edit.
		if (data.resurrectable || !data.stats
			|| data.stats.CharacterRace === undefined) {

			self.html.identityPanel.hide();
			return;
		}

		self.html.identityPanel.show();
		var characterClass = statValue(data, 'CharacterClass');

		IDENTITY.forEach(entry => {
			if (entry.whenClass && entry.whenClass !== characterClass) {
				return;
			}

			var current = data.stats[entry.stat];
			if (current === undefined) {
				return;
			}

			var options = optionsFor(entry, data);
			var select = $('<select>')
				.addClass('form-control identity-select')
				.append(options.map(value => $('<option>')
					.prop('selected', value === String(current.value))
					.text(humanise(value))
					.val(value)));

			// A value the game holds that the list does not offer (an NPC race
			// on a summoned creature, say) still has to be visible rather than
			// silently reassigned to the first option.
			if (options.indexOf(String(current.value)) < 0) {
				select.prepend($('<option>')
					.prop('selected', true)
					.text(humanise(current.value) + ' (unusual)')
					.val(String(current.value)));
			}

			select.on('change', () => {
				current.value = select.val();

				// A subrace has to belong to its race, or the save ends up
				// with a Wood Elf dwarf.
				if (entry.stat === 'CharacterRace') {
					var valid = SUBRACES_BY_RACE[select.val()] || [];
					var subrace = data.stats.CharacterSubrace;
					if (subrace && valid.length > 0
						&& valid.indexOf(String(subrace.value)) < 0) {

						subrace.value = valid[0];
					}
				}

				Eternity.Modifications.transition({modifications: true});
				populateIdentity(data);
			});

			grid.append($('<div>')
				.addClass('identity-field')
				.append($('<label>').text(entry.label))
				.append(select));
		});

		identityWarnings(data).forEach(text =>
			note.append($('<div>').addClass('identity-warning').text(text)));

		note.append($('<div>')
			.addClass('identity-hint')
			.text('Changing class leaves the old class’s abilities in place — '
				+ 'the game rebuilds them from what the save holds rather than '
				+ 'from the class. Use the Abilities tab to sort those out.'));
	};

	var populateSkills = (data) => {
		var grid = self.html.skillsGrid.empty();
		var available = SKILLS.filter(skill => data.stats[skill.stat] !== undefined);

		if (available.length < 1) {
			self.html.skillsNote.text('This character has no skill data.');
			self.html.remainingSkillPoints.val('').prop('disabled', true);
			return;
		}

		var remaining = data.stats.RemainingSkillPoints;
		self.html.remainingSkillPoints
			.prop('disabled', remaining === undefined)
			.val(remaining === undefined ? '' : remaining.value)
			.off()
			.on('change keyup', function () {
				var value = parseInt($(this).val(), 10);
				if (!isNaN(value) && value >= 0 && remaining !== undefined) {
					remaining.value = value;
					Eternity.Modifications.transition({modifications: true});
				}
			});

		available.forEach(skill => {
			var entry = data.stats[skill.stat];
			var points = parseInt(entry.value, 10) || 0;
			var rank = rankForPoints(points);

			var readout = $('<span>').addClass('skill-points');
			var input = $('<input>')
				.attr({type: 'number', min: 0, max: MAX_SKILL_RANK})
				.addClass('form-control skill-rank')
				.val(rank);

			var describe = (currentRank, currentPoints) => {
				var spare = currentPoints - pointsForRank(currentRank);
				readout.text(currentPoints + ' pts'
					+ (spare > 0 ? ' (' + spare + ' spare)' : ''));
			};

			describe(rank, points);

			input.on('change keyup', function () {
				var wanted = parseInt($(this).val(), 10);
				if (isNaN(wanted) || wanted < 0 || wanted > MAX_SKILL_RANK) {
					return;
				}

				// Writing the exact cost keeps the sheet and the save agreeing;
				// any leftover points from the old value are dropped, which is
				// what the game itself would store for that rank.
				entry.value = pointsForRank(wanted);
				describe(wanted, entry.value);
				Eternity.Modifications.transition({modifications: true});
			});

			grid.append($('<div>').addClass('skill-row')
				.append($('<label>').addClass('skill-name').text(skill.label))
				.append(input)
				.append(readout));
		});

		// Unlike the six base attributes, skills are not re-copied from the
		// prefab on load, so these stick for companions too.
		self.html.skillsNote.text(
			'Rank ' + 0 + '–' + MAX_SKILL_RANK + '. Rank N costs '
			+ 'N(N+1)/2 points; the game shows the rank, the save stores points.');
	};

	var populateCharacter = (container, data) => {
		var portrait = container.find('.portrait')
			.empty()
			.css('background-image', 'url(data:image/png;base64,' + data.portrait + ')')
			.css('background-repeat', 'no-repeat')
			.html((data.isDead) ? '<div>DEAD</div>' : '');

		if (data.resurrectable) {
			portrait.append(
				$('<button type="button">')
					.addClass('pm-btn pm-btn-dialog resurrect-btn')
					.html('<i>&#10094;</i> Resurrect <i>&#10095;</i>')
					.click(self.resurrect.bind(self, data.GUID)));
		}

		// Synthetic dead-companion entries carry no stats; clear the inputs
		// so the previous character's values don't linger, and lock them.
		container.find('.stats input')
			.val('')
			.prop('disabled', !!data.resurrectable);

		var sortedData = sortData(data.stats);
		var rawTable = self.html.rawTable.find('tbody');
		rawTable.empty();

		var sectionHeader = text =>
			$('<tr>').addClass('raw-section-header')
				.append($('<td>').attr('colspan', 2).text(text));

		var lockedStat = stat =>
			data.isCompanion && disabledForCompanions.indexOf(stat) > -1;

		// Pinned section first, in curated order...
		var pinned = importantStats.filter(stat => data.stats[stat] !== undefined);
		if (pinned.length > 0) {
			rawTable.append(sectionHeader('Most useful'));
			pinned.forEach(stat =>
				rawTable.append(createRawEditor(stat, stat, data.stats[stat], lockedStat(stat))));
			rawTable.append(sectionHeader('All stats (A–Z)'));
		}

		sortedData.forEach(tuple => {
			var stat = tuple[0];
			var value = tuple[1];

			container
				.find('.stats')
				.find('input[data-fullkey="' + stat + '"]')
				.val(value.value.toString())
				.prop('disabled', lockedStat(stat));

			if (pinned.indexOf(stat) < 0) {
				rawTable.append(createRawEditor(stat, stat, value, lockedStat(stat)));
			}
		});

		container
			.find('.stats')
			.find('input')
			.change(self.update.bind(self))
			.keyup(self.update.bind(self));

		populateSkills(data);
		populateIdentity(data);
	};

	var filterTable = (search, table) => {
		var searchString = search.val().toLowerCase();
		if (searchString.length < 1) {
			table.find('tbody tr').show();
			return;
		}

		var matches =
			table.find('td:last-child')
				.filter((i, el) => {
					// Section-header rows carry no key.
					var key = $(el).data('key');
					return key && key.toLowerCase().includes(searchString);
				});

		table.find('tbody tr').hide();
		matches.each((i, el) => $(el).parent().show());
	};

	self.init = () => {
		// Collapsing the character list gives the inventory grids room on
		// smaller screens; the choice sticks between sessions.
		var applySidebar = collapsed => {
			$('body').toggleClass('sidebar-collapsed', collapsed);
			self.html.sidebarToggle
				.attr('title', collapsed ? 'Show the character list' : 'Hide the character list')
				.find('i')
				.attr('class', collapsed ? 'fa fa-chevron-right' : 'fa fa-chevron-left');
		};

		var collapsed = false;
		try {
			collapsed = localStorage.getItem('ekSidebar') === 'collapsed';
		} catch (e) {
			// localStorage may be unavailable; default to expanded.
		}

		applySidebar(collapsed);
		self.html.sidebarToggle.click(() => {
			collapsed = !collapsed;
			applySidebar(collapsed);

			try {
				localStorage.setItem('ekSidebar', collapsed ? 'collapsed' : 'open');
			} catch (e) {
				// Not persisting the choice is harmless.
			}
		});

		self.html.searchRaw.keyup(filterTable.bind(self, self.html.searchRaw, self.html.rawTable));
		self.html.searchGlobals.keyup(
			filterTable.bind(self, self.html.searchGlobals, self.html.globalsTable));
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.html.characterList.empty();
		Eternity.render({saveView: true});

		self.html.menuCharacterAttributes.off();
		self.html.menuCharacterAttributes.click(self.switchView.bind(self, self.views.ATTR));
		self.html.menuCharacterRaw.off();
		self.html.menuCharacterRaw.click(self.switchView.bind(self, self.views.RAW));
		self.html.menuEditGlobals.off();
		self.html.menuEditGlobals.click(self.switchView.bind(self, self.views.GLOBALS));
		self.html.menuOpenConsole.off();
		self.html.menuOpenConsole.click(self.switchView.bind(self, self.views.CONSOLE));
		Eternity.InventoryEditor.html.menuInventoryEditor.off();
		Eternity.InventoryEditor.html.menuInventoryEditor.click(
			self.switchView.bind(self, self.views.INVENTORY));
		Eternity.AbilityEditor.html.menuCharacterAbilities.off();
		Eternity.AbilityEditor.html.menuCharacterAbilities.click(
			self.switchView.bind(self, self.views.ABILITIES));
		Eternity.StrongholdEditor.html.menuStrongholdEditor.off();
		Eternity.StrongholdEditor.html.menuStrongholdEditor.click(
			self.switchView.bind(self, self.views.STRONGHOLD));

		Eternity.CurrencyEditor.render({enabled: true, amount: self.state.saveData.currency});
		Eternity.Modifications.html.newSaveName.val(
			Eternity.Modifications.suggestSaveName(self.state.info));
		populateCharacterList(self.html.characterList, self.state.saveData.characters);
		populateGlobals(self.state.saveData.globals);

		if (self.state.activeCharacter) {
			self.html.characterList.find('li')
				.removeClass('active')
				.filter((i, li) => $(li).data('guid') === self.state.activeCharacter)
				.addClass('active');
			var character =
				self.state.saveData.characters.filter(c => c.GUID == self.state.activeCharacter);
			if (character.length > 0) {
				populateCharacter(self.html.character, character[0]);
			}
		} else {
			// A freshly opened save always starts on the main character.
			var characters = self.state.saveData.characters;
			var main = characters.filter(c => c.isMainCharacter)[0] || characters[0];
			self.switchCharacter(main.GUID);
		}

		$('.view').hide();
		switch(self.state.view) {
			case self.views.RAW:
				self.html.rawTable.show();
				filterTable(self.html.searchRaw, self.html.rawTable);
				break;

			case self.views.GLOBALS:
				self.html.globalsTable.show();
				filterTable(self.html.searchGlobals, self.html.globalsTable);
				break;

			case self.views.CONSOLE:
				Eternity.ConsoleTab.html.consoleView.show();
				Eternity.ConsoleTab.transition({enabled: true});
				break;

			case self.views.INVENTORY:
				Eternity.InventoryEditor.html.inventoryView.show();
				Eternity.InventoryEditor.transition({
					enabled: true, character: self.state.activeCharacter});
				break;

			case self.views.ABILITIES:
				Eternity.AbilityEditor.html.abilitiesView.show();
				Eternity.AbilityEditor.transition({
					enabled: true, character: self.state.activeCharacter});
				break;

			// The stronghold belongs to the party rather than to whoever
			// happens to be selected, so no character rides along with it.
			case self.views.STRONGHOLD:
				Eternity.StrongholdEditor.html.strongholdView.show();
				Eternity.StrongholdEditor.transition({enabled: true});
				break;

			default:
				self.html.character.show();
		}
	};
};

SavedGame.prototype.switchCharacter = function (guid) {
	var self = this;
	self.transition({activeCharacter: guid});
};

SavedGame.prototype.resurrect = function (guid) {
	var self = this;
	var button = self.html.character.find('.resurrect-btn');
	button.prop('disabled', true)
		.html('<i class="fa fa-spinner fa-pulse"></i> Resurrecting&hellip;');

	var success = response => {
		response = JSON.parse(response);

		if (response.error) {
			button.prop('disabled', false).html('<i>&#10094;</i> Resurrect <i>&#10095;</i>');
			Eternity.GenericError.render({msg: response.error});
			return;
		}

		// The fresh saveData must replace ours (a new character exists now),
		// but replacing it wholesale would discard unsaved edits. Existing
		// characters and the party currency are untouched by resurrection,
		// so the UI's current copies — edits included — stay authoritative.
		// Globals are taken fresh: resurrection just cleared death flags in
		// them, and carrying old values over would revert that.
		var previous = self.state.saveData;
		if (previous && previous.characters) {
			var byGuid = {};
			previous.characters.forEach(c => { byGuid[c.GUID] = c; });
			response.characters.forEach(c => {
				if (byGuid[c.GUID] && byGuid[c.GUID].stats && c.stats) {
					c.stats = byGuid[c.GUID].stats;
				}
			});

			if (previous.currency !== undefined) {
				response.currency = previous.currency;
			}
		}

		// The synthetic "dead:" entry is gone from the re-opened save;
		// falling back to the default selection re-picks the main character.
		self.render({
			saveData: response
			, info: self.state.info
			, view: self.state.view
		});

		Eternity.Modifications.transition({modifications: true});
	};

	var failure = (errno, response) => {
		button.prop('disabled', false).html('<i>&#10094;</i> Resurrect <i>&#10095;</i>');
		Eternity.GenericError.render({msg: response});
	};

	window.resurrectCharacter({
		request: JSON.stringify({
			oldSave: self.state.info.absolutePath
			, savedYet: Eternity.Modifications.state.savedYet
			// Synthetic dead-companion GUIDs look like "dead:<registry key>".
			, companion: guid.replace(/^dead:/, '')
		})
		, onSuccess: success
		, onFailure: failure
	});
};

SavedGame.prototype.switchView = function (view) {
	var self = this;
	self.transition({view: view});
};

SavedGame.prototype.update = function (e) {
	var self = this;
	var element = $(e.currentTarget);
	var isCol = element.prop('nodeName') === 'TD';
	var isDropdown = element.prop('nodeName') === 'SELECT';
	var value = isCol ? element.text() : element.val();
	var key = isDropdown ? element.parent().data('fullkey') : element.data('fullkey');

	if (key === null || key.length < 1) {
		return;
	}

	if (key.indexOf('.') < 0) {
		var character =
			self.state.saveData.characters.filter(c => c.GUID === self.state.activeCharacter)[0];
		character.stats[key].value = value;
	} else {
		var ex = key.split('.');
		self.state.saveData.globals[ex[0]][ex[1]][ex[2]].value = value;
	}

	Eternity.Modifications.transition({modifications: true});
};

$.extend(SavedGame.prototype, Renderer.prototype);
