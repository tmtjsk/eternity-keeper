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

package uk.me.mantas.eternity.tests.save;

import net.lingala.zip4j.ZipFile;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.save.ChangesSaver;
import uk.me.mantas.eternity.save.SaveBackups;
import uk.me.mantas.eternity.save.SaveBackups.Backup;
import uk.me.mantas.eternity.save.SaveBackups.Reason;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Save writes a new file into the saves folder, and if one of that name is
 * already there it is deleted to make room. That file is the player's — an
 * earlier edit, or a save that happens to share the name — so it is copied
 * into the backups first.
 */
public class PackageSaveGameTest extends TestHarness {
	private File saves;
	private File written;

	@Before
	public void folders () throws Exception {
		saves = EKUtils.createTempDir(PREFIX).get();
		Settings.getInstance().json.put("savesLocation", saves.getAbsolutePath());

		// What Save has assembled in its working folder, ready to be zipped.
		written = new File(EKUtils.createTempDir(PREFIX).get(), "abc 0 Encampment.savegame");
		assertTrue(written.mkdirs());
		FileUtils.writeStringToFile(
			new File(written, "MobileObjects.save"), "the new edit", StandardCharsets.UTF_8);
	}

	private void packageIt () {
		expose(ChangesSaver.class).call("packageSaveGame", written);
	}

	@Test
	public void aSaveItReplacesIsBackedUpFirst () throws Exception {
		final File existing = new File(saves, written.getName());
		FileUtils.writeStringToFile(existing, "an earlier edit", StandardCharsets.UTF_8);

		packageIt();

		final List<Backup> backups = SaveBackups.forThisProcess().list();
		assertEquals(1, backups.size());
		assertEquals(Reason.OVERWRITE, backups.get(0).reason);
		assertEquals("an earlier edit"
			, FileUtils.readFileToString(backups.get(0).file, StandardCharsets.UTF_8));
		assertTrue("the new save is written", new ZipFile(existing).isValidZipFile());
	}

	@Test
	public void aNewNameNeedsNoBackup () {
		packageIt();

		assertTrue(new File(saves, written.getName()).isFile());
		assertTrue(SaveBackups.forThisProcess().list().isEmpty());
	}
}
