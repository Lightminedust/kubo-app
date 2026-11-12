package com.kubo.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class UpdateSettings {
    private final Path file;
    public UpdateSettings(Path file) { this.file = file; }
    public boolean automatic() {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        catch (IOException | IllegalArgumentException ignored) { return true; }
        return !properties.getProperty("checkAtStartup", "true").equalsIgnoreCase("false");
    }
    public void automatic(boolean enabled) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "settings-", ".tmp");
        try {
            Files.writeString(temporary, "checkAtStartup=" + enabled + "\n");
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
