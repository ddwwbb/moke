package com.termux.terminal;

/** OSC 7 reports a remote shell's working directory without writing into the terminal screen. */
public class CwdReportTest extends TerminalTestCase {

    public void testFileUrlReportsDecodedPathWithBelAndSt() {
        withTerminalSized(10, 3).enterString("\033]7;file://host/srv/my%20project\007");
        assertEquals("/srv/my project", mOutput.lastCwd);
        assertEquals("host", mOutput.lastCwdHost);

        enterString("\033]7;file://host/srv/%E4%B8%AD%E6%96%87+repo\033\\");
        assertEquals("/srv/中文+repo", mOutput.lastCwd);
        enterString("\033]7;file:///tmp/x\033\\");
        assertEquals("/tmp/x", mOutput.lastCwd);
        assertEquals("", mOutput.lastCwdHost);
    }

    public void testMalformedOrNonFileUrlsDoNotReplaceKnownDirectory() {
        withTerminalSized(10, 3).enterString("\033]7;file://host/repo\007");
        enterString("\033]7;https://host/other\007");
        enterString("\033]7;file://host\007");
        enterString("\033]7;file://host/bad%ZZ\007");
        assertEquals("/repo", mOutput.lastCwd);
    }

    public void testCwdReportDoesNotPrintOnScreen() {
        withTerminalSized(10, 3).enterString("\033]7;file://host/repo\007x")
            .assertLinesAre("x         ", "          ", "          ");
    }
}
