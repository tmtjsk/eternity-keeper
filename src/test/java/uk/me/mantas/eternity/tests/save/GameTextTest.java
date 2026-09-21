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


package uk.me.mantas.eternity.tests.save;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.DatabaseString.StringTableType;
import uk.me.mantas.eternity.save.GameText;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import static org.junit.Assert.*;

// The game's own words, read out of the string tables on the player's
// install: data/localized/en/text/game/<table>.stringtable, one XML file per
// DatabaseString.StringTableType. A save stores a hireling's or a prisoner's
// name only as a table and an id into it.
public class GameTextTest extends TestHarness {
	// The shape of the shipped files, BOM and all.
	private static final String CHARACTERS = "﻿<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
		+ "<StringTableFile xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
		+ "  <Name>game\\characters</Name>\n"
		+ "  <EntryCount>3</EntryCount>\n"
		+ "  <Entries>\n"
		+ "    <Entry>\n"
		+ "      <Language>english</Language>\n"
		+ "      <ID>329</ID>\n"
		+ "      <DefaultText>Crucible Knight</DefaultText>\n"
		+ "      <FemaleText />\n"
		+ "      <GenderNeutralText />\n"
		+ "    </Entry>\n"
		+ "    <Entry>\n"
		+ "      <Language>english</Language>\n"
		+ "      <ID>1171</ID>\n"
		+ "      <DefaultText>Kestorik</DefaultText>\n"
		+ "      <FemaleText />\n"
		+ "    </Entry>\n"
		+ "    <Entry>\n"
		+ "      <ID>12</ID>\n"
		+ "      <DefaultText>Hiravias &amp; \"friends\" &lt;3</DefaultText>\n"
		+ "    </Entry>\n"
		+ "  </Entries>\n"
		+ "</StringTableFile>\n";

	private File install () throws IOException {
		final Optional<File> game = EKUtils.createTempDir(PREFIX);
		assertTrue(game.isPresent());

		final File tables = new File(game.get()
			, "PillarsOfEternity_Data/data/localized/en/text/game");
		FileUtils.write(new File(tables, "characters.stringtable"), CHARACTERS, "UTF-8");
		return game.get();
	}

	@After
	public void pinOff () {
		GameText.useNoText();
	}

	@Test
	public void anIdIsLookedUpInItsOwnTable () throws IOException {
		GameText.useTextAt(install());

		assertEquals(Optional.of("Crucible Knight")
			, GameText.getInstance().lookup(StringTableType.Characters, 329));
		assertEquals(Optional.of("Kestorik")
			, GameText.getInstance().lookup(StringTableType.Characters, 1171));
	}

	@Test
	public void entitiesComeBackAsTheCharactersTheyStandFor () throws IOException {
		GameText.useTextAt(install());

		assertEquals(Optional.of("Hiravias & \"friends\" <3")
			, GameText.getInstance().lookup(StringTableType.Characters, 12));
	}

	@Test
	public void anIdTheTableDoesNotHaveIsNothing () throws IOException {
		GameText.useTextAt(install());

		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Characters, 4000));
		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Characters, -1));
	}

	@Test
	public void aTableTheInstallDoesNotHaveIsNothing () throws IOException {
		GameText.useTextAt(install());

		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Stronghold, 79));
	}

	@Test
	public void withNoInstallThereIsNoText () {
		// TestHarness pins this, so a machine with the game installed answers
		// the same as one without.
		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Characters, 329));
	}

	@Test
	public void aBrokenTableIsNothingRatherThanAFailure () throws IOException {
		final File game = install();
		FileUtils.write(new File(game
			, "PillarsOfEternity_Data/data/localized/en/text/game/stronghold.stringtable")
			, "<StringTableFile><Entries><Entry><ID>79</ID>", "UTF-8");

		GameText.useTextAt(game);
		assertEquals(Optional.empty()
			, GameText.getInstance().lookup(StringTableType.Stronghold, 79));
		assertEquals(Optional.of("Kestorik")
			, GameText.getInstance().lookup(StringTableType.Characters, 1171));
	}
}
