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

import net.lingala.zip4j.ZipFile;
import org.apache.commons.io.FileUtils;
import org.joox.Match;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.save.SaveGameRenamer;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

import static org.joox.JOOX.$;
import static org.junit.Assert.*;

public class SaveGameRenamerTest extends TestHarness {
	// Includes Polish characters to make sure the encoding survives.
	private static final String NEW_NAME = "Zapis Wielka Sala ĄĘŁŻÓćń";

	private String userSaveName (final File saveinfo) throws IOException {
		final Match xml = $(new String(
			EKUtils.removeBOM(FileUtils.readFileToByteArray(saveinfo)), "UTF-8"));

		return xml.find("Simple[name='UserSaveName']").attr("value");
	}

	@Test
	public void renamesArchiveAndWorkingCopy ()
		throws URISyntaxException
		, IOException {

		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		// Build an extracted working copy and a .savegame archive from the
		// test fixture.
		final File fixture = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI());
		final File extracted = new File(workingDir.get(), "extracted.savegame");
		FileUtils.copyDirectory(fixture, extracted);

		final File archive = new File(workingDir.get(), "original.savegame");
		final ZipFile zip = new ZipFile(archive);
		zip.addFiles(new ArrayList<>(Arrays.asList(extracted.listFiles())));

		final SaveGameRenamer renamer =
			new SaveGameRenamer(archive.getAbsolutePath(), extracted.getAbsolutePath());
		renamer.rename(NEW_NAME);

		// The working copy is updated...
		assertEquals(NEW_NAME, userSaveName(new File(extracted, "saveinfo.xml")));

		// ...and so is the saveinfo.xml inside the archive.
		final File reExtracted = new File(workingDir.get(), "reextracted");
		new ZipFile(archive).extractAll(reExtracted.getAbsolutePath());
		assertEquals(NEW_NAME, userSaveName(new File(reExtracted, "saveinfo.xml")));

		// The rest of the archive is intact.
		assertTrue(new File(reExtracted, "MobileObjects.save").exists());
	}

	@Test(expected = FileNotFoundException.class)
	public void throwsWhenArchiveMissing () throws IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		new SaveGameRenamer(
			new File(workingDir.get(), "404.savegame").getAbsolutePath()
			, workingDir.get().getAbsolutePath());
	}
}
