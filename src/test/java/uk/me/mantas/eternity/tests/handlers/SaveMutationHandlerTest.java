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

package uk.me.mantas.eternity.tests.handlers;

import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.handlers.SaveMutationHandler;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * What every handler that edits the working save and hands it back has in
 * common.
 *
 * <p>Seven handlers — inventory, abilities, stronghold, grimoires, party,
 * resurrection, achievements — were each the same fifty lines: hop onto the
 * mutation worker, read {@code oldSave} and {@code savedYet}, work out which
 * copy of the save is the live one, check it exists, do the one line that
 * differs, reopen, and catch the same two exceptions. The one thing that
 * differed was the edit; everything else only stayed consistent because every
 * copy remembered to do it, including invariant 9 — every save mutation on
 * the single mutation worker, so two can never write the same file at once.
 */
public class SaveMutationHandlerTest extends TestHarness {
	private File workingSave () throws Exception {
		final File resources = new File(getClass().getResource("/").toURI());
		final File directory = new File(EKUtils.createTempDir(PREFIX).get(), "working.savegame");
		assertTrue(directory.mkdir());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), directory);
		return directory;
	}

	@After
	public void forgetThePreviousSave () {
		Environment.getInstance().state().previousSaveDirectory(null);
	}

	/** A handler whose edit is whatever the test says it is. */
	private static final class Probe extends SaveMutationHandler {
		final AtomicReference<File> sawSave = new AtomicReference<>();
		final AtomicReference<JSONObject> sawRequest = new AtomicReference<>();
		final AtomicReference<Thread> ranOn = new AtomicReference<>();
		String problem = null;
		IOException throwing = null;

		@Override
		protected String mutate (final File save, final JSONObject request) throws IOException {
			ranOn.set(Thread.currentThread());
			sawSave.set(save);
			sawRequest.set(request);

			if (throwing != null) {
				throw throwing;
			}

			return problem;
		}
	}

	private static JSONObject request (final File oldSave, final boolean savedYet) {
		final JSONObject request = new JSONObject();
		request.put("oldSave", oldSave.getAbsolutePath());
		request.put("savedYet", savedYet);
		request.put("anything", "else");
		return request;
	}

	private static void send (final SaveMutationHandler handler, final Object request
		, final CefQueryCallback callback) {

		handler.onQuery(mock(CefBrowser.class), 0, request.toString(), false, callback);
	}

	@Test
	public void theEditRunsOnTheMutationWorker () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);
		verify(callback, timeout(60000)).success(anyString());

		final AtomicReference<Thread> worker = new AtomicReference<>();
		Environment.getInstance().mutationWorker()
			.submit(() -> worker.set(Thread.currentThread()))
			.get(10, TimeUnit.SECONDS);

		assertSame("invariant 9: the edit must run on the mutation worker"
			, worker.get(), probe.ranOn.get());
	}

	@Test
	public void anUnsavedSessionEditsTheSaveItWasOpenedFrom () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);
		verify(callback, timeout(60000)).success(anyString());

		assertEquals(save.getAbsoluteFile(), probe.sawSave.get().getAbsoluteFile());
		assertEquals("else", probe.sawRequest.get().getString("anything"));
	}

	/**
	 * After the first Save the working copy is the one that was written, not
	 * the one the list opened.
	 */
	@Test
	public void aSavedSessionEditsTheCopyItLastWrote () throws Exception {
		final File opened = workingSave();
		final File written = workingSave();
		Environment.getInstance().state().previousSaveDirectory(written);

		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(opened, true), callback);
		verify(callback, timeout(60000)).success(anyString());

		assertEquals(written.getAbsoluteFile(), probe.sawSave.get().getAbsoluteFile());
	}

	@Test
	public void claimingASaveThatWasNeverWrittenFallsBackToTheOpenedOne () throws Exception {
		final File opened = workingSave();
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(opened, true), callback);
		verify(callback, timeout(60000)).success(anyString());

		assertEquals(opened.getAbsoluteFile(), probe.sawSave.get().getAbsoluteFile());
	}

	@Test
	public void aSaveThatIsNotThereIsNeverEdited () throws Exception {
		final File missing = new File(EKUtils.createTempDir(PREFIX).get(), "gone.savegame");
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(missing, false), callback);

		verify(callback, timeout(60000)).failure(anyInt(), eq("Unable to find your save file."));
		assertNull(probe.ranOn.get());
		verify(callback, never()).success(anyString());
	}

	@Test
	public void aMalformedRequestIsAnErrorNotAnException () {
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, "{ not json", callback);

		verify(callback, timeout(60000)).failure(anyInt(), eq("Error parsing JSON request."));
		assertNull(probe.ranOn.get());
	}

	@Test
	public void aRefusedEditSaysWhyAndDoesNotReopen () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		probe.problem = "The stronghold is not yours yet.";
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		verify(callback, timeout(60000)).failure(anyInt(), eq("The stronghold is not yours yet."));
		verify(callback, never()).success(anyString());
	}

	@Test
	public void aFailedWriteIsReportedWithWhatWentWrong () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		probe.throwing = new IOException("MobileObjects.save is locked");
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		verify(callback, timeout(60000)).failure(anyInt()
			, argThat(message -> message.contains("MobileObjects.save is locked")));
		verify(callback, never()).success(anyString());
	}

	@Test
	public void aSuccessfulEditHandsBackTheReopenedSave () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		verify(callback, timeout(60000)).success(argThat(response ->
			new JSONObject(response).has("characters")));
	}
}
