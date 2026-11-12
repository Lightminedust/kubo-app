package com.kubo.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class UpdateInstallerTest {
    @TempDir Path temporary;
    BuildInfo build(PackageKind kind) throws IOException {
        Path app = temporary.resolve("Kubo's space $ folder");
        Files.createDirectories(app.resolve("app"));
        Files.writeString(app.resolve("app/Kubo.cfg"),"current config");
        Path launcher = Files.writeString(app.resolve("Kubo.exe"),"old executable");
        return new BuildInfo(new Version(1,0,0),kind,"x86_64",launcher);
    }
    @Test void stagesPortableReplacementWithoutChangingTheRunningApp() throws Exception {
        BuildInfo build = build(PackageKind.ZIP);
        Path downloaded = PortableArchiveTest.zip(temporary.resolve("Kubo-windows-portable.zip"),PortableArchiveTest.files(true));
        var asset = DownloadTest.asset(Files.readAllBytes(downloaded),downloaded.getFileName().toString());
        var prepared = UpdateInstaller.prepare(build,asset,downloaded);
        assertEquals("old executable",Files.readString(build.launcher()));
        assertEquals("new executable",Files.readString(prepared.staging().resolve("Kubo/Kubo.exe")));
        assertTrue(prepared.command().contains(build.launcher().toString()));
        assertTrue(prepared.command().contains("Hidden"));
        prepared.discard();
        assertFalse(Files.exists(prepared.staging()));
        assertTrue(Files.exists(build.launcher()));
    }
    @Test void preparesEachWindowsInstallerAsTheSameKind() throws Exception {
        for(PackageKind kind : List.of(PackageKind.EXE,PackageKind.MSI)) {
            BuildInfo build = build(kind);
            Path file = Files.writeString(temporary.resolve(kind == PackageKind.EXE ? "Kubo-Setup.exe" : "Kubo-1.10.0.msi"),"installer");
            var prepared = UpdateInstaller.prepare(build,DownloadTest.asset(Files.readAllBytes(file),file.getFileName().toString()),file);
            assertTrue(prepared.command().contains(kind.id()));
            assertTrue(prepared.command().contains(file.toString()));
            assertNull(prepared.staging());
        }
    }
    @Test void rejectsUnpackagedBuildsAndUnsafeCleanup() throws Exception {
        Path file = Files.writeString(temporary.resolve("Kubo-Setup.exe"),"content");
        var asset = DownloadTest.asset(Files.readAllBytes(file),"Kubo-Setup.exe");
        assertThrows(IOException.class, () -> UpdateInstaller.prepare(new BuildInfo(new Version(1,0,0),PackageKind.UNKNOWN,"x86_64",null),asset,file));
        assertThrows(IOException.class, () -> UpdateInstaller.deleteStaging(temporary));
        assertTrue(Files.exists(file));
    }
    @Test void rechecksTheInstallerAfterTheReadyScreen() throws Exception {
        BuildInfo build = build(PackageKind.MSI);
        Path file = Files.writeString(temporary.resolve("Kubo-1.10.0.msi"),"verified installer");
        var prepared = UpdateInstaller.prepare(build,DownloadTest.asset(Files.readAllBytes(file),file.getFileName().toString()),file);
        Files.writeString(file,"modified installer");
        assertThrows(IOException.class,prepared::start);
        assertFalse(Files.exists(prepared.ready()));
        assertEquals("old executable",Files.readString(build.launcher()));
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void windowsHelperReplacesOnlyTheFixtureAndKeepsItsBackup() throws Exception {
        BuildInfo build = build(PackageKind.ZIP);
        Path file = PortableArchiveTest.zip(temporary.resolve("Kubo-windows-portable.zip"),PortableArchiveTest.files(true));
        var prepared = UpdateInstaller.prepare(build,DownloadTest.asset(Files.readAllBytes(file),file.getFileName().toString()),file);
        List<String> command = helperCommand(prepared, "2147483647");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(temporary.resolve("helper.log").toFile()).start();
        assertTrue(process.waitFor(15,TimeUnit.SECONDS));
        assertEquals(0,process.exitValue(),Files.readString(temporary.resolve("helper.log")));
        assertEquals("new executable",Files.readString(build.launcher()));
        Path backup = Path.of(command.get(command.indexOf("portable-zip") + 4));
        assertEquals(build.launcher().toRealPath().getParent().getParent(), backup.getParent());
        assertEquals("old executable",Files.readString(backup.resolve("Kubo.exe")));
    }
    @Test @EnabledOnOs(OS.WINDOWS)
    void windowsHelperRollsBackIfTheStagedFolderDisappears() throws Exception {
        BuildInfo build = build(PackageKind.ZIP);
        Path file = PortableArchiveTest.zip(temporary.resolve("Kubo-windows-portable.zip"),PortableArchiveTest.files(true));
        var prepared = UpdateInstaller.prepare(build,DownloadTest.asset(Files.readAllBytes(file),file.getFileName().toString()),file);
        Process sleeper = new ProcessBuilder("powershell.exe","-NoProfile","-WindowStyle","Hidden","-Command","Start-Sleep -Seconds 30").start();
        Process helper = null;
        try {
            helper = new ProcessBuilder(helperCommand(prepared,Long.toString(sleeper.pid())))
                    .redirectErrorStream(true).redirectOutput(temporary.resolve("helper.log").toFile()).start();
            for(int i=0;i<80 && !Files.exists(prepared.ready());i++) Thread.sleep(100);
            assertTrue(Files.exists(prepared.ready()),Files.readString(temporary.resolve("helper.log")));
            Files.move(prepared.staging().resolve("Kubo"), prepared.staging().resolve("unavailable"));
            sleeper.destroyForcibly(); sleeper.waitFor();
            assertTrue(helper.waitFor(15,TimeUnit.SECONDS));
            assertNotEquals(0,helper.exitValue());
            assertEquals("old executable",Files.readString(build.launcher()));
        } finally { sleeper.destroyForcibly(); if(helper != null && helper.isAlive()) helper.destroyForcibly(); }
    }
    private List<String> helperCommand(UpdateInstaller.Prepared prepared, String previousPid) {
        var command = new ArrayList<>(prepared.command());
        int offset = command.indexOf("portable-zip");
        command.set(offset + 1,previousPid);
        // Fixtures are text, not executables: never launch either the old or the new application.
        command.set(offset + 5,temporary.resolve("do-not-launch.exe").toString());
        command.set(offset + 8,temporary.resolve("result.txt").toString());
        return command;
    }
}
