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

import net.lingala.zip4j.ZipFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Changes a save's user-visible name (the UserSaveName inside saveinfo.xml)
 * both in the extracted working copy and inside the original .savegame
 * archive on disk. The archive file itself keeps its name; the game reads
 * the display name from saveinfo.xml.
 */
public class SaveGameRenamer {
	private final File saveArchive;
	private final File extractedDirectory;

	public SaveGameRenamer (final String saveArchivePath, final String extractedPath)
		throws FileNotFoundException {

		saveArchive = new File(saveArchivePath);
		extractedDirectory = new File(extractedPath);

		if (!saveArchive.isFile()) {
			throw new FileNotFoundException(saveArchivePath);
		}

		if (!extractedDirectory.isDirectory()) {
			throw new FileNotFoundException(extractedPath);
		}
	}

	public void rename (final String newUserSaveName) throws IOException {
		SaveGameInfo.updateSaveInfo(extractedDirectory, newUserSaveName);

		final File saveinfo = new File(extractedDirectory, "saveinfo.xml");
		final ZipFile archive = new ZipFile(saveArchive);
		archive.removeFile("saveinfo.xml");
		archive.addFile(saveinfo);
	}
}
