package com.kubo.ui.logs;

import com.kubo.KuboApp;
import com.kubo.logs.SessionHistory.Command;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the logs history with sample commands; called by the files preview, on the JavaFX thread.
 */
public final class LogsPreview {

    private LogsPreview() {
    }

    public static void render(Path output) throws IOException {
        LogsView view = new LogsView(output.resolve("no-logs-here"), null, null);
        Scene scene = new Scene(view, 1260, 820, Color.web("#050508"));
        scene.getStylesheets().add(KuboApp.STYLESHEET);

        Instant start = Instant.parse("2026-09-15T09:12:00Z");
        List<Command> commands = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            commands.add(new Command(start.plusSeconds(i * 2L), "MONITOR",
                    "echo @@stat; head -n1 /proc/stat; echo @@meminfo; cat /proc/meminfo; echo @@net; cat /proc/net/dev",
                    0, 38 + i, 4_210, ""));
        }
        commands.add(new Command(start.plusSeconds(14), "FILES", "id -u; id -un; id -Gn; printf '%s\\n' \"$HOME\"",
                0, 31, 42, ""));
        commands.add(new Command(start.plusSeconds(15), "FILES",
                "P='/home/xana/projects'; if [ ! -d \"$P\" ] || [ ! -r \"$P\" ] || [ ! -x \"$P\" ]; then printf '@@DENIED@@'; exit 0; fi; find \"$P\" -mindepth 1 -maxdepth 1 -printf ...",
                0, 61, 1_830, ""));
        commands.add(new Command(start.plusSeconds(40), "USER", "cd -- '/home/xana' && cat secrets.env", 1, 22, 0,
                "cat: secrets.env: Permission denied"));
        commands.add(new Command(start.plusSeconds(62), "FILES",
                "F='/home/xana/projects/'; N='gemmie-2.1.0.tar.gz'; D=\"$F$N\"; if [ -e \"$D\" ] || [ -L \"$D\" ]; then echo 'Something with this name is already there' >&2; exit 17; fi; T=$(mktemp -p \"$F\" .kubo-upload.XXXXXX) || exit 1; cat > \"$T\"; ...",
                0, 19_420, 45, ""));
        commands.add(new Command(start.plusSeconds(90), "FILES", "rm -rf --one-file-system -- '/home/xana/tmp/build'",
                -1, 15_000, 0, "No complete answer within 15 s"));
        view.showSample("xana@173.212.254.237:22", start, commands);
        view.applyCss();
        view.layout();

        WritableImage image = scene.snapshot(null);
        Path file = output.resolve("logs.png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        view.shutdown();
        System.out.println("Wrote " + file.toAbsolutePath());
    }
}
