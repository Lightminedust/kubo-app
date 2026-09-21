package com.kubo.ui.services;

import com.kubo.command.CommandService;
import com.kubo.connection.ssh.SshConnection;
import com.kubo.model.services.Action;
import com.kubo.model.services.Dependencies;
import com.kubo.model.services.Lifeline;
import com.kubo.model.services.Move;
import com.kubo.model.services.Removal;
import com.kubo.model.services.UnitDraft;
import com.kubo.model.services.Resource;
import com.kubo.model.services.Signal;
import com.kubo.model.services.ServiceUnit;
import com.kubo.model.services.ServicesReport;
import com.kubo.model.services.Standing;
import com.kubo.privilege.RootSession;
import com.kubo.command.CommandEntry;
import com.kubo.privilege.Privilege;
import com.kubo.files.FileBrowser;
import com.kubo.services.ServiceControl;
import com.kubo.services.ServiceReader;
import com.kubo.ui.PromptCard;
import com.kubo.ui.RootButton;
import com.kubo.ui.docker.SudoPrompt;
import com.kubo.ui.ServerConsole;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The services screen: a masthead saying in one line whether the machine will come back the way it is now, the
 * map of every unit by what it means, and a console.
 *
 * <p>Reading needs nothing special. Changing needs root, and the three verbs that take something away make
 * the user type the service name back first. The two that would cut this very connection are refused rather
 * than confirmed; see {@link com.kubo.model.services.Lifeline}.
 */
public final class ServicesScope extends StackPane {

    private static final Color ALERT = Color.web("#ff2d55");
    private static final Color AMBER = Color.web("#ffb347");
    private static final Color SAFE = Color.web("#35e0a1");

    private final ServiceReader reader;
    private final ServiceControl control;
    /** Only for the tree of files the new-service screen walks. */
    private final FileBrowser browser;
    private ServiceBuilder builder;
    private final RootSession root;
    private final ServerConsole console;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubo-services");
        thread.setDaemon(true);
        return thread;
    });

    private final ServicesTree tree = new ServicesTree();
    /** The tree on the left, the detail of whatever is clicked on the right. Neither replaces the other. */
    private final BorderPane body = new BorderPane(tree);
    /** The service the panel beside the tree is standing on. */
    private ServiceUnit showing;
    /** What stands in the middle until the first reading lands. */
    private final VBox loading = buildLoading();
    private boolean everRead;
    /** The last reading, kept so a move can show itself before the next one comes back. */
    private ServicesReport reading = ServicesReport.EMPTY;
    /** The state of the machine drifts slowly; a re-read every half minute is plenty and costs one round trip. */
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(30), event -> refresh()));

    private final Label headline = label("READING…", "sheet-title");
    private final Label caption = label("", "sheet-caption");
    private final Label failedValue = label("—", "readout-value");
    private final Label returnValue = label("—", "readout-value");
    private final Label bootValue = label("—", "readout-value");
    private final Label problem = label("", "card-problem");
    private final Label done = label("", "card-success");
    /** What the server is being asked for right now, if anything. */
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Label busyText = label("", "card-note");
    private final HBox working = new HBox(10, busy, busyText);

    private Dependencies dependencies = Dependencies.EMPTY;
    private StackPane overlay;
    private ContextMenu menu;

    private static final System.Logger LOG = System.getLogger(ServicesScope.class.getName());

    public ServicesScope(ServiceReader reader, ServiceControl control, SshConnection connection,
                         CommandService commands, RootSession root) {
        this.reader = reader;
        this.control = control;
        this.root = root;
        this.browser = connection == null ? null : new FileBrowser(connection, commands);
        if (this.browser != null) {
            // The same root session as everything else on this screen, so making a folder under /opt works
            // here exactly as it does on the file screen rather than failing for no visible reason.
            this.browser.setRootSession(root);
        }
        this.console = new ServerConsole(connection, commands, root);
        // Folded to its bar on this screen: the tree is what the window is for, and the log is there to be
        // opened when something is being checked, not to take a third of it by default.
        this.console.setCollapsed(true);
        getStyleClass().add("scope-view");

        tree.setOnSelect(this::showDetail);
        tree.setOnMenu(this::showMenu);

        // The tree has nothing in it until systemd answers, and an empty screen says nothing about why.
        body.setCenter(loading);

        BorderPane layout = new BorderPane(body);
        layout.setTop(buildMasthead());
        layout.setBottom(console);
        getChildren().add(layout);
        poll.setCycleCount(Timeline.INDEFINITE);
    }

    /**
     * Fills the screen from a reading already in hand, for the previews. Nothing is sent to a server.
     */
    void preview(ServicesReport read, Dependencies links) {
        dependencies = links;
        dependencies = links;
        tree.setDependencies(links);
        apply(read);
    }

    ServicesTree tree() {
        return tree;
    }

    public void start() {
        refresh();
        poll.play();
    }

    public void shutdown() {
        poll.stop();
        tree.stop();
        console.shutdown();
        worker.shutdownNow();
    }

    // Reading

    private void refresh() {
        if (reader == null) {
            return;
        }
        worker.execute(() -> {
            ServicesReport read;
            try {
                read = reader.read();
            } catch (RuntimeException e) {
                // A screen stuck on "reading" tells the user nothing, and a stack trace goes to a terminal
                // they will never see.
                LOG.log(System.Logger.Level.ERROR, "Reading the services failed", e);
                Platform.runLater(() -> {
                    showProblem(reason(e));
                    stopWaiting(reason(e));
                });
                return;
            }
            Platform.runLater(() -> apply(read));
            // The links are a second, longer reading. Nothing on the screen waits for it: the map is already
            // up, and until it lands a click simply draws no links.
            try {
                Dependencies links = reader.relations(read.units());
                Platform.runLater(() -> {
                    dependencies = links;
                    tree.setDependencies(links);
                });
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Reading what depends on what failed", e);
            }
        });
    }

    private void apply(ServicesReport read) {
        reading = read;
        if (!everRead) {
            everRead = true;
            body.setCenter(tree);
        }
        tree.setReport(read);

        if (read.absent()) {
            headline.setText("NO SYSTEMD");
            headline.setStyle("-fx-text-fill: " + web(AMBER) + ";");
            caption.setText("THIS SERVER DOES NOT RUN SYSTEMD, SO IT HAS NO SERVICES TO SHOW");
            return;
        }

        int failed = read.count(Standing.FAILED);
        int leaving = read.count(Standing.WILL_NOT_RETURN);
        int stopped = read.count(Standing.NOT_RUNNING);
        int wrong = failed + leaving + stopped;

        headline.setText(failed > 0 ? failed + (failed == 1 ? " SERVICE FAILED" : " SERVICES FAILED")
                : wrong > 0 ? wrong + (wrong == 1 ? " SERVICE TO LOOK AT" : " SERVICES TO LOOK AT")
                : "EVERYTHING HOLDING");
        headline.setStyle("-fx-text-fill: " + web(failed > 0 ? ALERT : wrong > 0 ? AMBER : SAFE) + ";");
        caption.setText(failed > 0
                ? "SOMETHING TRIED TO START AND COULD NOT"
                : leaving > 0
                        ? "SOMETHING RUNNING NOW WOULD NOT COME BACK AFTER A REBOOT"
                        : stopped > 0
                                ? "SOMETHING THAT SHOULD BE RUNNING IS NOT"
                                : "EVERY SERVICE THAT IS UP COMES BACK AT THE NEXT BOOT");

        failedValue.setText(String.valueOf(failed));
        failedValue.setStyle("-fx-text-fill: " + web(failed > 0 ? ALERT : SAFE) + ";");
        returnValue.setText(String.valueOf(leaving));
        returnValue.setStyle("-fx-text-fill: " + web(leaving > 0 ? AMBER : SAFE) + ";");
        bootValue.setText(read.boot() == null ? "—" : ServicesTree.shortTime(read.boot()));

        List<String> unreadable = read.unreadable();
        showProblem(unreadable.isEmpty() ? null : String.join("  ·  ", unreadable));
    }

    /** The turning indicator and the line of what is being asked, for the wait when the screen opens. */
    private VBox buildLoading() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.getStyleClass().add("connection-progress");
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        Label title = label("READING THE SERVICES\u2026", "host-key-host");
        Label note = label("KUBO IS ASKING SYSTEMD WHAT IS LOADED, WHAT COMES BACK AT THE NEXT BOOT, AND "
                + "WHAT THE LAST ONE COST.", "lede");
        note.setWrapText(true);
        note.setMaxWidth(520);
        HBox head = new HBox(14, spinner, title);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(14, label("SERVICES.SCOPE", "series"), head, note);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setPadding(new Insets(40));
        return box;
    }

    /** The first reading failed outright. The wait says why rather than turning for ever. */
    private void stopWaiting(String trouble) {
        if (everRead) {
            return;
        }
        loading.getChildren().setAll(label("SERVICES.SCOPE", "series"),
                label("COULD NOT READ THE SERVICES", "host-key-host"),
                label(trouble, "card-problem"));
    }

    /**
     * Says what is being waited on.
     *
     * <p>Stopping a service can take systemd the best part of a minute, and a screen that does nothing at all
     * during it is a screen that looks broken. Whatever else happens, this turns until the answer comes back.
     */
    private void working(String what) {
        busyText.setText(what);
        working.setVisible(true);
        working.setManaged(true);
        done.setVisible(false);
        done.setManaged(false);
    }

    private void resting() {
        working.setVisible(false);
        working.setManaged(false);
    }

    private void showProblem(String text) {
        problem.setText(text == null ? "" : "▸ " + text);
        problem.setVisible(text != null);
        problem.setManaged(text != null);
    }

    private static String reason(RuntimeException problem) {
        return problem.getMessage() == null || problem.getMessage().isBlank()
                ? problem.getClass().getSimpleName() : problem.getMessage();
    }

    // One unit in full

    /**
     * A double click opens the card at once, saying it is reading, and fills it when the two commands answer.
     * Opening on the answer instead would leave the screen doing nothing visible for a second.
     */
    /**
     * Shows one service beside the tree: its table, its journal and what can be done to it.
     *
     * <p>The tree is never taken away for it. It keeps growing to the right as services are followed, and
     * this panel simply says what the last one clicked is — two things side by side rather than one screen
     * replacing another, which is what made the old version lose people.
     */
    private void showDetail(ServiceUnit unit) {
        showing = unit;
        ServiceSheet sheet = new ServiceSheet(unit, dependencies, control != null && control.available(),
                hands(unit), this::closeDetail);
        boolean replacing = body.getRight() != null;
        body.setRight(sheet);
        // A shorter push when one card replaces another: there the movement only has to say the contents
        // changed, while an arriving card has to say it arrived.
        arrive(sheet, replacing ? 16 : 40);
        if (reader == null) {
            return;
        }
        worker.execute(() -> {
            try {
                Map<String, String> properties = reader.detail(unit.name());
                String file = reader.unitFile(unit.name());
                Platform.runLater(() -> sheet.setDetail(properties, file));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading one unit failed", e);
                Platform.runLater(() -> sheet.setProblem(reason(e)));
                return;
            }
            try {
                String lines = reader.journal(unit.name(), 200);
                Platform.runLater(() -> sheet.setJournal(lines));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Reading a journal failed", e);
                Platform.runLater(() -> sheet.setJournal("The journal could not be read: " + reason(e)));
            }
        });
    }

    /**
     * Brings the card in from the right edge instead of having it simply be there.
     *
     * <p>A panel that appears between two frames is a panel people read as part of the screen rather than as
     * an answer to what they just clicked. The short push in is what says a click landed.
     *
     * @param from how far to the right it starts, in pixels
     */
    private static void arrive(Node card, double from) {
        card.setOpacity(0);
        card.setTranslateX(from);
        FadeTransition fade = new FadeTransition(Duration.millis(140), card);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(220), card);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade, slide).play();
    }

    private void closeDetail() {
        showing = null;
        body.setRight(null);
    }

    /**
     * Everything the card beside the tree can ask for.
     *
     * <p>Each of these answers the card with null when it worked and with a sentence when it did not, so a
     * failure lands where the thing was asked for rather than somewhere else on the screen.
     */
    private ServiceSheet.Hands hands(ServiceUnit unit) {
        return new ServiceSheet.Hands() {

            @Override
            public void act(Action action) {
                ask(action, unit);
            }

            @Override
            public void limit(Resource resource, String value, Consumer<String> done) {
                send(done, () -> control.setLimit(resource, value, unit.name()));
            }

            @Override
            public void signal(Signal signal, boolean everything, Consumer<String> done) {
                send(done, () -> control.signal(signal, everything, unit.name()));
            }

            @Override
            public void saveSettings(String content, Consumer<String> done) {
                send(done, () -> {
                    CommandEntry written = control.saveOverride(unit.name(), content);
                    // No point telling systemd to re-read a file that was never written.
                    return written.success() ? control.reloadUnits() : written;
                });
            }

            @Override
            public void clearSettings(Consumer<String> done) {
                send(done, () -> {
                    CommandEntry gone = control.removeOverride(unit.name());
                    return gone.success() ? control.reloadUnits() : gone;
                });
            }

            @Override
            public void remove(String filePath) {
                askRemoval(unit, filePath);
            }

            @Override
            public void readSettings(Consumer<String> back) {
                if (reader == null) {
                    return;
                }
                worker.execute(() -> {
                    String held;
                    try {
                        held = reader.settings(unit.name());
                    } catch (RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING, "Reading a drop-in failed", e);
                        return;
                    }
                    Platform.runLater(() -> back.accept(held));
                });
            }
        };
    }

    /**
     * Runs one thing on the server for the card, asking for the sudo password first if it is not held yet.
     *
     * <p>Everything that can go wrong comes back the same way: no root, a value systemd would not take, a
     * refusal to cut this very connection, or a command that simply failed. The card shows the sentence.
     */
    private void send(Consumer<String> done, java.util.function.Supplier<CommandEntry> work) {
        send(done, work, "ASKING THE SERVER\u2026");
    }

    private void send(Consumer<String> done, java.util.function.Supplier<CommandEntry> work, String saying) {
        if (control == null || !control.available()) {
            done.accept("Kubo cannot change anything on this server: it has no way to become root.");
            return;
        }
        working(saying);
        withRoot(() -> worker.execute(() -> {
            String trouble;
            try {
                CommandEntry result = work.get();
                trouble = result.success() ? null
                        : firstLine(result.error().isBlank() ? result.output() : result.error());
            } catch (IllegalArgumentException | IllegalStateException e) {
                // A value Kubo would not send, or an action it will not run: its own words, not systemd's.
                trouble = e.getMessage();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "A service change failed", e);
                trouble = reason(e);
            }
            String said = trouble;
            Platform.runLater(() -> {
                resting();
                done.accept(said);
                if (said == null) {
                    // What it really did is only known from the next reading.
                    refresh();
                }
            });
        }));
    }

    /**
     * What can be done with the service that was right clicked.
     *
     * <p>A standing is not a place a service is put: it is what its two answers add up to. So "move it to
     * running" is a short sequence of ordinary verbs, and only the moves that would really land where they
     * say are offered — a one-shot task that has finished is idle whatever is done to it, and is offered
     * nothing rather than offered a move that leaves the card where it was.
     */
    private void showMenu(ServicesTree.MenuRequest request) {
        if (menu != null) {
            menu.hide();
        }
        ServiceUnit unit = request.unit();
        List<MenuItem> items = new ArrayList<>();
        items.add(item("SHOW " + unit.shortName().toUpperCase(Locale.ROOT),
                event -> showDetail(unit)));
        items.add(new SeparatorMenuItem());

        if (control == null || !control.available()) {
            MenuItem none = new MenuItem("MOVING A SERVICE NEEDS ROOT");
            none.setDisable(true);
            items.add(none);
        } else {
            List<Standing> targets = Move.targets(unit);
            if (targets.isEmpty()) {
                MenuItem none = new MenuItem("NOTHING WOULD MOVE THIS ONE");
                none.setDisable(true);
                items.add(none);
            }
            for (Standing target : targets) {
                MenuItem entry = item("MOVE TO  " + target.label(), event -> askMove(target, unit));
                // The moves that take something away are marked as such, the way they are everywhere else.
                if (Move.stepsTo(target, unit).stream().anyMatch(Action::takesAway)) {
                    entry.getStyleClass().add("redis-menu-danger");
                }
                items.add(entry);
            }
            items.add(new SeparatorMenuItem());
            // Whether it may go at all depends on where its file is, which is one read away. The question is
            // asked once that answer is in, rather than offered here and then taken back.
            MenuItem delete = item("DELETE  " + unit.shortName().toUpperCase(Locale.ROOT) + "\u2026",
                    event -> findThenRemove(unit));
            delete.getStyleClass().add("redis-menu-danger");
            items.add(delete);
        }
        menu = new ContextMenu(items.toArray(new MenuItem[0]));
        // A context menu is a window of its own, so Kubo's stylesheet does not reach it by itself.
        menu.getStyleClass().add("redis-context-menu");
        menu.show(tree, request.screenX(), request.screenY());
    }

    private static MenuItem item(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        MenuItem entry = new MenuItem(text);
        entry.setOnAction(action);
        return entry;
    }

    /**
     * Asks before a move that takes something away, and refuses outright the ones that would cut this very
     * connection — each step of the move is judged, not only the move as a whole.
     */
    private void askMove(Standing target, ServiceUnit unit) {
        List<Action> steps = Move.stepsTo(target, unit);
        for (Action step : steps) {
            String refusal = Lifeline.refusal(step, unit.name());
            if (refusal != null) {
                showOverlay(PromptCard.output("SERVICES", "MOVE " + unit.shortName(), refusal,
                        this::hideOverlay));
                return;
            }
        }
        boolean takesAway = steps.stream().anyMatch(Action::takesAway);
        String what = describe(steps);
        if (!takesAway) {
            walk(steps, unit, target, null);
            return;
        }
        String expected = unit.shortName();
        showOverlay(new PromptCard("SERVICES", "MOVE TO  " + target.label(),
                "This runs " + what + " on " + expected + ". " + reach(steps.get(0), unit)
                        + " Type the name of the service to confirm.",
                expected, "MOVE IT", true, (typed, finished) -> {
                    if (!typed.strip().equals(expected)) {
                        finished.accept("Type " + expected + " exactly to confirm.");
                        return;
                    }
                    walk(steps, unit, target, finished);
                }, this::hideOverlay));
    }

    /** The steps in words, so the card says what it is about to run rather than only where it lands. */
    private static String describe(List<Action> steps) {
        List<String> words = new ArrayList<>();
        for (Action step : steps) {
            words.add(step.label().toLowerCase(Locale.ROOT));
        }
        return String.join(" then ", words);
    }

    /**
     * Runs the steps of a move in order, stopping at the first one that fails.
     *
     * <p>A move is not one command, so it can fail half way. When it does, the screen says which step failed
     * and re-reads: leaving a service between two standings without saying so would be the worst of it.
     */
    private void walk(List<Action> steps, ServiceUnit unit, Standing target, Consumer<String> finished) {
        working("MOVING " + unit.shortName().toUpperCase(Locale.ROOT) + " TO " + target.label() + "\u2026");
        withRoot(() -> worker.execute(() -> {
            String trouble = null;
            Action failedAt = null;
            for (Action step : steps) {
                try {
                    CommandEntry result = control.run(step, unit.name());
                    if (!result.success()) {
                        trouble = firstLine(result.error().isBlank() ? result.output() : result.error());
                    }
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "A step of a move failed", e);
                    trouble = reason(e);
                }
                if (trouble != null) {
                    failedAt = step;
                    break;
                }
            }
            String said = trouble == null ? null
                    : failedAt.label() + " FAILED, SO THE MOVE STOPPED THERE: " + trouble;
            Platform.runLater(() -> {
                resting();
                if (finished != null) {
                    finished.accept(said);
                }
                if (said == null) {
                    hideOverlay();
                    done.setText(unit.shortName() + "  MOVED TO  " + target.label());
                    done.setVisible(true);
                    done.setManaged(true);
                    // The card moves now rather than in five round trips' time. This is what the verbs that
                    // just succeeded imply, and the reading that follows either agrees or corrects it.
                    apply(with(reading, Move.after(steps, unit)));
                } else if (finished == null) {
                    showProblem(said);
                }
                refresh();
            });
        }));
    }

    /**
     * The screen for making something on this server into a service.
     *
     * <p>It takes the middle of the window rather than a window of its own, and the detail panel steps aside
     * while it is there: one thing is being done, so one thing is on the screen.
     */
    private void openBuilder() {
        if (browser == null) {
            return;
        }
        closeDetail();
        builder = new ServiceBuilder(browser, worker, this::write, this::closeBuilder,
                this::showOverlay, this::hideOverlay);
        body.setCenter(builder);
    }

    private void closeBuilder() {
        if (builder != null) {
            builder.stop();
            builder = null;
        }
        body.setCenter(everRead ? tree : loading);
    }

    /**
     * Writes a new service, tells systemd it is there, and does what was asked with it.
     *
     * <p>Four separate commands, and any of them can fail on its own. The screen says which one did rather
     * than a single word for the lot: a unit that is written but not reloaded is a different problem from one
     * that was reloaded and would not start.
     */
    private void write(UnitDraft draft, String text, Consumer<String> done) {
        if (control == null || !control.available()) {
            done.accept("Kubo cannot write a service on this server: it has no way to become root.");
            return;
        }
        boolean startIt = builder != null && builder.startsNow();
        String unit = draft.unitName();
        working("WRITING " + unit.toUpperCase(Locale.ROOT) + "\u2026");
        withRoot(() -> worker.execute(() -> {
            String trouble = null;
            try {
                if (reader != null && reader.exists(unit)) {
                    trouble = "There is already a unit called " + unit + " on this server. "
                            + "Kubo will not write over it.";
                }
                if (trouble == null) {
                    // The text the screen showed, not a second generation of it.
                    trouble = step(control.createUnit(unit, text), "writing " + unit);
                }
                if (trouble == null) {
                    trouble = step(control.reloadUnits(), "telling systemd the file is there");
                }
                if (trouble == null && draft.atBoot()) {
                    trouble = step(control.run(Action.ENABLE, unit), "setting it to start at boot");
                }
                if (trouble == null && startIt) {
                    trouble = step(control.run(Action.START, unit), "starting it");
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Creating a service failed", e);
                trouble = reason(e);
            }
            String said = trouble;
            Platform.runLater(() -> {
                resting();
                done.accept(said);
                if (said != null) {
                    // Said in both places: the builder stays open with it, and the masthead keeps it after
                    // the builder is closed by hand.
                    showProblem(said);
                }
                if (said == null) {
                    closeBuilder();
                    this.done.setText(unit + "  CREATED"
                            + (startIt ? " AND STARTED" : "") + (draft.atBoot() ? " AND SET TO START AT BOOT" : ""));
                    this.done.setVisible(true);
                    this.done.setManaged(true);
                    refresh();
                }
            });
        }));
    }

    /** The same reading with one service replaced, which is how a move is shown before it is confirmed. */
    private static ServicesReport with(ServicesReport report, ServiceUnit moved) {
        List<ServiceUnit> units = new ArrayList<>(report.units().size());
        for (ServiceUnit unit : report.units()) {
            units.add(unit.name().equals(moved.name()) ? moved : unit);
        }
        return new ServicesReport(units, report.boot(), report.userspace(), report.unreadable());
    }

    /** One command of a sequence, named by what it was for so a failure says where it stopped. */
    private static String step(CommandEntry result, String what) {
        return result.success() ? null
                : "Stopped while " + what + ": "
                        + firstLine(result.error().isBlank() ? result.output() : result.error());
    }

    // Acting

    /**
     * Anything that takes something away makes the user type the service name back first. Anything that only
     * adds \u2014 starting, enabling \u2014 runs on the click, because the button beside it undoes it.
     */
    private void ask(Action action, ServiceUnit unit) {
        String refusal = Lifeline.refusal(action, unit.name());
        if (refusal != null) {
            // The guard is in the service layer as well; this is the one the user reads.
            showOverlay(PromptCard.output("SERVICES", action.label() + " " + unit.shortName(), refusal,
                    this::hideOverlay));
            return;
        }
        if (!action.takesAway()) {
            act(action, unit, null);
            return;
        }
        String expected = unit.shortName();
        showOverlay(new PromptCard("SERVICES", action.label() + "  " + expected,
                action.meaning() + " " + reach(action, unit) + " Type the name of the service to confirm.",
                expected, action.label(), true, (typed, finished) -> {
                    if (!typed.strip().equals(expected)) {
                        finished.accept("Type " + expected + " exactly to confirm.");
                        return;
                    }
                    act(action, unit, finished);
                }, this::hideOverlay));
    }

    /**
     * Asks before taking a service off the machine, saying which file goes.
     *
     * <p>The path is in the question because it is the whole of the judgement: a file in
     * /etc/systemd/system was put there by hand and is the user's to remove, and one anywhere else belongs
     * to a package. The refusal is asked for again here even though the card only offers the button when it
     * would be allowed, because this is the verb with nothing behind it.
     */
    private void askRemoval(ServiceUnit unit, String filePath) {
        String refusal = Removal.refusal(unit.name(), filePath);
        if (refusal != null) {
            showOverlay(PromptCard.output("SERVICES", "DELETE " + unit.shortName(), refusal,
                    this::hideOverlay));
            return;
        }
        List<Action> steps = Removal.steps(unit);
        String expected = unit.shortName();
        String what = steps.isEmpty() ? "This deletes " : "This runs " + describe(steps) + ", then deletes ";
        showOverlay(new PromptCard("SERVICES", "DELETE  " + expected,
                what + filePath + " and any settings Kubo wrote beside it. Nothing undoes this: the file is "
                        + "gone, not disabled." + reach(Action.STOP, unit)
                        + " Type the name of the service to confirm.",
                expected, "DELETE IT", true, (typed, finished) -> {
                    if (!typed.strip().equals(expected)) {
                        finished.accept("Type " + expected + " exactly to confirm.");
                        return;
                    }
                    erase(steps, unit, filePath, finished);
                }, this::hideOverlay));
    }

    /**
     * Stops it, disables it, deletes its file, takes its settings with it and tells systemd.
     *
     * <p>Five commands, and the order is the point. Deleting the file of a running service would leave the
     * process up with nothing behind it until the next reboot, and deleting an enabled one would leave boot
     * symlinks pointing at a file that is gone. Anything failing before the file goes stops the whole thing,
     * so a half-deleted service is not a state this can end in.
     */
    private void erase(List<Action> steps, ServiceUnit unit, String filePath, Consumer<String> finished) {
        working("DELETING " + unit.shortName().toUpperCase(Locale.ROOT) + "\u2026");
        withRoot(() -> worker.execute(() -> {
            String trouble = null;
            try {
                for (Action first : steps) {
                    CommandEntry result = control.run(first, unit.name());
                    if (!result.success()) {
                        trouble = first.label() + " FAILED, SO NOTHING WAS DELETED: "
                                + firstLine(result.error().isBlank() ? result.output() : result.error());
                        break;
                    }
                }
                if (trouble == null) {
                    trouble = step(control.removeUnit(unit.name(), filePath), "deleting " + filePath);
                }
                if (trouble == null) {
                    // A drop-in for a unit that is gone is a file nothing will ever read again.
                    trouble = step(control.removeOverride(unit.name()), "removing its own settings");
                }
                if (trouble == null) {
                    trouble = step(control.reloadUnits(), "telling systemd the file has gone");
                }
                if (trouble == null) {
                    // Allowed to find nothing: it only clears a failed entry, and most units never failed.
                    control.resetFailed(unit.name());
                }
            } catch (IllegalStateException e) {
                // Kubo refusing, in its own words.
                trouble = e.getMessage();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Deleting a service failed", e);
                trouble = reason(e);
            }
            String said = trouble;
            Platform.runLater(() -> {
                resting();
                if (finished != null) {
                    finished.accept(said);
                }
                if (said == null) {
                    hideOverlay();
                    closeDetail();
                    done.setText(unit.name() + "  DELETED");
                    done.setVisible(true);
                    done.setManaged(true);
                    refresh();
                }
            });
        }));
    }

    /**
     * Reads where the unit file is, then asks. Only the tree needs this: the card already knows the path.
     */
    private void findThenRemove(ServiceUnit unit) {
        if (reader == null) {
            return;
        }
        working("READING " + unit.shortName().toUpperCase(Locale.ROOT) + "\u2026");
        worker.execute(() -> {
            String filePath;
            try {
                filePath = reader.detail(unit.name()).getOrDefault("FragmentPath", "");
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Reading one unit failed", e);
                Platform.runLater(() -> {
                    resting();
                    showProblem(reason(e));
                });
                return;
            }
            String found = filePath;
            Platform.runLater(() -> {
                resting();
                askRemoval(unit, found);
            });
        });
    }

    /** What else this may take with it, when the reading knows of something leaning on this service. */
    private String reach(Action action, ServiceUnit unit) {
        List<String> dependents = dependencies.neededByOf(unit.name());
        if (dependents.isEmpty() || action == Action.ENABLE || action == Action.DISABLE) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String name : dependents) {
            names.add(name.endsWith(".service") ? name.substring(0, name.length() - ".service".length()) : name);
        }
        boolean one = names.size() == 1;
        return "systemd says " + String.join(", ", names) + (one ? " depends" : " depend") + " on it, so "
                + (one ? "it may go too." : "they may go too.");
    }

    private void act(Action action, ServiceUnit unit, Consumer<String> finished) {
        if (control == null) {
            return;
        }
        working(action.label() + " " + unit.shortName().toUpperCase(Locale.ROOT) + "\u2026");
        withRoot(() -> worker.execute(() -> {
            CommandEntry result;
            try {
                result = control.run(action, unit.name());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Acting on a service failed", e);
                Platform.runLater(() -> report(null, action, unit, reason(e), finished));
                return;
            }
            Platform.runLater(() -> report(result, action, unit, null, finished));
        }));
    }

    /**
     * systemctl says nothing at all when it works, so the screen says it instead and reads the machine again:
     * what a verb really did is only known from the next reading.
     */
    private void report(CommandEntry result, Action action, ServiceUnit unit, String failure,
                        Consumer<String> finished) {
        String trouble = failure != null ? failure
                : result.success() ? null : firstLine(result.error().isBlank() ? result.output() : result.error());
        resting();
        if (trouble != null) {
            if (finished != null) {
                finished.accept(trouble);
            } else {
                showProblem(trouble);
            }
            return;
        }
        hideOverlay();
        done.setText(action.label() + "  " + unit.shortName() + "  \u2014 DONE");
        done.setVisible(true);
        done.setManaged(true);
        refresh();
    }

    private static String firstLine(String text) {
        return text == null ? "FAILED" : text.strip().lines().findFirst().orElse("FAILED").toUpperCase(Locale.ROOT);
    }

    /** Asks for the sudo password once, when it is not already held for this session. */
    private void withRoot(Runnable action) {
        if (control != null && control.needsPassword() && root != null) {
            showOverlay(new SudoPrompt(root.privilege(), verification(), () -> {
                root.refresh();
                hideOverlay();
                action.run();
            }, this::hideOverlay));
        } else {
            action.run();
        }
    }

    /** Checks a sudo password against this server, for the root button and the prompts. */
    private BiConsumer<Privilege.Command, Consumer<Boolean>> verification() {
        if (control == null) {
            return RootButton.noVerification();
        }
        return (command, answer) -> worker.execute(() -> {
            CommandEntry entry = control.verify(command);
            Platform.runLater(() -> answer.accept(entry.success()));
        });
    }

    private void showOverlay(Region card) {
        hideOverlay();
        Region scrim = new Region();
        scrim.getStyleClass().add("picker-scrim");
        scrim.setOnMousePressed(event -> hideOverlay());
        overlay = new StackPane(scrim, card);
        overlay.setMinSize(0, 0);
        getChildren().add(overlay);
    }

    private void hideOverlay() {
        if (overlay != null) {
            getChildren().remove(overlay);
            overlay = null;
        }
    }

    // Building

    private VBox buildMasthead() {
        Label wordmark = RootButton.wordmark();
        Label mark = label("S", "sheet-mark");
        Label kicker = label("SERVICES.SCOPE", "series");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox camera = new HBox(2, rail("−", event -> tree.zoomOut()), rail("FIT", event -> tree.fit()),
                rail("+", event -> tree.zoomIn()));
        camera.getStyleClass().add("zoom-bar");
        RootButton rootButton = new RootButton(root, verification(), this::showOverlay, this::hideOverlay);
        HBox identity = new HBox(12, wordmark, mark, kicker, spacer, rootButton, camera);
        identity.setAlignment(Pos.CENTER_LEFT);

        VBox naming = new VBox(2, headline, caption);
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox readout = new HBox(0, readout("FAILED", failedValue), rule(),
                readout("WON'T SURVIVE A REBOOT", returnValue), rule(), readout("LAST BOOT", bootValue));
        readout.getStyleClass().add("readout-bar");
        readout.setAlignment(Pos.CENTER_LEFT);
        HBox title = new HBox(26, naming, titleSpacer, readout);
        title.setAlignment(Pos.CENTER_LEFT);

        done.setVisible(false);
        done.setManaged(false);
        busy.getStyleClass().add("connection-progress");
        busy.setPrefSize(14, 14);
        busy.setMaxSize(14, 14);
        working.setAlignment(Pos.CENTER_LEFT);
        working.setVisible(false);
        working.setManaged(false);
        VBox masthead = new VBox(12, identity, title, buildRail(), working, done, problem);
        masthead.getStyleClass().add("sheet-masthead");
        masthead.setPadding(new Insets(16, 34, 0, 26));
        problem.setWrapText(true);
        problem.setMaxWidth(900);
        showProblem(null);
        return masthead;
    }

    private HBox buildRail() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox rail = new HBox(8, rail("↻ RE-READ", event -> refresh()),
                rail("+ NEW SERVICE", event -> openBuilder()),
                rail("SHELL", event -> console.showShell()), spacer,
                rail("?", event -> showOverlay(new ServicesHelp(this::hideOverlay))));
        rail.setAlignment(Pos.CENTER_LEFT);
        rail.setPadding(new Insets(0, 0, 10, 0));
        return rail;
    }

    private static Button rail(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("rail-button");
        button.setFocusTraversable(false);
        button.setOnAction(action);
        return button;
    }

    private VBox readout(String caption, Label value) {
        VBox box = new VBox(0, label(caption, "readout-caption"), value);
        box.setPadding(new Insets(0, 20, 0, 20));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Region rule() {
        Region rule = new Region();
        rule.getStyleClass().add("readout-rule");
        rule.setMinWidth(1);
        rule.setPrefWidth(1);
        rule.setMinHeight(30);
        return rule;
    }

    private static String web(Color colour) {
        return String.format("#%02x%02x%02x", (int) Math.round(colour.getRed() * 255),
                (int) Math.round(colour.getGreen() * 255), (int) Math.round(colour.getBlue() * 255));
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
