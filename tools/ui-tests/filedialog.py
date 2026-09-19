# Answers a native Windows file dialog the editor opened: finds it by title,
# writes a path into its file-name box and presses OK. No focus or keystrokes
# involved, so the game or anything else in front does not get in the way.
import ctypes, time
from ctypes import wintypes

user32 = ctypes.WinDLL("user32", use_last_error=True)
WM_SETTEXT, WM_COMMAND, IDOK = 0x000C, 0x0111, 1
EnumProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
user32.FindWindowW.restype = wintypes.HWND
user32.SendMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPVOID]


def _class(hwnd):
    buf = ctypes.create_unicode_buffer(64)
    user32.GetClassNameW(hwnd, buf, 64)
    return buf.value


def _visible_edits(dialog):
    found = []

    def visit(hwnd, _):
        if _class(hwnd) == "Edit" and user32.IsWindowVisible(hwnd):
            found.append(hwnd)
        return True

    user32.EnumChildWindows(dialog, EnumProc(visit), 0)
    return found


def answer(title, path, seconds=60):
    deadline = time.time() + seconds
    dialog = None
    while time.time() < deadline:
        dialog = user32.FindWindowW("#32770", title)
        if dialog:
            break
        time.sleep(0.5)
    if not dialog:
        raise TimeoutError("no dialog titled %r" % title)

    time.sleep(1.0)
    edits = _visible_edits(dialog)
    if not edits:
        raise RuntimeError("dialog %r has no file-name box" % title)

    # The file-name box is the last visible Edit in both the Open and the
    # Save dialog (the Save dialog's search box comes first).
    user32.SendMessageW(edits[-1], WM_SETTEXT, 0, ctypes.c_wchar_p(path))
    time.sleep(0.3)
    user32.SendMessageW(dialog, WM_COMMAND, IDOK, None)
    return True
