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


package uk.me.mantas.eternity.save;

import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.game.DatabaseString.StringTableType;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The game's own words, out of the string tables on the player's install.
 *
 * <p>A save often names something only as a table and an id: a hireling is
 * {@code SerializedNameId}, the {@code StringID} of its prefab's
 * {@code DisplayName} in the characters table, and a prisoner is a
 * {@code CharacterDatabaseString}. The words live in
 * {@code data/localized/en/text/game/<table>.stringtable} under the game's
 * data folder — one XML file per {@link StringTableType}, named after the
 * constant in lower case — and are read straight off the disk the way
 * portraits are, rather than copied into the extracted game data.
 *
 * <p>English, like every other name the editor shows: the item and ability
 * catalogs are extracted from the English tables too.
 *
 * <p>A table is read the first time something asks for it and kept. With no
 * install, no such table, or one that will not parse, a lookup is simply
 * empty, and the caller says something sensible instead.
 */
public class GameText {
	private static final Logger logger = Logger.getLogger(GameText.class);
	private static final String TABLES = "data/localized/en/text/game";

	private static GameText instance = null;

	private final File directory;
	private final Map<StringTableType, Map<Integer, String>> tables = new HashMap<>();

	public static synchronized GameText getInstance () {
		if (instance == null) {
			instance = new GameText(gameDirectory());
		}

		return instance;
	}

	/** Testing seam — forces the next getInstance() to look again. */
	public static synchronized void reset () {
		instance = null;
	}

	/** Testing seam — pretends the game is not installed. */
	public static synchronized void useNoText () {
		instance = new GameText(Optional.empty());
	}

	/** Testing seam — reads from a game directory of the caller's choosing. */
	public static synchronized void useTextAt (final File gameDirectory) {
		instance = new GameText(Optional.of(gameDirectory));
	}

	/**
	 * The install, out of settings. Mocked away in tests, so anything thrown
	 * here means "no install" rather than a failure.
	 */
	private static Optional<File> gameDirectory () {
		try {
			final JSONObject settings = Settings.getInstance().json;
			final String location = settings.getString("gameLocation");
			return location == null || location.isEmpty()
				? Optional.empty() : Optional.of(new File(location));
		} catch (final JSONException | NullPointerException e) {
			return Optional.empty();
		}
	}

	private GameText (final Optional<File> gameDirectory) {
		directory = gameDirectory
			.map(game -> new File(new File(game
				, Environment.getInstance().config().pillarsDataDirectory()), TABLES))
			.orElse(null);
	}

	public synchronized Optional<String> lookup (final StringTableType table, final int id) {
		if (table == null || id < 0) {
			return Optional.empty();
		}

		return Optional.ofNullable(tables.computeIfAbsent(table, this::read).get(id));
	}

	private Map<Integer, String> read (final StringTableType table) {
		if (directory == null) {
			return Collections.emptyMap();
		}

		final File file = new File(
			directory, table.name().toLowerCase(Locale.ROOT) + ".stringtable");

		if (!file.isFile()) {
			return Collections.emptyMap();
		}

		final Map<Integer, String> entries = new HashMap<>();
		try (final InputStream in = new BufferedInputStream(new FileInputStream(file))) {
			parse(in, entries);
		} catch (final IOException | XMLStreamException | NumberFormatException e) {
			logger.error(
				"Unable to read '%s': %s%n", file.getAbsolutePath(), e.getMessage());

			// Whatever came before the fault is still the game's own text,
			// but a half-read table would answer some ids and not others for
			// no reason anyone could see, so it is all or nothing.
			return Collections.emptyMap();
		}

		return entries;
	}

	// <Entry><ID>329</ID><DefaultText>Crucible Knight</DefaultText>…</Entry>,
	// with FemaleText and GenderNeutralText beside it, usually empty.
	private static void parse (final InputStream in, final Map<Integer, String> entries)
		throws XMLStreamException {

		final XMLInputFactory factory = XMLInputFactory.newInstance();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

		final XMLStreamReader reader = factory.createXMLStreamReader(in);
		try {
			Integer id = null;
			String text = null;

			while (reader.hasNext()) {
				final int event = reader.next();
				if (event == XMLStreamConstants.START_ELEMENT) {
					switch (reader.getLocalName()) {
						case "Entry":
							id = null;
							text = null;
							break;

						case "ID":
							id = Integer.valueOf(reader.getElementText().trim());
							break;

						case "DefaultText":
							text = reader.getElementText().trim();
							break;

						default:
							break;
					}
				} else if (event == XMLStreamConstants.END_ELEMENT
					&& "Entry".equals(reader.getLocalName())
					&& id != null && text != null) {

					entries.put(id, text);
				}
			}
		} finally {
			reader.close();
		}
	}
}
