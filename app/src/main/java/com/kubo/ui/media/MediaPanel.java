package com.kubo.ui.media;

import com.kubo.files.Download;
import com.kubo.files.FileBrowser;
import com.kubo.model.files.RemoteEntry;
import com.kubo.model.media.MediaKind;
import com.kubo.ui.Meter;
import com.kubo.ui.scope.Format;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A picture, a sound or a film from the server, shown without leaving Kubo.
 *
 * <p>Nothing streams. The file is copied to a scratch folder on this computer first, with a bar saying how
 * much of it has arrived, and deleted when the window closes. There is no way around that: a player needs to
 * seek, and seeking over an SSH channel that only goes forwards means asking the server for the file again
 * from the start every time somebody drags the slider.
 *
 * <p>Opening one file opens its neighbours with it. Somebody who opens a holiday video is almost never after
 * that one file; they are after the folder it is in. So the folder is listed, everything of the same kind is
 * kept, and the arrows walk through them without going back to the file manager.
 */
public final class MediaPanel extends VBox {

    /** Above this, the file is not fetched until somebody says so. A video can be larger than the disk. */
    private static final long ASK_ABOVE = 64L * 1024 * 1024;
    /** And above this it is refused: copying it would take longer than anybody would wait. */
    private static final long REFUSE_ABOVE = 4L * 1024 * 1024 * 1024;

    private final FileBrowser browser;
    private final Path scratch;
    private final MediaKind kind;

    private final Label name = new Label();
    private final Label where = new Label();
    private final Label state = new Label();
    private final Meter progress = new Meter(260, 4);
    private final Label progressText = new Label();
    private final Button cancel = rail("CANCEL");
    private final Button confirm = rail("COPY IT ANYWAY");
    private final Button outside = rail("OPEN IT OUTSIDE KUBO");
    private final VBox loading = new VBox(12);

    private final StackPane stage = new StackPane();
    private final ImageView picture = new ImageView();
    private final javafx.scene.media.MediaView film = new javafx.scene.media.MediaView();
    private final Label sound = new Label();

    private final Button playPause = rail("▶");
    private final Button previous = rail("◀");
    private final Button next = rail("▶▶");
    private final javafx.scene.control.Slider seek = new javafx.scene.control.Slider();
    private final Label clock = new Label();
    private final HBox transport;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-media");
        thread.setDaemon(true);
        return thread;
    });

    private List<RemoteEntry> neighbours = List.of();
    private int at;
    private MediaPlayer player;
    private AtomicBoolean cancelled = new AtomicBoolean();
    private Path fetched;
    private boolean seeking;

    /**
     * @param browser null to build the panel without a server, for the previews
     */
    public MediaPanel(FileBrowser browser, RemoteEntry file, MediaKind kind, Path scratch) {
        this.browser = browser;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.scratch = scratch;
        getStyleClass().add("media-panel");
        setSpacing(0);

        name.getStyleClass().add("media-name");
        where.getStyleClass().add("metric-label");
        state.getStyleClass().add("card-note");
        progressText.getStyleClass().add("metric-value");
        sound.getStyleClass().add("media-sound");

        picture.setPreserveRatio(true);
        picture.setSmooth(true);
        film.setPreserveRatio(true);
        // The surface follows the window rather than the file: a picture that arrives larger than the screen
        // should be brought down to it, and one smaller should not be blown up into mush.
        // Kept at one or above. A fit of zero or less means "no limit" to JavaFX, so before the stage has
        // been measured the subtraction goes negative, the height stops limiting anything, and a picture
        // wider than it is tall is scaled to the width and cropped at the bottom.
        picture.fitWidthProperty().bind(room(stage.widthProperty()));
        picture.fitHeightProperty().bind(room(stage.heightProperty()));
        film.fitWidthProperty().bind(room(stage.widthProperty()));
        film.fitHeightProperty().bind(room(stage.heightProperty()));

        stage.getStyleClass().add("media-stage");
        stage.setAlignment(Pos.CENTER);
        VBox.setVgrow(stage, Priority.ALWAYS);

        readable();
        loading.setAlignment(Pos.CENTER);
        loading.getChildren().addAll(state, progress, progressText, buttons());
        stage.getChildren().addAll(picture, film, sound, loading);

        transport = transport();
        getChildren().addAll(header(), stage, transport);

        if (file != null) {
            open(file);
        }
    }

    private HBox header() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox titles = new VBox(3, name, where);
        HBox row = new HBox(16, titles, spacer);
        row.getStyleClass().add("media-header");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14, 24, 14, 24));
        return row;
    }

    private HBox buttons() {
        cancel.setOnAction(event -> cancelled.set(true));
        confirm.setVisible(false);
        confirm.setManaged(false);
        outside.setVisible(false);
        outside.setManaged(false);
        outside.setOnAction(event -> handOver());
        HBox row = new HBox(10, cancel, confirm, outside);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    private HBox transport() {
        playPause.setOnAction(event -> toggle());
        previous.setOnAction(event -> step(-1));
        next.setOnAction(event -> step(1));
        clock.getStyleClass().add("metric-value");

        seek.setMin(0);
        seek.setMax(1);
        seek.getStyleClass().add("media-seek");
        HBox.setHgrow(seek, Priority.ALWAYS);
        // While a finger is on the slider the player must not move it, or it fights whoever is dragging.
        seek.setOnMousePressed(event -> seeking = true);
        seek.setOnMouseReleased(event -> {
            seeking = false;
            MediaPlayer running = player;
            if (running != null && running.getTotalDuration() != null) {
                running.seek(running.getTotalDuration().multiply(seek.getValue()));
            }
        });

        HBox row = new HBox(12, previous, playPause, next, seek, clock);
        row.getStyleClass().add("media-transport");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 24, 14, 24));
        return row;
    }

    /** Opens one file, and finds the ones beside it. */
    public void open(RemoteEntry file) {
        name.setText(file.name());
        at = 0;
        neighbours = List.of(file);
        where.setText("");
        show(file);
        if (browser == null) {
            return;
        }
        // The folder is listed on the side, after the file itself is on its way: the one somebody asked for
        // should not wait behind a listing of nine hundred others.
        String folder = RemoteEntry.parentOf(file.path()).orElse(null);
        if (folder == null) {
            return;
        }
        worker.execute(() -> {
            List<RemoteEntry> same = beside(folder);
            Platform.runLater(() -> {
                if (same.size() <= 1) {
                    return;
                }
                neighbours = same;
                at = Math.max(0, indexOf(same, file.path()));
                place();
            });
        });
    }

    private List<RemoteEntry> beside(String folder) {
        List<RemoteEntry> same = new ArrayList<>();
        for (RemoteEntry entry : browser.list(folder).entries()) {
            if (!entry.isDirectory() && MediaKind.playable(entry.name()).filter(kind::equals).isPresent()) {
                same.add(entry);
            }
        }
        same.sort(Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(same);
    }

    private static int indexOf(List<RemoteEntry> entries, String path) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).path().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    private void place() {
        boolean several = neighbours.size() > 1;
        previous.setDisable(!several);
        next.setDisable(!several);
        where.setText(several
                ? (at + 1) + " of " + neighbours.size() + " " + kind.plural() + " in this folder"
                : "");
    }

    private void step(int by) {
        if (neighbours.size() <= 1) {
            return;
        }
        // Round, so the last one leads back to the first rather than to a disabled button.
        at = Math.floorMod(at + by, neighbours.size());
        RemoteEntry entry = neighbours.get(at);
        name.setText(entry.name());
        place();
        show(entry);
    }

    /** Fetches one file and puts it on the stage. */
    private void show(RemoteEntry file) {
        stop();
        cancelled = new AtomicBoolean();
        picture.setImage(null);
        film.setVisible(false);
        sound.setVisible(false);
        waiting(true);

        if (file.sizeBytes() > REFUSE_ABOVE) {
            failed(file.name() + " is " + Format.bytes(file.sizeBytes())
                    + ". It has to be copied here before it can be played, and that is too much to copy.");
            return;
        }
        if (file.sizeBytes() > ASK_ABOVE) {
            state.setText(file.name() + " is " + Format.bytes(file.sizeBytes())
                    + ". It has to be copied to this computer before it can be played.");
            progress.setVisible(false);
            progressText.setText("");
            cancel.setVisible(false);
            cancel.setManaged(false);
            confirm.setVisible(true);
            confirm.setManaged(true);
            confirm.setOnAction(event -> fetch(file));
            return;
        }
        fetch(file);
    }

    private void fetch(RemoteEntry file) {
        if (browser == null) {
            return;
        }
        confirm.setVisible(false);
        confirm.setManaged(false);
        cancel.setVisible(true);
        cancel.setManaged(true);
        progress.setVisible(true);
        progress.setShare(0);
        state.setText("Copying " + file.name() + " from the server…");
        progressText.setText("0 B of " + Format.bytes(file.sizeBytes()));

        long total = Math.max(1, file.sizeBytes());
        AtomicBoolean stop = cancelled;
        worker.execute(() -> {
            Download done = browser.download(file, scratch, local(file), true,
                    received -> Platform.runLater(() -> {
                        progress.setShare((double) received / total);
                        progressText.setText(Format.bytes(received) + " of " + Format.bytes(file.sizeBytes()));
                    }),
                    stop::get);
            Platform.runLater(() -> {
                if (stop.get()) {
                    failed("Stopped.");
                    return;
                }
                if (done.saved() == null) {
                    failed(done.problem() == null ? "The file could not be copied." : done.problem());
                    return;
                }
                fetched = done.saved();
                play(done.saved());
            });
        });
    }

    /** A name of our own in the scratch folder, keeping the extension, which is what tells the player what it is. */
    private String local(RemoteEntry file) {
        String suffix = "";
        int dot = file.name().lastIndexOf('.');
        if (dot > 0) {
            suffix = file.name().substring(dot);
        }
        return "kubo-media" + suffix;
    }

    private void play(Path local) {
        waiting(false);
        if (kind == MediaKind.IMAGE) {
            Image image = new Image(local.toUri().toString(), false);
            if (image.isError()) {
                failed("This file is named like a picture but is not one Kubo can read.");
                return;
            }
            picture.setImage(image);
            return;
        }
        try {
            MediaPlayer started = new MediaPlayer(new Media(local.toUri().toString()));
            player = started;
            started.setOnError(() -> cannotDecode());
            started.currentTimeProperty().addListener((observable, before, now) -> tick(started));
            started.setOnEndOfMedia(() -> playPause.setText("▶"));
            if (kind == MediaKind.VIDEO) {
                film.setMediaPlayer(started);
                film.setVisible(true);
            } else {
                sound.setText(name.getText());
                sound.setVisible(true);
            }
            started.play();
            playPause.setText("❚❚");
        } catch (RuntimeException notPlayable) {
            // A name that lies about its contents, or a codec inside a container Kubo can open.
            cannotDecode();
        }
    }

    private void tick(MediaPlayer running) {
        Duration total = running.getTotalDuration();
        Duration now = running.getCurrentTime();
        if (total == null || total.isUnknown() || total.toMillis() <= 0) {
            return;
        }
        if (!seeking) {
            seek.setValue(now.toMillis() / total.toMillis());
        }
        clock.setText(spell(now) + " / " + spell(total));
    }

    private static String spell(Duration time) {
        long seconds = (long) Math.max(0, time.toSeconds());
        long hours = seconds / 3600;
        String rest = String.format(Locale.ROOT, "%02d:%02d", (seconds % 3600) / 60, seconds % 60);
        return hours > 0 ? hours + ":" + rest : rest;
    }

    private void toggle() {
        MediaPlayer running = player;
        if (running == null) {
            return;
        }
        if (running.getStatus() == MediaPlayer.Status.PLAYING) {
            running.pause();
            playPause.setText("▶");
        } else {
            running.play();
            playPause.setText("❚❚");
        }
    }

    private void waiting(boolean yes) {
        loading.setVisible(yes);
        loading.setManaged(yes);
        transport.setDisable(yes);
        transport.setVisible(kind.timed());
        transport.setManaged(kind.timed());
        if (yes) {
            clock.setText("");
            seek.setValue(0);
            playPause.setText("▶");
        }
    }

    /**
     * What to say when the file arrived whole and still will not play.
     *
     * <p>The player's own words for this are a class name and an enum, which tell a person nothing. The
     * container is fine, Kubo opened it; what is inside it is encoded with something JavaFX has no decoder
     * for, which on a screen recording is usually the colour format or a variable frame rate rather than
     * anything exotic.
     *
     * <p>And it offers the way out, because the file is already here. Whatever plays video on this computer
     * almost certainly plays this one.
     */
    private void cannotDecode() {
        failed("This file arrived whole, and Kubo cannot decode what is inside it. Kubo plays H.264 in MP4, "
                + "AAC, MP3, WAV and AIFF, which is all JavaFX carries a decoder for. Your computer very "
                + "likely plays this file already.");
        if (fetched != null) {
            outside.setVisible(true);
            outside.setManaged(true);
        }
    }

    /**
     * Saves the copy somewhere it will survive and hands it to whatever opens that kind of file here.
     *
     * <p>Out of the scratch folder first. That folder is emptied when this window closes, and handing a
     * player a file that is about to be deleted under it is worse than not offering at all.
     */
    private void handOver() {
        Path copy = fetched;
        if (copy == null) {
            return;
        }
        outside.setDisable(true);
        try {
            Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
            Path folder = Files.isDirectory(downloads) ? downloads
                    : Path.of(System.getProperty("user.home"));
            Path kept = folder.resolve(name.getText());
            Files.copy(copy, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            state.setText("Saved to " + kept + " and handed to this computer's own player.");
            java.awt.Desktop.getDesktop().open(kept.toFile());
        } catch (IOException | RuntimeException cannot) {
            state.setText("Kubo could not hand the file over: " + cannot.getMessage());
            outside.setDisable(false);
        }
    }

    private void failed(String reason) {
        waiting(true);
        progress.setVisible(false);
        progressText.setText("");
        cancel.setVisible(false);
        cancel.setManaged(false);
        confirm.setVisible(false);
        confirm.setManaged(false);
        outside.setVisible(false);
        outside.setManaged(false);
        outside.setDisable(false);
        state.setText(reason);
        // The arrows keep working: one file that will not play is no reason to shut the folder.
        transport.setDisable(neighbours.size() <= 1);
    }

    private void stop() {
        MediaPlayer running = player;
        player = null;
        if (running != null) {
            running.stop();
            running.dispose();
        }
        film.setMediaPlayer(null);
        cancelled.set(true);
        deleteFetched();
    }

    /** The message is wrapped, because it is a sentence and not a label. */
    private void readable() {
        state.setWrapText(true);
        state.setMaxWidth(560);
        state.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
    }

    private void deleteFetched() {
        Path copy = fetched;
        fetched = null;
        if (copy == null) {
            return;
        }
        try {
            Files.deleteIfExists(copy);
        } catch (IOException stillOpen) {
            // Windows holds a file the player has not let go of yet. The folder goes at shutdown anyway.
            copy.toFile().deleteOnExit();
        }
    }

    /** Stops everything and takes the copied file with it. */
    public void shutdown() {
        stop();
        worker.shutdownNow();
        try {
            if (scratch != null && Files.isDirectory(scratch)) {
                try (java.util.stream.Stream<Path> inside = Files.list(scratch)) {
                    inside.forEach(path -> path.toFile().delete());
                }
                Files.deleteIfExists(scratch);
            }
        } catch (IOException gone) {
            // Nothing to do about it, and nothing in there that matters after this.
        }
    }

    /** Only for the previews: the panel waiting on a file, with no server behind it. */
    public void showForPreview(String fileName, String said, double share, String received) {
        name.setText(fileName);
        state.setText(said);
        progress.setShare(share);
        progressText.setText(received);
        preview();
    }

    /** Only for the previews: a picture already on this computer, on the stage. */
    public void showPictureForPreview(String fileName, Path local) {
        name.setText(fileName);
        preview();
        waiting(false);
        picture.setImage(new Image(local.toUri().toString(), false));
    }

    /** Only for the previews: the transport as it looks part way through something. */
    public void showPlayingForPreview(String fileName, double where_, String time) {
        name.setText(fileName);
        preview();
        waiting(false);
        sound.setText(fileName);
        sound.setVisible(kind == MediaKind.AUDIO);
        seek.setValue(where_);
        clock.setText(time);
        playPause.setText("❚❚");
        transport.setDisable(false);
    }

    /** Only for the previews: the panel after a file arrived whole and would not decode. */
    public void showUndecodableForPreview(String fileName) {
        name.setText(fileName);
        preview();
        cannotDecode();
        outside.setVisible(true);
        outside.setManaged(true);
    }

    /** The neighbour line and arrows as they look with a folder behind them. */
    private void preview() {
        previous.setDisable(false);
        next.setDisable(false);
        where.setText("3 of 11 " + kind.plural() + " in this folder");
    }

    /** The room a surface has along one axis, never less than a pixel. */
    private static javafx.beans.binding.DoubleBinding room(
            javafx.beans.property.ReadOnlyDoubleProperty side) {
        return javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(1, side.get() - 48), side);
    }

    private static Button rail(String text) {
        Button button = new Button(text);
        button.getStyleClass().addAll("rail-button", "compact-button");
        button.setFocusTraversable(false);
        return button;
    }
}
