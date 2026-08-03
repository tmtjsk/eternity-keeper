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

// The Inventory view, laid out like the game's own inventory screen: the
// selected character's portrait sits between their equipment slots, with quick
// items and weapon sets underneath, every party member's own 16-slot pack on
// the right, and the shared stash below.
//
// Items move the way they do in the game — click once to pick up, click again
// to drop — and double-clicking a stackable opens a quantity panel. Packs and
// the stash are freely editable; equipment, quick items and weapon sets are
// shown but read-only, because dropping an item into a slot it isn't valid for
// (a sword on someone's feet, armour in a quick slot) is exactly the sort of
// thing that corrupts a save.
var InventoryEditor = function () {
	var self = this;

	var defaultState = {
		enabled: false
		, character: false     // GUID of the party member whose doll is shown
		, working: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	// The working copy. containers maps "<characterGuid>|<component>" to
	// {maxItems, items:[...]}; original remembers where every item started so
	// Apply can send the minimal set of changes.
	var containers = {};
	var original = {};
	// Worn gear, kept apart from the lists because it's a fixed 11-slot array:
	// equipment[characterGuid] = [item|null x11]. originalEquipment is the
	// same shape, snapshotted at open time.
	var equipment = {};
	var originalEquipment = {};
	// The four weapon sets, flattened to 8 slots (primary/secondary per set).
	var weapons = {};
	var originalWeapons = {};
	var selected = null;      // {key, slot, itemGuid} of the item "in hand"
	var stackTarget = null;   // item being edited in the quantity panel
	var stashFilter = 0;      // ItemFilterType currently toggled on, 0 = all
	var browseFilter = 0;
	var browseOffset = 0;
	var browseTotal = 0;
	var browseLimit = 60;
	var browseItems = [];
	var browseTimer = null;

	var saveData = () => Eternity.SavedGame.state.saveData || {};
	var inventory = () => saveData().inventory || {};
	var icons = () => inventory().icons || {};

	var STASH = 'StashInventory';
	var EQUIPMENT = 'Equipment';
	var QUICKBAR = 'QuickbarInventory';
	var WEAPONS = 'WeaponSets';

	// Slot order as the game's own inventory screen lays them out: head down
	// the left with the pet at the bottom, neck down the right. The deprecated
	// cape slot is never populated, so it isn't shown at all.
	var LEFT_SLOTS = ['Head', 'Chest', 'LeftRing', 'Feet', 'Pet'];
	var RIGHT_SLOTS = ['Neck', 'Hands', 'RightRing', 'Waist', 'Grimoire'];

	var SLOT_LABELS = {
		Head: 'Head', Neck: 'Neck', Chest: 'Armour', Hands: 'Hands'
		, RightRing: 'Right ring', LeftRing: 'Left ring', Feet: 'Feet'
		, Waist: 'Waist', Grimoire: 'Grimoire', Pet: 'Pet', Cape: 'Cape'
	};

	// Index into EquipmentSetSerialized, and the Equippable flag an item must
	// carry to be allowed there.
	var SLOT_INDEX = {
		Head: 0, Neck: 1, Chest: 2, Hands: 3, RightRing: 4, LeftRing: 5
		, Cape: 6, Feet: 7, Waist: 8, Grimoire: 9, Pet: 10
	};

	var SLOT_FLAG = {
		Head: 'HeadSlot', Neck: 'NeckSlot', Chest: 'ArmorSlot', Hands: 'HandSlot'
		, RightRing: 'RingRightHandSlot', LeftRing: 'RingLeftHandSlot'
		, Feet: 'FeetSlot', Waist: 'WaistSlot', Grimoire: 'GrimoireSlot'
		, Pet: 'PetSlot', Cape: ''
	};

	// The game's own inventory filter categories (UIInventoryFilter).
	var FILTERS = [
		{value: 1, icon: 'fa-crosshairs', label: 'Weapons'}
		, {value: 2, icon: 'fa-shield', label: 'Armour'}
		, {value: 8, icon: 'fa-user', label: 'Clothing'}
		, {value: 16, icon: 'fa-flask', label: 'Consumables'}
		, {value: 32, icon: 'fa-leaf', label: 'Ingredients'}
		, {value: 64, icon: 'fa-star', label: 'Quest items'}
		, {value: 128, icon: 'fa-ellipsis-h', label: 'Miscellaneous'}
	];

	var containerKey = (characterGuid, component) => characterGuid + '|' + component;

	var lookupCharacter = guid => {
		var match = (saveData().characters || []).filter(c => c.GUID === guid);
		return match.length > 0 ? match[0] : null;
	};

	var characterName = guid => {
		var character = lookupCharacter(guid);
		return character ? character.name : 'Unknown';
	};

	var characterPortrait = guid => {
		var character = lookupCharacter(guid);
		return character ? character.portrait : '';
	};

	// ---- working copy -------------------------------------------------------

	var buildWorkingCopy = () => {
		containers = {};
		original = {};
		equipment = {};
		originalEquipment = {};
		selected = null;

		var data = inventory();
		(data.characters || []).forEach(character => {
			var key = containerKey(character.guid, character.packComponent);
			containers[key] = {
				maxItems: character.pack.maxItems
				, items: character.pack.items.map(item => $.extend({}, item))
			};

			containers[key].items.forEach(item => {
				original[item.guid] = {
					character: character.guid
					, component: character.packComponent
					, stackSize: item.stackSize
					, uiSlot: item.uiSlot
				};
			});

			var worn = (character.equipment.slots || []).map(
				slot => slot.item ? $.extend({}, slot.item) : null);

			equipment[character.guid] = worn;
			originalEquipment[character.guid] = worn.map(
				item => item ? item.guid : '');

			worn.forEach((item, index) => {
				if (item) {
					original[item.guid] = {
						character: character.guid
						, component: EQUIPMENT
						, equipmentSlot: index
						, stackSize: 1
						, uiSlot: -1
					};
				}
			});

			// Weapon sets are a flat array of primary/secondary pairs.
			var sets = [];
			(character.equipment.weaponSets || []).forEach(set => {
				sets[set.index * 2] = set.primary ? $.extend({}, set.primary) : null;
				sets[set.index * 2 + 1] = set.secondary ? $.extend({}, set.secondary) : null;
			});

			for (var w = 0; w < 8; w++) {
				if (sets[w] === undefined) {
					sets[w] = null;
				}
			}

			weapons[character.guid] = sets;
			originalWeapons[character.guid] = sets.map(item => item ? item.guid : '');
			sets.forEach((item, index) => {
				if (item) {
					original[item.guid] = {
						character: character.guid
						, component: EQUIPMENT
						, weaponSlot: index
						, stackSize: 1
						, uiSlot: -1
					};
				}
			});

			// The quick bar is a normal inventory, so it can join the
			// container machinery directly.
			var quickKey = containerKey(character.guid, QUICKBAR);
			containers[quickKey] = {
				maxItems: character.quickbar.maxItems || 4
				, items: character.quickbar.items.map(item => $.extend({}, item))
			};

			containers[quickKey].items.forEach(item => {
				original[item.guid] = {
					character: character.guid
					, component: QUICKBAR
					, stackSize: item.stackSize
					, uiSlot: item.uiSlot
				};
			});
		});

		var player = (data.characters || []).filter(c => c.isPlayer)[0];
		if (data.stash && player) {
			var stashKey = containerKey(player.guid, STASH);
			containers[stashKey] = {
				maxItems: data.stash.maxItems
				, items: data.stash.items.map(item => $.extend({}, item))
			};

			containers[stashKey].items.forEach(item => {
				original[item.guid] = {
					character: player.guid
					, component: STASH
					, stackSize: item.stackSize
					, uiSlot: item.uiSlot
				};
			});
		}
	};

	var findItem = guid => {
		for (var key in containers) {
			if (!containers.hasOwnProperty(key)) continue;
			var found = containers[key].items.filter(i => i.guid === guid);
			if (found.length > 0) {
				return {key: key, item: found[0]};
			}
		}

		for (var owner in equipment) {
			if (!equipment.hasOwnProperty(owner)) continue;
			for (var index = 0; index < equipment[owner].length; index++) {
				var worn = equipment[owner][index];
				if (worn && worn.guid === guid) {
					return {
						key: containerKey(owner, EQUIPMENT)
						, item: worn
						, equipmentSlot: index
					};
				}
			}
		}

		for (var wielder in weapons) {
			if (!weapons.hasOwnProperty(wielder)) continue;
			for (var w = 0; w < weapons[wielder].length; w++) {
				var held = weapons[wielder][w];
				if (held && held.guid === guid) {
					return {
						key: containerKey(wielder, WEAPONS)
						, item: held
						, weaponSlot: w
					};
				}
			}
		}

		return null;
	};

	/** Detach an item from wherever it currently sits. */
	var detach = held => {
		if (held.equipmentSlot !== undefined) {
			equipment[parseKey(held.key).character][held.equipmentSlot] = null;
		} else if (held.weaponSlot !== undefined) {
			weapons[parseKey(held.key).character][held.weaponSlot] = null;
		} else {
			containers[held.key].items =
				containers[held.key].items.filter(i => i.guid !== held.item.guid);
		}
	};

	/** Why this item can't go into a weapon set slot, or null when it can. */
	var weaponRejection = (characterGuid, slotIndex, item) => {
		var character = characterInfo(characterGuid);
		if (!character) {
			return 'That character has no equipment.';
		}

		var secondary = slotIndex % 2 === 1;
		var flag = secondary ? 'SecondaryWeaponSlot' : 'PrimaryWeaponSlot';
		var slots = item.slots || [];

		if (slots.indexOf(flag) < 0 && slots.indexOf('BothPrimaryAndSecondarySlot') < 0) {
			return item.displayName + ' cannot go in the '
				+ (secondary ? 'off-hand' : 'main hand') + '.';
		}

		if (item.classes && item.classes.length > 0
			&& item.classes.indexOf(character.characterClass) < 0) {

			return item.displayName + ' can only be used by: ' + item.classes.join(', ') + '.';
		}

		return null;
	};

	var characterInfo = guid =>
		(inventory().characters || []).filter(c => c.guid === guid)[0] || null;

	/**
	 * Party order, matching the list down the left of the editor: the main
	 * character first, then everyone else alphabetically.
	 */
	var sortedCharacters = () => {
		return (inventory().characters || []).slice().sort((a, b) => {
			if (!!a.isPlayer !== !!b.isPlayer) {
				return a.isPlayer ? -1 : 1;
			}

			return characterName(a.guid).localeCompare(characterName(b.guid));
		});
	};

	/**
	 * How many weapon sets this character may actually use. Everyone starts
	 * with two; the rest come from talents, so the game reports
	 * MaxWeaponSets = 2 + BonusWeaponSets and locks the others out.
	 */
	var weaponSetLimit = guid => {
		var character = characterInfo(guid);
		return character && character.maxWeaponSets ? character.maxWeaponSets : 2;
	};

	/**
	 * Whether a character has a given slot at all. Mirrors the game's
	 * Equipment.HasEquipmentSlot: godlike have no head slot (their divine
	 * features occupy it), only wizards get a grimoire, and pets belong to the
	 * player alone. The server works this out from race and class and sends
	 * the list, so the two can't drift apart.
	 */
	var slotAvailable = (characterGuid, slotName) => {
		var character = characterInfo(characterGuid);
		if (!character) {
			return false;
		}

		return (character.unavailableSlots || []).indexOf(slotName) < 0;
	};

	/** Why this item can't go in this slot, or null when it can. */
	var rejection = (characterGuid, slotName, item) => {
		var character = characterInfo(characterGuid);
		if (!character) {
			return 'That character has no equipment.';
		}

		if (!slotAvailable(characterGuid, slotName)) {
			if (slotName === 'Head') {
				return character.name + ' is Godlike — nothing can be worn on their head.';
			}

			if (slotName === 'Grimoire') {
				return 'Only wizards carry a grimoire.';
			}

			if (slotName === 'Pet') {
				return 'Only the main character can have a pet.';
			}

			return 'That slot does not exist for this character.';
		}

		var flag = SLOT_FLAG[slotName];
		if (!item.slots || item.slots.indexOf(flag) < 0) {
			return item.displayName + ' cannot be worn in the '
				+ (SLOT_LABELS[slotName] || slotName).toLowerCase() + ' slot.';
		}

		if (item.classes && item.classes.length > 0
			&& item.classes.indexOf(character.characterClass) < 0) {

			return item.displayName + ' can only be used by: ' + item.classes.join(', ') + '.';
		}

		return null;
	};

	var firstFreeSlot = container => {
		var taken = {};
		container.items.forEach(i => taken[i.uiSlot] = true);

		var slot = 0;
		while (taken[slot]) {
			slot++;
		}

		return slot;
	};

	// ---- rendering ----------------------------------------------------------

	var iconFor = item => {
		if (!item) {
			return null;
		}

		// Items from the catalog browser carry their icon inline; items from
		// the save look theirs up in the shared map sent with it.
		var data = item.icon || (item.key ? icons()[item.key] : null);
		return data ? 'data:image/png;base64,' + data : null;
	};

	var tileFor = (item, options) => {
		options = options || {};
		var tile = $('<div>').addClass('inv-tile');

		if (!item) {
			return tile.addClass('inv-tile-empty');
		}

		var source = iconFor(item);
		if (source) {
			tile.append($('<img>').attr('src', source).addClass('inv-tile-icon'));
		} else {
			// No catalogued icon — fall back to initials so the tile still
			// reads as occupied.
			tile.append($('<span>').addClass('inv-tile-fallback')
				.text((item.displayName || '?').substring(0, 2)));
		}

		if (item.stackSize > 1) {
			tile.append($('<span>').addClass('inv-tile-count').text(item.stackSize));
		}

		if (item.quest) {
			tile.addClass('inv-tile-quest');
		}

		// Soulbound / unique / enchanted gear gets the game's colour coding.
		if (item.quality) {
			tile.addClass('inv-quality-' + item.quality);
		}

		tile.attr('title', (item.displayName || item.baseItem)
			+ (item.stackSize > 1 ? ' ×' + item.stackSize : '')
			+ (options.readOnly ? '' : '\nClick to pick up, double-click for quantity'));

		return tile;
	};

	var renderDoll = () => {
		var guid = self.state.character;
		var character = (inventory().characters || []).filter(c => c.guid === guid)[0];

		self.html.invCharacterName.text(characterName(guid));
		self.html.invPortrait.empty();
		self.html.invSlotsLeft.empty();
		self.html.invSlotsRight.empty();
		self.html.invQuickSlots.empty();
		self.html.invWeaponSets.empty();

		var portrait = characterPortrait(guid);
		if (portrait) {
			self.html.invPortrait.append(
				$('<img>').attr('src', 'data:image/png;base64,' + portrait));
		}

		if (!character) {
			return;
		}

		var worn = equipment[guid] || [];
		var equipmentKey = containerKey(guid, EQUIPMENT);

		var renderSlots = (host, names) => {
			names.forEach(name => {
				var index = SLOT_INDEX[name];
				var item = worn[index] || null;
				var available = slotAvailable(guid, name);
				var tile = tileFor(item);

				if (!available) {
					tile.addClass('inv-tile-blocked')
						.attr('title', rejection(guid, name, {displayName: '', slots: []}));
				} else {
					if (item && selected && selected.itemGuid === item.guid) {
						tile.addClass('inv-tile-selected');
					}

					bindTile(tile, equipmentKey, index, item);
				}

				host.append($('<div>').addClass('inv-equip-slot')
					.append(tile)
					.append($('<span>').addClass('inv-slot-label')
						.text(SLOT_LABELS[name] || name)));
			});
		};

		renderSlots(self.html.invSlotsLeft, LEFT_SLOTS);
		renderSlots(self.html.invSlotsRight, RIGHT_SLOTS);

		var quickKey = containerKey(guid, QUICKBAR);
		var quick = containers[quickKey];
		if (quick) {
			renderGrid(self.html.invQuickSlots, quickKey, quick.maxItems);
		}

		var sets = weapons[guid] || [];
		var weaponsKey = containerKey(guid, WEAPONS);

		var usableSets = weaponSetLimit(guid);

		for (var s = 0; s < 4; s++) {
			var locked = s >= usableSets;
			var row = $('<div>').addClass('inv-weapon-set')
				.append($('<span>').addClass('inv-weapon-index')
					.text(['I', 'II', 'III', 'IV'][s]));

			if (s === character.equipment.selectedSet) {
				row.addClass('inv-weapon-set-active');
			}

			if (locked) {
				row.addClass('inv-weapon-set-locked');
			}

			(function (setIndex, isLocked) {
				[0, 1].forEach(hand => {
					var index = setIndex * 2 + hand;
					var item = sets[index] || null;
					var tile = tileFor(item);

					if (isLocked) {
						tile.addClass('inv-tile-blocked').attr('title',
							characterName(guid) + ' has not unlocked weapon set '
							+ ['I', 'II', 'III', 'IV'][setIndex]
							+ ' — it takes a talent to open the third and fourth.');
					} else {
						if (item && selected && selected.itemGuid === item.guid) {
							tile.addClass('inv-tile-selected');
						}

						bindTile(tile, weaponsKey, index, item);
					}

					row.append(tile);
				});
			})(s, locked);

			self.html.invWeaponSets.append(row);
		}
	};

	// A single click redraws the grid, which would tear the tile out of the
	// DOM before its dblclick ever fired. Hold the click back briefly and drop
	// it if a second one arrives, so both gestures work on the same tile.
	var clickTimer = null;

	var bindTile = (tile, key, slot, item) => {
		tile.on('click', () => {
			if (clickTimer) {
				return;
			}

			clickTimer = window.setTimeout(() => {
				clickTimer = null;
				onTileClick(key, slot, item);
			}, 220);
		});

		tile.on('dblclick', () => {
			if (clickTimer) {
				window.clearTimeout(clickTimer);
				clickTimer = null;
			}

			onTileDoubleClick(item);
		});
	};

	var renderGrid = (host, key, slotCount) => {
		var container = containers[key];
		if (!container) {
			return;
		}

		var bySlot = {};
		container.items.forEach(item => bySlot[item.uiSlot] = item);

		for (var slot = 0; slot < slotCount; slot++) {
			var item = bySlot[slot] || null;
			var tile = tileFor(item);

			if (item && selected && selected.itemGuid === item.guid) {
				tile.addClass('inv-tile-selected');
			}

			bindTile(tile, key, slot, item);
			host.append(tile);
		}
	};

	var renderPacks = () => {
		self.html.invPacks.empty();

		sortedCharacters().forEach(character => {
			var key = containerKey(character.guid, character.packComponent);
			var container = containers[key];
			if (!container) {
				return;
			}

			var row = $('<div>').addClass('inv-pack-row');
			if (character.guid === self.state.character) {
				row.addClass('inv-pack-row-active');
			}

			var head = $('<div>').addClass('inv-pack-owner')
				.append($('<span>').addClass('inv-pack-owner-name')
					.text(characterName(character.guid)))
				.append($('<span>').addClass('inv-count')
					.text(container.items.length + '/' + container.maxItems))
				.on('click', () => Eternity.SavedGame.switchCharacter(character.guid));

			var portrait = characterPortrait(character.guid);
			if (portrait) {
				head.prepend($('<img>').addClass('inv-pack-portrait')
					.attr('src', 'data:image/png;base64,' + portrait));
			}

			var grid = $('<div>').addClass('inv-grid');
			renderGrid(grid, key, container.maxItems);

			self.html.invPacks.append(row.append(head).append(grid));
		});
	};

	var renderStash = () => {
		var player = (inventory().characters || []).filter(c => c.isPlayer)[0];
		self.html.invStashGrid.empty();

		if (!player) {
			return;
		}

		var key = containerKey(player.guid, STASH);
		var container = containers[key];
		if (!container) {
			return;
		}

		self.html.invStashCount.text(container.items.length + ' items');
		renderFilters();

		var filter = (self.html.invStashSearch.val() || '').toLowerCase();
		var sorted = container.items.slice()
			.sort((a, b) => (a.displayName || '').localeCompare(b.displayName || ''));

		// With a category picked, the game groups that type first; we simply
		// show only that type and fall back to everything when it's cleared.
		if (stashFilter) {
			sorted = sorted.filter(item => (item.filter || 0) === stashFilter);
		}

		sorted.forEach(item => {
			if (filter && (item.displayName || '').toLowerCase().indexOf(filter) < 0) {
				return;
			}

			var tile = tileFor(item);
			if (selected && selected.itemGuid === item.guid) {
				tile.addClass('inv-tile-selected');
			}

			bindTile(tile, key, item.uiSlot, item);
			self.html.invStashGrid.append(tile);
		});

		// The stash is effectively unlimited, so instead of drawing a fixed
		// grid we leave one spare tile to drop things onto.
		if (!filter) {
			var empty = $('<div>').addClass('inv-tile inv-tile-empty');
			empty.on('click', () => onTileClick(key, -1, null));
			self.html.invStashGrid.append(empty);
		}
	};

	// The game's category filters: nothing is selected to begin with, clicking
	// an icon narrows the stash to that type, and clicking it again clears it.
	var renderFilters = () => {
		self.html.invStashFilters.empty();

		FILTERS.forEach(category => {
			var button = $('<button>')
				.addClass('inv-filter')
				.attr('type', 'button')
				.attr('title', category.label)
				.append($('<i>').addClass('fa ' + category.icon));

			if (stashFilter === category.value) {
				button.addClass('inv-filter-on');
			}

			button.on('click', () => {
				stashFilter = stashFilter === category.value ? 0 : category.value;
				renderStash();
			});

			self.html.invStashFilters.append(button);
		});
	};

	// What you're holding sits right between the equipment panel and the party
	// packs, where the eye already is while moving things around.
	var renderCarry = () => {
		var bar = self.html.invCarry.empty();

		if (!selected) {
			bar.removeClass('inv-carry-on');
			return;
		}

		var held = findItem(selected.itemGuid);
		if (!held) {
			bar.removeClass('inv-carry-on');
			return;
		}

		var source = iconFor(held.item);
		bar.addClass('inv-carry-on')
			.append($('<span>').addClass('inv-carry-label').text('Carrying'));

		if (source) {
			bar.append($('<img>').addClass('inv-carry-icon').attr('src', source));
		}

		bar.append($('<span>').addClass('inv-carry-name').text(held.item.displayName))
			.append($('<span>').addClass('inv-carry-hint')
				.text('click a slot to place it · Esc to put it back'))
			.append($('<button>').addClass('inv-carry-drop').attr('type', 'button')
				.text('Cancel')
				.on('click', () => {
					selected = null;
					redraw();
				}));
	};

	var renderStatus = message => {
		if (message) {
			self.html.invStatus.text(message).addClass('inv-status-on').show();
			return;
		}

		self.html.invStatus.text('').removeClass('inv-status-on').hide();
	};

	var redraw = message => {
		renderDoll();
		renderCarry();
		renderPacks();
		renderStash();
		renderBrowseTargets();
		renderStatus(message);
		self.html.invApply.prop('disabled', !!self.state.working);
	};

	// ---- the "every item in the game" browser --------------------------------

	var renderBrowseTargets = () => {
		var select = self.html.invBrowseTarget;
		var chosen = select.val();
		select.empty();

		sortedCharacters().forEach(character => {
			select.append($('<option>')
				.val(containerKey(character.guid, character.packComponent))
				.text(characterName(character.guid)));
		});

		var player = (inventory().characters || []).filter(c => c.isPlayer)[0];
		if (player) {
			select.append($('<option>')
				.val(containerKey(player.guid, STASH))
				.text('Stash'));
		}

		if (chosen) {
			select.val(chosen);
		}
	};

	// The browser shows exactly two rows, so a page is however many tiles fit
	// across the grid twice over — which changes with the window size.
	var TILE_SPAN = 46;      // 42px tile + 4px margin
	var BROWSE_ROWS = 2;

	var browsePageSize = () => {
		var width = self.html.invBrowseGrid.width() || 0;
		var columns = Math.max(1, Math.floor(width / TILE_SPAN));
		return columns * BROWSE_ROWS;
	};

	var requestBrowse = () => {
		if (!window.browseItems) {
			return;
		}

		browseLimit = browsePageSize();

		window.browseItems({
			request: JSON.stringify({
				search: self.html.invBrowseSearch.val() || ''
				, filter: browseFilter
				, offset: browseOffset
				, limit: browseLimit
			})
			, onSuccess: response => {
				var page = JSON.parse(response);
				browseItems = page.items || [];
				browseTotal = page.total || 0;
				renderBrowse(page.available);
			}
			, onFailure: () => {
				browseItems = [];
				browseTotal = 0;
				renderBrowse(false);
			}
		});
	};

	var scheduleBrowse = () => {
		window.clearTimeout(browseTimer);
		browseTimer = window.setTimeout(() => {
			browseOffset = 0;
			requestBrowse();
		}, 250);
	};

	var renderBrowse = available => {
		var grid = self.html.invBrowseGrid.empty();

		if (!available) {
			self.html.invBrowseCount.text('no item catalog installed');
			self.html.invBrowsePage.text('');
			return;
		}

		self.html.invBrowseCount.text(browseTotal + ' items');
		self.html.invBrowsePage.text(browseTotal < 1
			? ''
			: (browseOffset + 1) + '–' + Math.min(browseOffset + browseLimit, browseTotal));

		self.html.invBrowsePrev.prop('disabled', browseOffset <= 0);
		self.html.invBrowseNext.prop('disabled', browseOffset + browseLimit >= browseTotal);

		browseItems.forEach(item => {
			var tile = tileFor(item, {readOnly: true});
			tile.attr('title', item.displayName + '\nClick to add a copy');
			tile.on('click', () => addFromCatalog(item));
			grid.append(tile);
		});

		renderBrowseFilters();
	};

	var renderBrowseFilters = () => {
		var host = self.html.invBrowseFilters.empty();

		FILTERS.forEach(category => {
			var button = $('<button>')
				.addClass('inv-filter')
				.attr('type', 'button')
				.attr('title', category.label)
				.append($('<i>').addClass('fa ' + category.icon));

			if (browseFilter === category.value) {
				button.addClass('inv-filter-on');
			}

			button.on('click', () => {
				browseFilter = browseFilter === category.value ? 0 : category.value;
				browseOffset = 0;
				requestBrowse();
			});

			host.append(button);
		});
	};

	// Adding is staged like everything else; nothing touches the save until
	// Apply. A placeholder GUID is minted here and the server creates the
	// item's real object when the change is applied.
	var addFromCatalog = item => {
		var target = self.html.invBrowseTarget.val();
		if (!target) {
			redraw('Pick who should receive the item first.');
			return;
		}

		var container = containers[target];
		if (!container) {
			redraw('That container is not available.');
			return;
		}

		if (container.items.length >= container.maxItems) {
			redraw(characterName(parseKey(target).character) + "'s pack is full.");
			return;
		}

		var copy = $.extend({}, item);
		copy.guid = newGuid();
		copy.stackSize = 1;
		copy.uiSlot = firstFreeSlot(container);
		copy.isNew = true;

		container.items.push(copy);
		redraw('Added ' + item.displayName + ' — Apply changes to write it to the save.');
	};

	// Plain RFC-4122 v4; the save only needs it to be unique.
	var newGuid = () =>
		'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
			var r = Math.random() * 16 | 0;
			return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
		});

	// ---- interaction --------------------------------------------------------

	var onTileClick = (key, slot, item) => {
		if (!selected) {
			if (item) {
				selected = {key: key, itemGuid: item.guid};
				redraw();
			}

			return;
		}

		// Clicking the held item again puts it back down.
		if (item && item.guid === selected.itemGuid) {
			selected = null;
			redraw();
			return;
		}

		var held = findItem(selected.itemGuid);
		if (!held) {
			selected = null;
			redraw();
			return;
		}

		var target = parseKey(key);
		if (target.component === EQUIPMENT) {
			dropOnEquipment(held, target.character, slot);
			return;
		}

		if (target.component === WEAPONS) {
			dropOnWeaponSet(held, target.character, slot);
			return;
		}

		// Coming off a character rather than out of a pack.
		if (held.equipmentSlot !== undefined || held.weaponSlot !== undefined) {
			var pack = containers[key];
			if (pack.items.length >= pack.maxItems) {
				redraw('That container is full — free a slot first.');
				return;
			}

			detach(held);
			held.item.stackSize = 1;
			pack.items.push(held.item);
			held.item.uiSlot = slotOrFirstFree(pack, held.item, slot);

			selected = null;
			redraw();
			return;
		}

		var source = containers[held.key];
		var destination = containers[key];

		if (held.key !== key) {
			if (destination.items.length >= destination.maxItems) {
				redraw('That pack is full — free a slot first.');
				return;
			}

			source.items = source.items.filter(i => i.guid !== held.item.guid);
			destination.items.push(held.item);
		}

		held.item.uiSlot = slotOrFirstFree(destination, held.item, slot);

		selected = null;
		redraw();
	};

	var parseKey = key => {
		var parts = key.split('|');
		return {character: parts[0], component: parts[1]};
	};

	// Land on the clicked tile when it's free, otherwise take the lowest free
	// one, mirroring the game's own placement.
	var slotOrFirstFree = (container, item, slot) => {
		var occupied = container.items
			.filter(i => i.guid !== item.guid && i.uiSlot === slot).length > 0;

		return (slot >= 0 && !occupied) ? slot : firstFreeSlot(container);
	};

	/** Put the held item on a character, swapping out whatever is there. */
	var dropOnEquipment = (held, characterGuid, slotIndex) => {
		var slotName = Object.keys(SLOT_INDEX)
			.filter(name => SLOT_INDEX[name] === slotIndex)[0];

		var why = rejection(characterGuid, slotName, held.item);
		if (why) {
			redraw(why);
			return;
		}

		swapInto(equipment[characterGuid], slotIndex, held);
	};

	/** Same idea for the four weapon sets. */
	var dropOnWeaponSet = (held, characterGuid, slotIndex) => {
		var why = weaponRejection(characterGuid, slotIndex, held.item);
		if (why) {
			redraw(why);
			return;
		}

		swapInto(weapons[characterGuid], slotIndex, held);
	};

	// Put the held item into slot N of a fixed-size array, sending whatever was
	// there back to wherever the held item came from.
	var swapInto = (slots, slotIndex, held) => {
		var displaced = slots[slotIndex];
		var origin = held.key;
		var originEquipment = held.equipmentSlot;
		var originWeapon = held.weaponSlot;

		detach(held);
		slots[slotIndex] = held.item;
		held.item.stackSize = 1;

		if (displaced) {
			if (originEquipment !== undefined) {
				equipment[parseKey(origin).character][originEquipment] = displaced;
			} else if (originWeapon !== undefined) {
				weapons[parseKey(origin).character][originWeapon] = displaced;
			} else {
				var pack = containers[origin];
				pack.items.push(displaced);
				displaced.uiSlot = firstFreeSlot(pack);
			}
		}

		selected = null;
		redraw();
	};

	var onTileDoubleClick = item => {
		if (!item) {
			return;
		}

		// Worn gear is always a single item and deleting it from here would
		// leave the slot pointing at nothing.
		var located = findItem(item.guid);
		if (located && located.equipmentSlot !== undefined) {
			selected = null;
			redraw('Take ' + item.displayName + ' off first to change it.');
			return;
		}

		// Only stackables get a quantity panel; for everything else the count
		// is always one and there is nothing to choose.
		if (!item.maxStack || item.maxStack <= 1) {
			selected = null;
			redraw(item.displayName + ' doesn’t stack.');
			return;
		}

		selected = null;
		stackTarget = item;
		self.html.stackDialogTitle.text(item.displayName);
		self.html.stackAmount.val(item.stackSize).attr('max', item.maxStack);
		self.html.stackHint.text(
			'1 to ' + item.maxStack + ' — set 0 to remove the item entirely.');

		self.html.stackIcon.empty();
		var source = iconFor(item);
		if (source) {
			self.html.stackIcon.append($('<img>').attr('src', source));
		}

		self.html.stackDialog.modal('show');
	};

	var applyStack = () => {
		if (!stackTarget) {
			return;
		}

		var amount = parseInt(self.html.stackAmount.val(), 10);
		if (isNaN(amount) || amount < 0) {
			return;
		}

		amount = Math.min(amount, stackTarget.maxStack || 1);

		if (amount === 0) {
			var located = findItem(stackTarget.guid);
			if (located) {
				containers[located.key].items =
					containers[located.key].items.filter(i => i.guid !== stackTarget.guid);
			}
		} else {
			stackTarget.stackSize = amount;
		}

		stackTarget = null;
		self.html.stackDialog.modal('hide');
		redraw();
	};

	// ---- persistence --------------------------------------------------------

	self.buildChanges = () => {
		var unequips = [];
		var moves = [];
		var equips = [];
		var stillPresent = {};

		// Everything that came off a character has to be taken off before
		// anything else is put on, or the slot would still look occupied.
		for (var owner in originalEquipment) {
			if (!originalEquipment.hasOwnProperty(owner)) continue;
			originalEquipment[owner].forEach((guid, index) => {
				if (!guid) {
					return;
				}

				var current = equipment[owner][index];
				if (current && current.guid === guid) {
					return;
				}

				var now = findItem(guid);
				stillPresent[guid] = !!now;

				// Where it ended up. An item that moved straight to another
				// slot is parked in its owner's pack on the way through.
				var destCharacter = owner;
				var destComponent = packComponentOf(owner);
				var destSlot = -1;

				if (now && now.equipmentSlot === undefined) {
					var where = parseKey(now.key);
					destCharacter = where.character;
					destComponent = where.component;
					destSlot = now.item.uiSlot;
				}

				unequips.push({
					character: owner
					, component: EQUIPMENT
					, itemGuid: guid
					, stackSize: 1
					, destCharacter: destCharacter
					, destComponent: destComponent
					, destSlot: destSlot
					, fromEquipmentSlot: index
				});
			});
		}

		for (var key in containers) {
			if (!containers.hasOwnProperty(key)) continue;
			var location = parseKey(key);

			containers[key].items.forEach(item => {
				stillPresent[item.guid] = true;

				// Brand new items are created rather than moved.
				if (item.isNew) {
					moves.push({
						character: location.character
						, component: location.component
						, itemGuid: item.guid
						, stackSize: item.stackSize
						, destCharacter: location.character
						, destComponent: location.component
						, destSlot: item.uiSlot
						, newItemPrefab: prefabNameOf(item)
						, newItemPath: item.baseItem
					});

					return;
				}

				var was = original[item.guid];
				if (!was || was.component === EQUIPMENT) {
					// Unequipping already delivered it to this pack.
					return;
				}

				var moved = was.character !== location.character
					|| was.component !== location.component;

				if (!moved && was.stackSize === item.stackSize && was.uiSlot === item.uiSlot) {
					return;
				}

				moves.push({
					character: was.character
					, component: was.component
					, itemGuid: item.guid
					, stackSize: item.stackSize
					, destCharacter: location.character
					, destComponent: location.component
					, destSlot: item.uiSlot
				});
			});
		}

		// Weapon sets follow the same take-off-then-put-on ordering.
		for (var wielder in originalWeapons) {
			if (!originalWeapons.hasOwnProperty(wielder)) continue;
			originalWeapons[wielder].forEach((guid, index) => {
				if (!guid) {
					return;
				}

				var current = weapons[wielder][index];
				if (current && current.guid === guid) {
					return;
				}

				var now = findItem(guid);
				stillPresent[guid] = !!now;

				var destCharacter = wielder;
				var destComponent = packComponentOf(wielder);
				var destSlot = -1;

				if (now && now.equipmentSlot === undefined && now.weaponSlot === undefined) {
					var where = parseKey(now.key);
					destCharacter = where.character;
					destComponent = where.component;
					destSlot = now.item.uiSlot;
				}

				unequips.push({
					character: wielder
					, component: EQUIPMENT
					, itemGuid: guid
					, stackSize: 1
					, destCharacter: destCharacter
					, destComponent: destComponent
					, destSlot: destSlot
					, fromEquipmentSlot: index
					, weaponSet: true
				});
			});
		}

		for (var holder in weapons) {
			if (!weapons.hasOwnProperty(holder)) continue;
			weapons[holder].forEach((item, index) => {
				if (!item) {
					return;
				}

				stillPresent[item.guid] = true;
				if (originalWeapons[holder][index] === item.guid) {
					return;
				}

				var was = original[item.guid];
				equips.push({
					character: was && was.component !== EQUIPMENT ? was.character : holder
					, component: was && was.component !== EQUIPMENT
						? was.component : packComponentOf(holder)
					, itemGuid: item.guid
					, stackSize: 1
					, destCharacter: holder
					, destComponent: EQUIPMENT
					, destSlot: -1
					, toEquipmentSlot: index
					, weaponSet: true
				});
			});
		}

		// Now everything that ended up worn.
		for (var wearer in equipment) {
			if (!equipment.hasOwnProperty(wearer)) continue;
			equipment[wearer].forEach((item, index) => {
				if (!item) {
					return;
				}

				stillPresent[item.guid] = true;
				if (originalEquipment[wearer][index] === item.guid) {
					return;
				}

				var was = original[item.guid];
				equips.push({
					character: was && was.component !== EQUIPMENT ? was.character : wearer
					, component: was && was.component !== EQUIPMENT
						? was.component : packComponentOf(wearer)
					, itemGuid: item.guid
					, stackSize: 1
					, destCharacter: wearer
					, destComponent: EQUIPMENT
					, destSlot: -1
					, toEquipmentSlot: index
				});
			});
		}

		// Anything that started somewhere and is now nowhere was deleted.
		var removals = [];
		for (var guid in original) {
			if (!original.hasOwnProperty(guid) || stillPresent[guid]) continue;
			removals.push({
				character: original[guid].character
				, component: original[guid].component
				, itemGuid: guid
				, stackSize: 0
				, destCharacter: original[guid].character
				, destComponent: original[guid].component
				, destSlot: -1
			});
		}

		return unequips.concat(moves).concat(equips).concat(removals);
	};

	var packComponentOf = characterGuid => {
		var character = characterInfo(characterGuid);
		return character ? character.packComponent : 'Inventory';
	};

	// "assets/.../ring_hag.prefab" -> "ring_hag"; the packet's ObjectName.
	var prefabNameOf = item => {
		var path = item.baseItem || '';
		var file = path.substring(path.lastIndexOf('/') + 1);
		return file.replace(/\.prefab$/i, '') || item.key;
	};

	self.reset = () => {
		containers = {};
		original = {};
		equipment = {};
		originalEquipment = {};
		weapons = {};
		originalWeapons = {};
		selected = null;
	};

	self.setStatus = message => self.html.invStatus.text(message).show();

	self.init = () => {
		self.html.invApply.click(() => self.apply());
		self.html.invRevert.click(() => {
			buildWorkingCopy();
			redraw('Reverted to the save’s current contents.');
		});

		self.html.stackAccept.click(applyStack);
		self.html.stackCancel.click(() => stackTarget = null);
		self.html.invStashSearch.keyup(() => renderStash());
		self.html.invBrowseSearch.keyup(scheduleBrowse);
		self.html.invBrowsePrev.click(() => {
			browseOffset = Math.max(0, browseOffset - browseLimit);
			requestBrowse();
		});
		self.html.invBrowseNext.click(() => {
			browseOffset += browseLimit;
			requestBrowse();
		});

		// A resize changes how many tiles fit, so the page has to be refilled
		// to keep it exactly two rows.
		$(window).resize(() => {
			window.clearTimeout(browseTimer);
			browseTimer = window.setTimeout(() => {
				if (self.html.inventoryView.is(':visible')
					&& browsePageSize() !== browseLimit) {

					browseOffset = 0;
					requestBrowse();
				}
			}, 200);
		});

		// Escape drops whatever is in hand, like right-clicking in the game.
		$(document).keyup(event => {
			if (event.which === 27 && selected) {
				selected = null;
				redraw();
			}
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);

		if (!self.state.enabled) {
			// Going back to the save list means the next save opened must not
			// inherit this one's staged edits.
			self.reset();
			return;
		}

		// Only rebuild when we have nothing staged, so switching characters
		// doesn't quietly discard edits that haven't been applied yet.
		if (Object.keys(containers).length < 1) {
			buildWorkingCopy();
		}

		// Every save-view render transitions us, but drawing a few hundred
		// tiles is only worth doing when the view is actually on screen.
		if (!self.html.inventoryView.is(':visible')) {
			return;
		}

		if (!self.state.character) {
			var player = (inventory().characters || []).filter(c => c.isPlayer)[0];
			if (player) {
				self.state.character = player.guid;
			}
		}

		redraw();

		// The catalog doesn't depend on the save, so it's only fetched once.
		if (browseTotal < 1 && browseItems.length < 1) {
			requestBrowse();
		}
	};
};

InventoryEditor.prototype.apply = function () {
	var self = this;
	var changes = self.buildChanges();

	if (changes.length < 1) {
		self.setStatus('No inventory changes to apply.');
		return;
	}

	self.transition({working: true, character: self.state.character});
	self.setStatus('Applying ' + changes.length + ' change(s)…');

	window.updateInventory({
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
				, view: Eternity.SavedGame.views.INVENTORY
			});
		}
		, onFailure: (code, message) => {
			self.transition({working: false, character: self.state.character});
			self.setStatus('Inventory update failed: ' + message);
		}
	});
};

$.extend(InventoryEditor.prototype, Renderer.prototype);
